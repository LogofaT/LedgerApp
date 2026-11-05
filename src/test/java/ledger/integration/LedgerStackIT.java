package ledger.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs only from the Docker Compose integration profile against both real app instances.
 * The application/network harness is intentionally black-box: no H2 substitutions or mocks.
 */
class LedgerStackIT {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static final String ONE=System.getenv().getOrDefault("LEDGER_BASE_URL","http://localhost:8080");
    private static final String TWO=System.getenv().getOrDefault("LEDGER_SECOND_URL","http://localhost:8081");

    @BeforeAll static void waitForApplications() throws Exception {
        long end=System.nanoTime()+Duration.ofSeconds(120).toNanos();
        Exception lastFailure=null;
        while(System.nanoTime()<end) {
            try {
                HttpResponse<String> a=HTTP.send(HttpRequest.newBuilder(URI.create(ONE+"/openapi.yaml")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.ofString());
                HttpResponse<String> b=HTTP.send(HttpRequest.newBuilder(URI.create(TWO+"/openapi.yaml")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.ofString());
                if(a.statusCode()==200&&b.statusCode()==200)return;
                lastFailure=new IllegalStateException("Application readiness returned HTTP "
                        +a.statusCode()+" and "+b.statusCode());
            } catch(java.io.IOException failure) { lastFailure=failure; }
            Thread.sleep(500);
        }
        fail("Both application instances did not become ready in 120 seconds",lastFailure);
    }

    @Test void receiptsRefusalsAndReplaysAreSharedAcrossInstancesAndRejectResidualWrites() throws Exception {
        String account="it-replay-"+UUID.randomUUID();
        String accountKey=key("account");
        HttpResponse<String> created=post(ONE,"/api/v1/accounts",accountKey,
                "{\"accountId\":\""+account+"\",\"currency\":\"EUR\",\"overdraftLimit\":\"0\"}");
        assertEquals(201,created.statusCode());
        HttpResponse<String> replay=post(TWO,"/api/v1/accounts",accountKey,
                "{\"accountId\":\""+account+"\",\"currency\":\"EUR\",\"overdraftLimit\":\"0\"}");
        assertEquals(201,replay.statusCode());
        assertEquals(created.body(),replay.body());
        assertEquals(created.headers().firstValue("Location"),replay.headers().firstValue("Location"));
        assertEquals(409,post(TWO,"/api/v1/accounts",accountKey,
                "{\"accountId\":\"changed-"+account+"\",\"currency\":\"EUR\",\"overdraftLimit\":\"0\"}").statusCode());
        String malformedKey=key("malformed");
        HttpResponse<String> malformed=post(ONE,"/api/v1/accounts",malformedKey,"{");
        assertEquals(400,malformed.statusCode());
        HttpResponse<String> malformedReplay=post(TWO,"/api/v1/accounts",malformedKey,"{");
        assertEquals(400,malformedReplay.statusCode());
        assertEquals(malformed.body(),malformedReplay.body());
        assertEquals(409,post(TWO,"/api/v1/accounts",malformedKey,"{\"accountId\":\"other\"}").statusCode());

        String badKey=key("refusal");
        String refusalDescription="refused-"+account;
        String badTx="{\"currency\":\"EUR\",\"description\":\""+refusalDescription+"\",\"entries\":["
                +"{\"accountId\":\""+account+"\",\"direction\":\"DEBIT\",\"amount\":\"1.00\"},"
                +"{\"accountId\":\"SETTLEMENT-EUR\",\"direction\":\"CREDIT\",\"amount\":\"1.00\"}]}";
        long transactionsBefore=transactionCount(refusalDescription);
        HttpResponse<String> refused=post(ONE,"/api/v1/transactions",badKey,badTx);
        assertEquals(422,refused.statusCode());
        HttpResponse<String> refusedAgain=post(TWO,"/api/v1/transactions",badKey,badTx);
        assertEquals(422,refusedAgain.statusCode());
        assertEquals(refused.body(),refusedAgain.body());
        assertEquals(409,post(TWO,"/api/v1/transactions",badKey,badTx.replace("1.00","2.00")).statusCode());
        assertEquals("0.00",get(ONE,"/api/v1/accounts/"+account).get("balance").asText());
        assertEquals(transactionsBefore,transactionCount(refusalDescription),"A deterministic refusal must not create a transaction");

        String unsupportedKey=key("unsupported");
        String unsupported="{\"accountId\":\"it-gbp-"+UUID.randomUUID()+"\",\"currency\":\"GBP\",\"overdraftLimit\":\"0\"}";
        assertEquals(422,post(ONE,"/api/v1/accounts",unsupportedKey,unsupported).statusCode());
        assertEquals(422,post(TWO,"/api/v1/accounts",unsupportedKey,unsupported).statusCode());
        assertEquals(400,post(ONE,"/api/v1/transactions",key("numeric"),"{\"currency\":\"EUR\",\"description\":\"x\",\"entries\":[{\"accountId\":\"x\",\"direction\":\"DEBIT\",\"amount\":1}]}").statusCode());
    }

    @Test void accountIdentifiersAreExactAndCaseVariantsKeepSeparateBalances() throws Exception {
        String lower="it-case-"+UUID.randomUUID();
        String upper=lower.toUpperCase(Locale.ROOT);
        String body="{\"accountId\":\""+lower+"\",\"currency\":\"EUR\"}";
        String requestKey=key("case-key");
        HttpResponse<String> opened=post(ONE,"/api/v1/accounts",requestKey,body);
        assertEquals(201,opened.statusCode(),opened.body());
        assertEquals("0.00",JSON.readTree(opened.body()).path("overdraftLimit").asText());
        assertEquals(opened.body(),post(TWO,"/api/v1/accounts",requestKey,body).body());
        assertEquals(409,post(TWO,"/api/v1/accounts",requestKey.toUpperCase(Locale.ROOT),body).statusCode(),
                "A differently cased key is a new command, not a replay");

        String aliases="{\"currency\":\"EUR\",\"description\":\"alias refusal\",\"entries\":["
                +"{\"accountId\":\""+lower+"\",\"direction\":\"DEBIT\",\"amount\":\"1\"},"
                +"{\"accountId\":\""+upper+"\",\"direction\":\"CREDIT\",\"amount\":\"1\"}]}";
        List<String> before=financialState();
        HttpResponse<String> refused=post(TWO,"/api/v1/transactions",key("alias"),aliases);
        assertEquals(404,refused.statusCode(),refused.body());
        assertEquals("ACCOUNT_NOT_FOUND",JSON.readTree(refused.body()).path("code").asText());
        assertEquals(before,financialState());
        assertEquals(404,post(ONE,"/api/v1/transactions",key("space-alias"),
                aliases.replace(upper,lower+" ")).statusCode(),
                "Trailing spaces must not alias an existing account");

        assertEquals(201,post(TWO,"/api/v1/accounts",key("upper"),body.replace(lower,upper)).statusCode());
        createTransfer("fund lower","SETTLEMENT-EUR",lower,"10","EUR",ONE);
        createTransfer("fund upper","SETTLEMENT-EUR",upper,"5","EUR",TWO);
        createTransfer("case transfer",lower,upper,"1","EUR",ONE);
        assertEquals("9.00",get(TWO,"/api/v1/accounts/"+lower).path("balance").asText());
        assertEquals("6.00",get(ONE,"/api/v1/accounts/"+upper).path("balance").asText());
        assertCurrencyZeroSum("EUR");
    }

    @Test void caseSensitiveIdempotencyKeysAndPaymentIdsRepresentDistinctCommands() throws Exception {
        String lower="it-id-"+UUID.randomUUID();
        String upper=lower.toUpperCase(Locale.ROOT);
        String lowerKey=key("distinct-key");
        String lowerBody="{\"accountId\":\""+lower+"\",\"currency\":\"JPY\"}";
        String upperBody=lowerBody.replace(lower,upper);
        assertEquals(201,post(ONE,"/api/v1/accounts",lowerKey,lowerBody).statusCode());
        assertEquals(201,post(TWO,"/api/v1/accounts",lowerKey.toUpperCase(Locale.ROOT),upperBody).statusCode());

        String paymentId=key("case-payment");
        String upperPaymentId=paymentId.toUpperCase(Locale.ROOT);
        publish(payment(paymentId,lower,"2","JPY"));
        publish(payment(upperPaymentId,lower,"3","JPY"));
        await(()->"5".equals(get(TWO,"/api/v1/accounts/"+lower).path("balance").asText()));
        assertEquals(1,transactionCountForPayment(paymentId));
        assertEquals(1,transactionCountForPayment(upperPaymentId));
        publish(payment(paymentId,lower,"2","JPY"));
        publish(payment(upperPaymentId,lower,"3","JPY"));
        publish(payment(paymentId,lower,"4","JPY"));
        await(()->quarantineCount(paymentId,"PAYMENT_ID_PAYLOAD_CONFLICT")==1);
        assertEquals("5",get(ONE,"/api/v1/accounts/"+lower).path("balance").asText());
        assertEquals("0",get(TWO,"/api/v1/accounts/"+upper).path("balance").asText());
        assertCurrencyZeroSum("JPY");
    }

    @Test void frameworkErrorsPreserveHttpStatusAndStructuredProblems() throws Exception {
        HttpResponse<String> missing=HTTP.send(HttpRequest.newBuilder(URI.create(ONE+"/api/v1/missing"))
                .timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(404,missing.statusCode(),missing.body());
        assertEquals("HTTP_404",JSON.readTree(missing.body()).path("code").asText());
        HttpResponse<String> method=HTTP.send(HttpRequest.newBuilder(URI.create(TWO+"/api/v1/accounts"))
                .timeout(Duration.ofSeconds(5)).PUT(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405,method.statusCode(),method.body());
        assertEquals("HTTP_405",JSON.readTree(method.body()).path("code").asText());
        assertTrue(method.headers().firstValue("Allow").orElseThrow().contains("POST"));
    }

    @Test void competingOverdraftCommandsSerializeAcrossInstances() throws Exception {
        String account="it-race-"+UUID.randomUUID();
        open(account,"EUR","0");
        createTransfer("fund-"+account, "SETTLEMENT-EUR",account,"10.00","EUR",ONE);
        String a="race-a-"+account;
        String b="race-b-"+account;
        String bodyA="{\"currency\":\"EUR\",\"description\":\"race a\",\"entries\":[{\"accountId\":\""+account+"\",\"direction\":\"DEBIT\",\"amount\":\"8.00\"},{\"accountId\":\"SETTLEMENT-EUR\",\"direction\":\"CREDIT\",\"amount\":\"8.00\"}]}";
        String bodyB=bodyA.replace("race a","race b");
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<HttpResponse<String>> first=pool.submit(()->post(ONE,"/api/v1/transactions",a,bodyA));
            Future<HttpResponse<String>> second=pool.submit(()->post(TWO,"/api/v1/transactions",b,bodyB));
            HttpResponse<String> responseA=first.get(15,TimeUnit.SECONDS), responseB=second.get(15,TimeUnit.SECONDS);
            List<Integer> statuses=List.of(responseA.statusCode(),responseB.statusCode());
            assertEquals(1,statuses.stream().filter(s->s==201).count());
            assertEquals(1,statuses.stream().filter(s->s==422).count());
            String winningKey=responseA.statusCode()==201?a:b, winningBody=responseA.statusCode()==201?bodyA:bodyB;
            HttpResponse<String> replay=post(TWO,"/api/v1/transactions",winningKey,winningBody);
            assertEquals(201,replay.statusCode());
            assertEquals(responseA.statusCode()==201?responseA.body():responseB.body(),replay.body());
            assertEquals(responseA.statusCode()==201?responseA.headers().firstValue("Location"):responseB.headers().firstValue("Location"),
                    replay.headers().firstValue("Location"));
            assertEquals("2.00",get(TWO,"/api/v1/accounts/"+account).get("balance").asText());
            assertCurrencyZeroSum("EUR");
        } finally { pool.shutdownNow(); }
    }

    @Test void oppositeTransfersAcrossInstancesKeepCurrencyBalanced() throws Exception {
        String a="it-op-a-"+UUID.randomUUID(),b="it-op-b-"+UUID.randomUUID();
        open(a,"USD","0");open(b,"USD","0");
        createTransfer("seed-a-"+a,"SETTLEMENT-USD",a,"10.00","USD",ONE);
        createTransfer("seed-b-"+b,"SETTLEMENT-USD",b,"10.00","USD",TWO);
        String bodyA="{\"currency\":\"USD\",\"description\":\"a to b\",\"entries\":[{\"accountId\":\""+a+"\",\"direction\":\"DEBIT\",\"amount\":\"2.00\"},{\"accountId\":\""+b+"\",\"direction\":\"CREDIT\",\"amount\":\"2.00\"}]}";
        String bodyB="{\"currency\":\"USD\",\"description\":\"b to a\",\"entries\":[{\"accountId\":\""+b+"\",\"direction\":\"DEBIT\",\"amount\":\"2.00\"},{\"accountId\":\""+a+"\",\"direction\":\"CREDIT\",\"amount\":\"2.00\"}]}";
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<Integer> x=pool.submit(()->post(ONE,"/api/v1/transactions",key("op-a"),bodyA).statusCode());
            Future<Integer> y=pool.submit(()->post(TWO,"/api/v1/transactions",key("op-b"),bodyB).statusCode());
            assertEquals(201,x.get(15,TimeUnit.SECONDS));assertEquals(201,y.get(15,TimeUnit.SECONDS));
        } finally {pool.shutdownNow();}
        assertEquals("10.00",get(ONE,"/api/v1/accounts/"+a).get("balance").asText());
        assertEquals("10.00",get(TWO,"/api/v1/accounts/"+b).get("balance").asText());
        assertCurrencyZeroSum("USD");
    }

    @Test void finalPerAccountBalanceAggregatesRepeatedPostings() throws Exception {
        String account="it-aggregate-"+UUID.randomUUID();open(account,"EUR","0");
        String body="{\"currency\":\"EUR\",\"description\":\"aggregate final balance\",\"entries\":["
                +"{\"accountId\":\""+account+"\",\"direction\":\"DEBIT\",\"amount\":\"1.00\"},"
                +"{\"accountId\":\""+account+"\",\"direction\":\"CREDIT\",\"amount\":\"2.00\"},"
                +"{\"accountId\":\"SETTLEMENT-EUR\",\"direction\":\"DEBIT\",\"amount\":\"1.00\"}]}";
        HttpResponse<String> result=post(ONE,"/api/v1/transactions",key("aggregate"),body);
        assertEquals(201,result.statusCode(),result.body());
        JsonNode view=JSON.readTree(result.body());
        assertFalse(view.has("sequence"));
        assertFalse(view.get("entries").get(0).has("postEntryBalance"));
        assertEquals(3,view.get("entries").size());
        assertEquals("1.00",get(TWO,"/api/v1/accounts/"+account).get("balance").asText());
        String transactionId=view.get("transactionId").asText();
        assertEquals(view,get(TWO,"/api/v1/transactions/"+transactionId));
    }

    @Test void intermediateBalanceOverflowIsAcceptedWhenFinalBalanceFits() throws Exception {
        String max="999999999999999999.99";
        String source="it-max-src-"+UUID.randomUUID(),account="it-max-"+UUID.randomUUID();
        // Use an isolated counterparty so existing settlement balances cannot affect the boundary.
        open(source,"EUR",max);open(account,"EUR","0");
        createTransfer("max-fund-"+account,source,account,max,"EUR",ONE);
        assertEquals(max,get(ONE,"/api/v1/accounts/"+account).get("balance").asText());
        try(var c=DriverManager.getConnection(mysqlUrl(),"ledger","local-ledger");
            var s=c.prepareStatement("""
                SELECT NUMERIC_PRECISION,NUMERIC_SCALE FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA=DATABASE() AND
                 ((TABLE_NAME='ledger_entry' AND COLUMN_NAME='amount')
                  OR (TABLE_NAME='ledger_account' AND COLUMN_NAME IN ('balance','overdraft_limit')))
                """)) {
            try(var rs=s.executeQuery()) {
                int columns=0;
                while(rs.next()) {assertEquals(20,rs.getInt(1));assertEquals(2,rs.getInt(2));columns++;}
                assertEquals(3,columns);
            }
        }
        String body="{\"currency\":\"EUR\",\"description\":\"intermediate overflow\",\"entries\":["
                +"{\"accountId\":\""+account+"\",\"direction\":\"CREDIT\",\"amount\":\""+max+"\"},"
                +"{\"accountId\":\""+account+"\",\"direction\":\"DEBIT\",\"amount\":\""+max+"\"}]}";
        String requestKey=key("overflow");
        List<String> before=financialState();
        HttpResponse<String> accepted=post(ONE,"/api/v1/transactions",requestKey,body);
        assertEquals(201,accepted.statusCode(),accepted.body());
        JsonNode response=JSON.readTree(accepted.body());
        assertFalse(response.has("sequence"));
        assertFalse(response.get("entries").get(0).has("postEntryBalance"));
        assertEquals(max,get(ONE,"/api/v1/accounts/"+account).get("balance").asText());
        JsonNode read=get(TWO,"/api/v1/transactions/"+response.path("transactionId").asText());
        assertEquals(response,read);
        HttpResponse<String> replay=post(TWO,"/api/v1/transactions",requestKey,body);
        assertEquals(accepted.statusCode(),replay.statusCode());
        assertEquals(accepted.body(),replay.body());
        List<String> after=financialState();
        assertEquals(Long.parseLong(before.get(0))+1,Long.parseLong(after.get(0)));
        assertEquals(Long.parseLong(before.get(1))+2,Long.parseLong(after.get(1)));
        assertEquals(Long.parseLong(before.get(2))+1,Long.parseLong(after.get(2)));
        assertEquals(before.get(3),after.get(3));
    }

    @Test void reversalKeyCannotBeReusedForDifferentTargetAfterSuccessOrPersistedRefusal() throws Exception {
        String account="it-rev-key-"+UUID.randomUUID();open(account,"EUR","0");
        String first=createTransfer("rev-key-first-"+account,"SETTLEMENT-EUR",account,"5.00","EUR",ONE);
        String second=createTransfer("rev-key-second-"+account,"SETTLEMENT-EUR",account,"5.00","EUR",TWO);
        for(boolean successFirst:List.of(true,false)) {
            String requestKey=key("rev-target");
            String target=successFirst?first:UUID.randomUUID().toString();
            HttpResponse<String> original=post(ONE,"/api/v1/transactions/"+target+"/reversals",requestKey,"");
            assertEquals(successFirst?201:404,original.statusCode(),original.body());
            List<String> before=financialState();
            HttpResponse<String> conflict=post(TWO,"/api/v1/transactions/"+second+"/reversals",requestKey,"");
            assertEquals(409,conflict.statusCode(),conflict.body());
            assertEquals("IDEMPOTENCY_KEY_REUSED",JSON.readTree(conflict.body()).path("code").asText());
            assertEquals(before,financialState(),"Changed target must not create another reversal or advance the gate");
            HttpResponse<String> replay=post(TWO,"/api/v1/transactions/"+target+"/reversals",requestKey,"");
            assertEquals(original.statusCode(),replay.statusCode());
            assertEquals(original.body(),replay.body());
            assertEquals(original.headers().firstValue("Location"),replay.headers().firstValue("Location"));
            assertEquals(before,financialState());
        }
        try(var c=DriverManager.getConnection(mysqlUrl(),"ledger","local-ledger");
            var s=c.prepareStatement("SELECT COUNT(*) FROM ledger_transaction WHERE reversal_of=?")) {
            s.setString(1,second);
            try(var rs=s.executeQuery()) {assertTrue(rs.next());assertEquals(0,rs.getLong(1));}
        }
    }

    @Test void reversalsRaceOnceAndOriginalRemainsImmutable() throws Exception {
        String a="it-rev-a-"+UUID.randomUUID(),b="it-rev-b-"+UUID.randomUUID();
        open(a,"USD","0"); open(b,"USD","0");
        createTransfer("fund-"+a,"SETTLEMENT-USD",a,"20.00","USD",ONE);
        HttpResponse<String> original=post(ONE,"/api/v1/transactions",key("original"),
                "{\"currency\":\"USD\",\"description\":\"move\",\"entries\":[{\"accountId\":\""+a+"\",\"direction\":\"DEBIT\",\"amount\":\"3.00\"},{\"accountId\":\""+b+"\",\"direction\":\"CREDIT\",\"amount\":\"3.00\"}]}");
        assertEquals(201,original.statusCode());
        String id=JSON.readTree(original.body()).get("transactionId").asText();
        ExecutorService pool=Executors.newFixedThreadPool(2);
        HttpResponse<String> result1,result2;
        try {
            String reverseKey1=key("rev1"), reverseKey2=key("rev2");
            Future<HttpResponse<String>> r1=pool.submit(()->post(ONE,"/api/v1/transactions/"+id+"/reversals",reverseKey1,""));
            Future<HttpResponse<String>> r2=pool.submit(()->post(TWO,"/api/v1/transactions/"+id+"/reversals",reverseKey2,""));
            result1=r1.get(15,TimeUnit.SECONDS);result2=r2.get(15,TimeUnit.SECONDS);
            List<Integer> statuses=List.of(result1.statusCode(),result2.statusCode());
            assertEquals(1,statuses.stream().filter(s->s==201).count());
            assertEquals(1,statuses.stream().filter(s->s==409).count());
            String winner=result1.statusCode()==201?reverseKey1:reverseKey2;
            String loser=result1.statusCode()==409?reverseKey1:reverseKey2;
            HttpResponse<String> replay=post(TWO,"/api/v1/transactions/"+id+"/reversals",winner,"");
            assertEquals(201,replay.statusCode());
            HttpResponse<String> successful=result1.statusCode()==201?result1:result2;
            assertEquals(successful.body(),replay.body());
            assertEquals(successful.headers().firstValue("Location"),replay.headers().firstValue("Location"));
            HttpResponse<String> refused=result1.statusCode()==409?result1:result2;
            HttpResponse<String> refusalReplay=post(ONE,"/api/v1/transactions/"+id+"/reversals",loser,"");
            assertEquals(409,refusalReplay.statusCode());
            assertEquals(refused.body(),refusalReplay.body());
        } finally { pool.shutdownNow(); }
        JsonNode read= get(TWO,"/api/v1/transactions/"+id);
        assertEquals(JSON.readTree(original.body()),read);
        assertEquals("move",read.get("description").asText());
        assertEquals("20.00",get(TWO,"/api/v1/accounts/"+a).get("balance").asText());
        assertEquals("0.00",get(TWO,"/api/v1/accounts/"+b).get("balance").asText());
        for(String sql:List.of(
                "UPDATE ledger_transaction SET description='tampered' WHERE transaction_id=?",
                "DELETE FROM ledger_transaction WHERE transaction_id=?",
                "UPDATE ledger_entry SET amount=amount+1 WHERE transaction_id=?",
                "DELETE FROM ledger_entry WHERE transaction_id=?")) {
            try(var c=DriverManager.getConnection(mysqlUrl(),"ledger","local-ledger");
                var s=c.prepareStatement(sql)) {
                s.setString(1,id);
                var failure=assertThrows(java.sql.SQLException.class,s::executeUpdate,sql);
                assertEquals("45000",failure.getSQLState(),"The immutability trigger must reject the write");
            }
        }
        assertEquals(read,get(ONE,"/api/v1/transactions/"+id));
        String reversalId=JSON.readTree(result1.statusCode()==201?result1.body():result2.body()).get("transactionId").asText();
        String reverseAgainKey=key("reverse-reversal");
        HttpResponse<String> reverseAgain=post(ONE,"/api/v1/transactions/"+reversalId+"/reversals",reverseAgainKey,"");
        assertEquals(422,reverseAgain.statusCode());
        assertEquals(reverseAgain.body(),post(TWO,"/api/v1/transactions/"+reversalId+"/reversals",reverseAgainKey,"").body());
    }

    @Test void reversalThatWouldOverdrawIsRefusedAndReplayed() throws Exception {
        String account="it-rev-overdraft-"+UUID.randomUUID();open(account,"EUR","0");
        HttpResponse<String> funding=post(ONE,"/api/v1/transactions",key("rev-fund"),
                "{\"currency\":\"EUR\",\"description\":\"temporary funds\",\"entries\":[{\"accountId\":\"SETTLEMENT-EUR\",\"direction\":\"DEBIT\",\"amount\":\"5.00\"},{\"accountId\":\""+account+"\",\"direction\":\"CREDIT\",\"amount\":\"5.00\"}]}");
        assertEquals(201,funding.statusCode());
        String original=JSON.readTree(funding.body()).get("transactionId").asText();
        createTransfer("spend-"+account,account,"SETTLEMENT-EUR","5.00","EUR",TWO);
        String reverseKey=key("overdraft-reversal");
        HttpResponse<String> refused=post(ONE,"/api/v1/transactions/"+original+"/reversals",reverseKey,"");
        assertEquals(422,refused.statusCode());
        HttpResponse<String> replay=post(TWO,"/api/v1/transactions/"+original+"/reversals",reverseKey,"");
        assertEquals(refused.body(),replay.body());
        assertEquals("0.00",get(TWO,"/api/v1/accounts/"+account).get("balance").asText());
    }

    @Test void paymentIdsDeduplicateAndPoisonIsQuarantinedBeforeValidDelivery() throws Exception {
        String account="it-payment-"+UUID.randomUUID(); open(account,"JPY","0");
        List<String> before=financialState();
        for(Object bad:Arrays.asList(1,null,true,"1e2","-1","+1","01"," 1","1.","0","1.0","999999999999999999999")) {
            String id=key("bad-amount");
            publish(payment(id,account,bad,"JPY"));
            await(()->quarantineCount(id,null)==1);
        }
        String wrongCurrency=key("wrong-currency"),missing=key("missing"),reserved=key("reserved");
        publish(payment(wrongCurrency,account,"1.00","EUR"));
        publish(payment(missing,"missing-"+UUID.randomUUID(),"1","JPY"));
        publish(payment(reserved,"SETTLEMENT-JPY","1","JPY"));
        await(()->quarantineCount(wrongCurrency,"CURRENCY_MISMATCH")==1);
        await(()->quarantineCount(missing,"ACCOUNT_NOT_FOUND")==1);
        await(()->quarantineCount(reserved,"NOT_MERCHANT_ACCOUNT")==1);
        assertEquals(before,financialState(),"Invalid payments must not write ledger, receipts or outbox");
        String paymentId="pay-"+UUID.randomUUID();
        String payment=payment(paymentId,account,"7","JPY");
        publish(payment);
        await(()->"7".equals(get(TWO,"/api/v1/accounts/"+account).path("balance").asText()));
        publish(payment(paymentId,account,"7","JPY")); // A different event ID, same economics.
        publish(payment(paymentId,account,"8","JPY"));
        await(()->quarantineCount(paymentId,"PAYMENT_ID_PAYLOAD_CONFLICT")>0);
        assertEquals("7",get(TWO,"/api/v1/accounts/"+account).get("balance").asText());
        String paymentTransactionId=transactionIdForPayment(paymentId);
        assertEquals(1,transactionCountForPayment(paymentId));
        JsonNode tx=get(TWO,"/api/v1/transactions/"+paymentTransactionId);
        assertEquals("Payment settled",tx.path("description").asText());
        assertEquals(2,tx.path("entries").size());
        assertEquals("SETTLEMENT-JPY",tx.path("entries").get(0).path("accountId").asText());
        assertEquals("DEBIT",tx.path("entries").get(0).path("direction").asText());
        assertEquals(account,tx.path("entries").get(1).path("accountId").asText());
        assertEquals("CREDIT",tx.path("entries").get(1).path("direction").asText());
        for(JsonNode entry:tx.path("entries")) assertEquals("7",entry.path("amount").asText());
        assertCurrencyZeroSum("JPY");
        JsonNode settlement=get(TWO,"/api/v1/accounts/SETTLEMENT-JPY");
        assertTrue(settlement.has("overdraftLimit"));
        assertTrue(settlement.get("overdraftLimit").isNull(),"Unlimited must be explicit, not a configurable economic cap");
    }

    @Test void paymentIdentityNormalizesAmountAndExcludesDeliveryMetadata() throws Exception {
        String account="it-payment-eur-"+UUID.randomUUID();open(account,"EUR","0");
        String id=key("normalized");
        String originalDescription="Original-"+id;
        var first=JSON.readTree(payment(id,account,"7","EUR"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)first).put("description",originalDescription).put("requestId","first-request");
        publish(JSON.writeValueAsString(first));
        await(()->"7.00".equals(get(TWO,"/api/v1/accounts/"+account).path("balance").asText()));
        var duplicate=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(payment(id,account,"7.00","EUR"));
        duplicate.put("requestId","different-request").put("description","Different description");
        publish(JSON.writeValueAsString(duplicate));
        publish(payment(id,account,"8.00","EUR"));
        await(()->quarantineCount(id,"PAYMENT_ID_PAYLOAD_CONFLICT")==1);
        assertEquals("7.00",get(TWO,"/api/v1/accounts/"+account).path("balance").asText());
        assertEquals(1,transactionCountForPayment(id));
        // A metadata-only duplicate must not itself be quarantined.
        assertEquals(1,quarantineCount(id,null));
        assertEquals(1,transactionCount(originalDescription));
        try(var c=DriverManager.getConnection(mysqlUrl(),"ledger","local-ledger");
            var s=c.prepareStatement("SELECT COUNT(*) FROM payment_receipt WHERE payment_id=?")) {
            s.setString(1,id);
            try(var rs=s.executeQuery()) {assertTrue(rs.next());assertEquals(1,rs.getInt(1));}
        }
    }

    @Test void announcementsCarryStableTransactionIdentifiers() throws Exception {
        String account="it-outbox-"+UUID.randomUUID();open(account,"USD","0");
        HttpResponse<String> created=post(ONE,"/api/v1/transactions",key("outbox"),
                "{\"currency\":\"USD\",\"description\":\"announce\",\"entries\":[{\"accountId\":\"SETTLEMENT-USD\",\"direction\":\"DEBIT\",\"amount\":\"1.00\"},{\"accountId\":\""+account+"\",\"direction\":\"CREDIT\",\"amount\":\"1.00\"}]}");
        assertEquals(201,created.statusCode());
        String id=JSON.readTree(created.body()).get("transactionId").asText();
        JsonNode response=JSON.readTree(created.body());
        assertEquals(response,get(TWO,"/api/v1/transactions/"+id));
        assertFalse(response.has("sequence"));
        assertFalse(response.path("entries").get(0).has("postEntryBalance"));
        try(var c=DriverManager.getConnection(mysqlUrl(),"ledger","local-ledger");
            var s=c.prepareStatement("SELECT payload FROM transaction_outbox WHERE event_id=?")) {
            s.setString(1,id);
            try(var rs=s.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(response,JSON.readTree(rs.getString(1)),
                        "Outbox and HTTP/read responses must retain the same persisted recordedAt");
            }
        }
        java.util.concurrent.atomic.AtomicBoolean observed=new java.util.concurrent.atomic.AtomicBoolean();
        await(()->{if(announcementFor(id))observed.set(true);return observed.get();});
        assertTrue(observed.get());
    }

    @Test void confirmedButUnmarkedOutboxRowIsRepublishedWithSameEventId() throws Exception {
        String account="it-outbox-retry-"+UUID.randomUUID();open(account,"USD","0");
        HttpResponse<String> created=post(ONE,"/api/v1/transactions",key("outbox-retry"),
                "{\"currency\":\"USD\",\"description\":\"confirm window\",\"entries\":[{\"accountId\":\"SETTLEMENT-USD\",\"direction\":\"DEBIT\",\"amount\":\"1.00\"},{\"accountId\":\""+account+"\",\"direction\":\"CREDIT\",\"amount\":\"1.00\"}]}");
        assertEquals(201,created.statusCode(),created.body());
        String id=JSON.readTree(created.body()).get("transactionId").asText();
        await(()->outboxAttempts(id)>=1);
        int previousAttempts=outboxAttempts(id);
        // Test-only fault injection: simulate broker confirmation followed by loss of the outbox sent mark.
        try(var c=DriverManager.getConnection(mysqlUrl(),"ledger","local-ledger");
            var s=c.prepareStatement("UPDATE transaction_outbox SET published_at=NULL WHERE event_id=?")) {
            s.setString(1,id);assertEquals(1,s.executeUpdate());
        }
        await(()->outboxAttempts(id)>=previousAttempts+1);
        assertTrue(countAnnouncements(id)>=2,"The retry should be observable as a duplicate with the same stable ID");
    }

    private boolean announcementFor(String txId) throws Exception {
        return countAnnouncements(txId)>0;
    }
    private int countAnnouncements(String txId) throws Exception {
        int count=0;
        ConnectionFactory f=new ConnectionFactory();f.setHost(System.getenv().getOrDefault("SPRING_RABBITMQ_HOST","localhost"));
        f.setUsername(System.getenv().getOrDefault("SPRING_RABBITMQ_USERNAME","ledger"));
        f.setPassword(System.getenv().getOrDefault("SPRING_RABBITMQ_PASSWORD","local-ledger"));
        try(var c=f.newConnection();var ch=c.createChannel()) {
            var response=ch.basicGet("ledger.transactions.announcements",true);
            while(response!=null) {
                if(txId.equals(response.getProps().getMessageId()))count++;
                response=ch.basicGet("ledger.transactions.announcements",true);
            }
        }
        return count;
    }
    private int outboxAttempts(String eventId) throws java.sql.SQLException {
        try(var c=DriverManager.getConnection(mysqlUrl(),"ledger","local-ledger");
            var s=c.prepareStatement("SELECT attempts FROM transaction_outbox WHERE event_id=?")) {
            s.setString(1,eventId);
            try(var rs=s.executeQuery()){return rs.next()?rs.getInt(1):0;}
        }
    }
    private long quarantineCount(String paymentId,String reason) throws java.sql.SQLException {
        try(var c=DriverManager.getConnection(mysqlUrl(),"ledger","local-ledger");
            var s=c.prepareStatement("SELECT COUNT(*) FROM message_quarantine WHERE payment_id=?"+(reason==null?"":" AND reason_code=?"))) {
            s.setString(1,paymentId);if(reason!=null)s.setString(2,reason);
            try(var rs=s.executeQuery()){return rs.next()?rs.getLong(1):0;}
        }
    }
    private String payment(String id,String merchant,Object amount,String currency) throws Exception {
        Map<String,Object> fields=new LinkedHashMap<>();
        fields.put("eventId",key("event"));fields.put("paymentId",id);
        fields.put("merchantAccountId",merchant);fields.put("amount",amount);fields.put("currency",currency);
        return JSON.writeValueAsString(fields);
    }
    private void publish(String json) throws Exception {
        ConnectionFactory f=new ConnectionFactory();f.setHost(System.getenv().getOrDefault("SPRING_RABBITMQ_HOST","localhost"));
        f.setUsername(System.getenv().getOrDefault("SPRING_RABBITMQ_USERNAME","ledger"));
        f.setPassword(System.getenv().getOrDefault("SPRING_RABBITMQ_PASSWORD","local-ledger"));
        try(var c=f.newConnection();var ch=c.createChannel()) {
            var properties=new com.rabbitmq.client.AMQP.BasicProperties.Builder()
                    .contentType("application/json").deliveryMode(2).priority(0).build();
            ch.basicPublish("","ledger.payments",properties,json.getBytes(StandardCharsets.UTF_8));
        }
    }
    private String mysqlUrl(){return System.getenv().getOrDefault("SPRING_DATASOURCE_URL","jdbc:mysql://localhost:3306/ledger?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");}
    private void assertCurrencyZeroSum(String currency)throws Exception {
        try(var c=DriverManager.getConnection(mysqlUrl(),"ledger","local-ledger");
            var s=c.prepareStatement("SELECT SUM(balance) FROM ledger_account WHERE currency=?")) {
            s.setString(1,currency);
            try(var rs=s.executeQuery()){assertTrue(rs.next());assertEquals(0,rs.getBigDecimal(1).signum());}
        }
    }
    private long transactionCount(String description)throws Exception {
        try(var c=DriverManager.getConnection(mysqlUrl(),"ledger","local-ledger");
            var s=c.prepareStatement("SELECT COUNT(*) FROM ledger_transaction WHERE description=?")) {
            s.setString(1,description);
            try(var rs=s.executeQuery()) {assertTrue(rs.next());return rs.getLong(1);}
        }
    }
    private long transactionCountForPayment(String paymentId)throws Exception {
        try(var c=DriverManager.getConnection(mysqlUrl(),"ledger","local-ledger");
            var s=c.prepareStatement("SELECT COUNT(*) FROM ledger_transaction WHERE payment_id=?")) {
            s.setString(1,paymentId);
            try(var rs=s.executeQuery()) {assertTrue(rs.next());return rs.getLong(1);}
        }
    }
    private String transactionIdForPayment(String paymentId)throws Exception {
        try(var c=DriverManager.getConnection(mysqlUrl(),"ledger","local-ledger");
            var s=c.prepareStatement("SELECT transaction_id FROM payment_receipt WHERE payment_id=?")) {
            s.setString(1,paymentId);
            try(var rs=s.executeQuery()) {assertTrue(rs.next());return rs.getString(1);}
        }
    }
    private List<String> financialState()throws Exception {
        List<String> state=new ArrayList<>();
        try(var c=DriverManager.getConnection(mysqlUrl(),"ledger","local-ledger");
            var s=c.createStatement()) {
            // Ignore mutable outbox delivery fields; compare committed financial-state counts and balances.
            try(var rs=s.executeQuery("""
                SELECT (SELECT COUNT(*) FROM ledger_transaction),(SELECT COUNT(*) FROM ledger_entry),
                       (SELECT COUNT(*) FROM transaction_outbox),
                       (SELECT COUNT(*) FROM payment_receipt)
                """)) {
                assertTrue(rs.next());
                for(int i=1;i<=4;i++)state.add(rs.getString(i));
            }
            try(var rs=s.executeQuery("SELECT account_id,balance FROM ledger_account ORDER BY account_id")) {
                while(rs.next())state.add(rs.getString(1)+"="+rs.getBigDecimal(2).toPlainString());
            }
        }
        return state;
    }
    private void open(String id,String currency,String overdraft)throws Exception {
        HttpResponse<String> r=post(ONE,"/api/v1/accounts",key("open"),"{\"accountId\":\""+id+"\",\"currency\":\""+currency+"\",\"overdraftLimit\":\""+overdraft+"\"}");
        assertEquals(201,r.statusCode(),r.body());
    }
    private String createTransfer(String description,String debit,String credit,String amount,String currency,String host)throws Exception {
        String dr=debit;
        String cr=credit;
        String body="{\"currency\":\""+currency+"\",\"description\":\""+description+"\",\"entries\":[{\"accountId\":\""+dr+"\",\"direction\":\"DEBIT\",\"amount\":\""+amount+"\"},{\"accountId\":\""+cr+"\",\"direction\":\"CREDIT\",\"amount\":\""+amount+"\"}]}";
        HttpResponse<String> r=post(host,"/api/v1/transactions",key("fund"),body);
        assertEquals(201,r.statusCode(),r.body());
        return JSON.readTree(r.body()).path("transactionId").asText();
    }
    private HttpResponse<String> post(String host,String path,String key,String body)throws Exception {
        HttpRequest.Builder b=HttpRequest.newBuilder(URI.create(host+path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type","application/json").header("Idempotency-Key",key)
                .header("X-Correlation-Id","it-"+UUID.randomUUID());
        if(!key.isEmpty()) b.POST(HttpRequest.BodyPublishers.ofString(body)); else b.POST(HttpRequest.BodyPublishers.noBody());
        return HTTP.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode get(String host,String path)throws Exception {
        HttpResponse<String> r=HTTP.send(HttpRequest.newBuilder(URI.create(host+path)).timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,r.statusCode(),r.body());return JSON.readTree(r.body());
    }
    private String key(String p){return p+"-"+UUID.randomUUID();}
    private void await(Callable<Boolean> predicate)throws Exception {
        long end=System.nanoTime()+Duration.ofSeconds(15).toNanos();
        while(System.nanoTime()<end){if(predicate.call())return;Thread.sleep(150);}
        fail("Condition did not become true before timeout");
    }
}
