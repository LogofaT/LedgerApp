package ledger.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import ledger.api.Models.CommandResult;
import ledger.api.Models.PaymentMessage;
import ledger.api.Models.TransactionView;
import ledger.messaging.PaymentQuarantine;
import ledger.persistence.LedgerRepository;
import ledger.persistence.OutboxRepository;
import ledger.service.LedgerService;
import org.jooq.ExecuteContext;
import org.jooq.impl.DefaultExecuteListener;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Repository/transaction integration checks run only by the isolated Docker
 * verification profile. Independent JDBC queries intentionally verify what
 * remains committed after Spring's transaction manager rolls the work back.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"ledger.outbox.poll-ms=600000", "spring.rabbitmq.listener.simple.auto-startup=false"})
class LedgerPersistenceIT {
    @Autowired private LedgerService ledger;
    @Autowired private LedgerRepository repository;
    @Autowired private OutboxRepository outbox;
    @Autowired private PaymentQuarantine quarantine;
    @Autowired private DSLContext dsl;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    private final StatementRecorder statements = new StatementRecorder();

    @BeforeEach
    void recordJooqStatements() {
        dsl.configuration().set(new DefaultExecuteListenerProvider(statements));
        statements.clear();
    }

    @Test
    void generatedMappingsAndSpringRollbackPreserveAllLedgerStateAndGateIsFirstSql() {
        String suffix=UUID.randomUUID().toString();
        String accountId="jooq-"+suffix.substring(0,12);
        String description="jooq mapping "+suffix;
        String accountKey="jooq-account-"+suffix;
        String transactionKey="jooq-tx-"+suffix;
        String paymentAccount="jooq-pay-"+suffix.substring(0,8);
        String paymentAccountKey="jooq-pay-account-"+suffix;
        String paymentId="jooq-payment-"+suffix;
        String quarantineEvent="jooq-quarantine-"+suffix;
        List<String> createdEvents=new ArrayList<>();
        int txBefore=jdbc.queryForObject("SELECT COUNT(*) FROM ledger_transaction",Integer.class);

        assertThrows(RollbackProbe.class,()->new TransactionTemplate(transactionManager).execute(status->{
            CommandResult<?> opened=ledger.openAccount(accountKey,
                    "{\"accountId\":\""+accountId+"\",\"currency\":\"EUR\",\"overdraftLimit\":\"25.00\"}",
                    null);
            assertEquals(201,opened.status());
            assertFirstStatementLocksGate();

            statements.clear();
            CommandResult<?> paymentAccountOpened=ledger.openAccount(paymentAccountKey,
                    "{\"accountId\":\""+paymentAccount+"\",\"currency\":\"JPY\",\"overdraftLimit\":\"0\"}",
                    null);
            assertEquals(201,paymentAccountOpened.status());
            assertFirstStatementLocksGate();
            statements.clear();
            ledger.settlePayment(new PaymentMessage("event-"+suffix,paymentId,paymentAccount,"5","JPY",null,null));
            assertFirstStatementLocksGate();
            assertTrue(repository.findPaymentReceipt(paymentId).isPresent());
            String paymentTransactionId=repository.findPaymentReceipt(paymentId).orElseThrow().transactionId();
            createdEvents.add(paymentTransactionId);

            statements.clear();
            quarantine.store(quarantineEvent,paymentId,"TEST_QUARANTINE","{}");
            assertFirstStatementLocksGate();

            statements.clear();
            CommandResult<?> posted=ledger.createTransaction(transactionKey,
                    "{\"currency\":\"EUR\",\"description\":\""+description+"\",\"entries\":["
                            +"{\"accountId\":\"SETTLEMENT-EUR\",\"direction\":\"DEBIT\",\"amount\":\"7.12\"},"
                            +"{\"accountId\":\""+accountId+"\",\"direction\":\"CREDIT\",\"amount\":\"7.12\"}]}",
                    null);
            assertEquals(201,posted.status());
            assertFirstStatementLocksGate();

            LedgerRepository.AccountData account=repository.findAccount(accountId).orElseThrow();
            assertEquals(new BigDecimal("7.12"),account.balance());
            assertEquals(new BigDecimal("25.00"),account.overdraftLimit());
            LedgerRepository.AccountData settlement=repository.findAccount("SETTLEMENT-EUR").orElseThrow();
            assertNull(settlement.overdraftLimit(),"SQL NULL must remain Java null, not a synthetic zero/cap");

            TransactionView view=(TransactionView)posted.body();
            createdEvents.add(view.transactionId());
            LedgerRepository.TransactionData header=repository.findTransaction(view.transactionId()).orElseThrow();
            assertNull(header.requestId());
            assertNull(header.paymentId());
            List<LedgerRepository.EntryData> entries=repository.findTransactionEntries(view.transactionId());
            assertEquals(List.of("DEBIT","CREDIT"),entries.stream().map(LedgerRepository.EntryData::direction).toList());
            assertEquals(new BigDecimal("7.12"),entries.get(0).amount());
            assertEquals(new BigDecimal("7.12"),entries.get(1).amount());

            var pending=outbox.findPending(100).stream()
                    .filter(event->event.eventId().equals(view.transactionId())).findFirst().orElseThrow();
            com.fasterxml.jackson.databind.JsonNode json;
            try {
                json=new ObjectMapper().readTree(pending.payload());
            } catch (Exception invalidJson) {
                throw new AssertionError("Outbox payload is not readable JSON",invalidJson);
            }
            assertEquals("7.12",json.path("entries").get(0).path("amount").asText(),
                    "MySQL JSON must map back to the original response payload");
            assertEquals(view.recordedAt(),header.recordedAt().toString());
            assertEquals(view.recordedAt(),json.path("recordedAt").asText());

            throw new RollbackProbe();
        }));

        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ledger_account WHERE account_id=?",Integer.class,accountId));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ledger_account WHERE account_id=?",Integer.class,paymentAccount));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ledger_transaction WHERE description=?",Integer.class,description));
        assertEquals(txBefore,jdbc.queryForObject("SELECT COUNT(*) FROM ledger_transaction",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_receipt WHERE idempotency_key IN (?,?,?)",
                Integer.class,accountKey,transactionKey,paymentAccountKey));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM payment_receipt WHERE payment_id=?",
                Integer.class,paymentId));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM message_quarantine WHERE event_id=?",
                Integer.class,quarantineEvent));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM transaction_outbox WHERE event_id IN (?,?)",
                Integer.class,createdEvents.get(0),createdEvents.get(1)));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE transaction_id IN (?,?)",
                Integer.class,createdEvents.get(0),createdEvents.get(1)));
    }

    private void assertFirstStatementLocksGate() {
        List<String> sql=statements.snapshot();
        assertFalse(sql.isEmpty(),"A jOOQ statement should have executed");
        String first=sql.get(0).toLowerCase();
        assertTrue(first.contains("ledger_gate")&&first.contains("for update"),
                "The first SQL statement for every mutation must lock ledger_gate: "+sql);
    }

    private static final class StatementRecorder extends DefaultExecuteListener {
        private final ThreadLocal<List<String>> sql=ThreadLocal.withInitial(ArrayList::new);

        @Override
        public void executeStart(ExecuteContext context) {
            if(context.sql()!=null) sql.get().add(context.sql());
        }

        private void clear() {
            sql.get().clear();
        }

        private List<String> snapshot() {
            return List.copyOf(sql.get());
        }
    }

    private static final class RollbackProbe extends RuntimeException {}
}
