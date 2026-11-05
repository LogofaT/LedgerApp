package ledger.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import ledger.api.Models.PaymentMessage;
import ledger.persistence.LedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaymentServiceTest {
    private LedgerRepository repository;
    private LedgerService service;
    private final AtomicReference<LedgerRepository.PaymentReceiptData> receipt=new AtomicReference<>();
    private final List<LedgerRepository.TransactionData> transactions=new ArrayList<>();
    private final List<LedgerRepository.PostingData> postings=new ArrayList<>();
    private int writes;

    @BeforeEach void setup() {
        repository=mock(LedgerRepository.class);
        service=new LedgerService(repository,new ObjectMapper(),100);
        when(repository.findAccount("merchant")).thenReturn(Optional.of(account("merchant","JPY",false,BigDecimal.ZERO)));
        when(repository.findAccount("SETTLEMENT-JPY")).thenReturn(Optional.of(account("SETTLEMENT-JPY","JPY",true,BigDecimal.ZERO)));
        when(repository.findPaymentReceipt("payment")).thenAnswer(i->Optional.ofNullable(receipt.get()));
        doAnswer(i->{transactions.add(i.getArgument(0)); writes++; return null;}).when(repository).insertTransaction(any());
        doAnswer(i->{postings.add(i.getArgument(2)); writes++; return null;}).when(repository)
                .insertPosting(anyString(),anyInt(),any());
        doAnswer(i->{receipt.set(new LedgerRepository.PaymentReceiptData(i.getArgument(1),
                i.getArgument(2),"SETTLED")); writes++; return null;}).when(repository)
                .insertPaymentReceipt(anyString(),anyString(),anyString());
        doAnswer(i->{writes++; return null;}).when(repository).insertOutbox(anyString(),anyString());
        doAnswer(i->{writes++; return null;}).when(repository).updateAccountBalance(anyString(),any());
    }

    @Test void constructsExactlyTwoPostingsAndReplaysAcrossEventsAndMetadata() {
        var result=service.settlePayment(payment("7",null));
        assertFalse(result.duplicate());
        assertEquals(2,postings.size());
        assertEquals(new LedgerRepository.PostingData("SETTLEMENT-JPY","DEBIT",new BigDecimal("7")),postings.get(0));
        assertEquals(new LedgerRepository.PostingData("merchant","CREDIT",new BigDecimal("7")),postings.get(1));
        assertEquals("Payment settled",transactions.get(0).description());
        int previousWrites=writes;
        var duplicate=service.settlePayment(new PaymentMessage("another-event","payment","merchant","7","JPY","another-request","Changed description"));
        assertTrue(duplicate.duplicate());
        assertEquals(result.transactionId(),duplicate.transactionId());
        assertEquals(previousWrites,writes,"Duplicate must not write ledger, receipt or outbox");
    }

    @Test void normalizedDecimalIdentityAndChangedEconomicFields() {
        when(repository.findAccount("merchant")).thenReturn(Optional.of(account("merchant","EUR",false,BigDecimal.ZERO)));
        when(repository.findAccount("SETTLEMENT-EUR")).thenReturn(Optional.of(account("SETTLEMENT-EUR","EUR",true,BigDecimal.ZERO)));
        when(repository.findPaymentReceipt("payment")).thenAnswer(i->Optional.ofNullable(receipt.get()));
        var original=service.settlePayment(new PaymentMessage("event","payment","merchant","7","EUR",null,"Original"));
        assertTrue(service.settlePayment(new PaymentMessage("new-event","payment","merchant","7.00","EUR",null,null)).duplicate());
        int previousWrites=writes;
        for(PaymentMessage changed:List.of(
                new PaymentMessage("event","payment","merchant","8","EUR",null,null),
                new PaymentMessage("event","payment","other","7","EUR",null,null),
                new PaymentMessage("event","payment","merchant","7","USD",null,null))) {
            assertEquals("PAYMENT_ID_PAYLOAD_CONFLICT",assertThrows(LedgerFailure.class,()->service.settlePayment(changed)).code());
            assertEquals(previousWrites,writes);
        }
        assertNotNull(original.transactionId());
    }

    @Test void rejectsMissingWrongCurrencyAndSettlementMerchantsBeforeWrites() {
        assertRefused(new PaymentMessage("event","payment","missing","7","JPY",null,null),"ACCOUNT_NOT_FOUND");
        assertRefused(new PaymentMessage("event","payment","merchant","7","EUR",null,null),"CURRENCY_MISMATCH");
        when(repository.findAccount("SETTLEMENT-JPY"))
                .thenReturn(Optional.of(account("SETTLEMENT-JPY","JPY",true,BigDecimal.ZERO)));
        assertRefused(new PaymentMessage("event","payment","SETTLEMENT-JPY","7","JPY",null,null),"NOT_MERCHANT_ACCOUNT");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings={"1e2","-1","+1","01"," 1","1 ","1.",".1","0","1.0","999999999999999999999"})
    void rejectsMalformedNonpositiveOverscaleOrOutOfRangeAmounts(String amount) {
        assertThrows(LedgerFailure.class,()->service.settlePayment(payment(amount,null)));
        assertEquals(0,writes);
    }

    @Test void validatesOptionalDescriptionEvenForDuplicates() {
        service.settlePayment(payment("7",null));
        int previousWrites=writes;
        for(String invalid:List.of("","   ","x".repeat(241)))
            assertEquals("INVALID_FIELD",assertThrows(LedgerFailure.class,()->service.settlePayment(payment("7",invalid))).code());
        assertEquals(previousWrites,writes);
        assertTrue(service.settlePayment(payment("7","x".repeat(240))).duplicate());
    }

    @Test void validatesMerchantAndOptionalRequestMetadataBeforeDuplicateLookup() {
        service.settlePayment(payment("7", null));
        clearInvocations(repository);
        for (PaymentMessage invalid : List.of(
                new PaymentMessage("event", "payment", "", "7", "JPY", null, null),
                new PaymentMessage("event", "payment", "merchant", "7", "JPY", "", null),
                new PaymentMessage("event", "payment", "merchant", "7", "JPY", "bad/id", null),
                new PaymentMessage("", "payment", "merchant", "7", "JPY", null, null))) {
            assertThrows(LedgerFailure.class, () -> service.settlePayment(invalid));
        }
        verify(repository, never()).findPaymentReceipt(anyString());
        verify(repository, never()).insertTransaction(any());
        verify(repository, never()).insertPaymentReceipt(anyString(), anyString(), anyString());
    }

    @Test void unlimitedSettlementStillRejectsStorageOverflowBeforeWrites() {
        when(repository.findAccount("SETTLEMENT-JPY")).thenReturn(Optional.of(
                account("SETTLEMENT-JPY","JPY",true,new BigDecimal("-999999999999999999"))));
        assertRefused(payment("7",null),"BALANCE_OUT_OF_RANGE");
    }

    @Test void unlimitedAccountResponseSerializesExplicitNullEvenWhenNullsAreNormallyOmitted() throws Exception {
        var mapper=com.fasterxml.jackson.databind.json.JsonMapper.builder().defaultPropertyInclusion(
                com.fasterxml.jackson.annotation.JsonInclude.Value.construct(
                        com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL,
                        com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)).build();
        var view=new ledger.api.Models.AccountView("SETTLEMENT-JPY","JPY","-7",null,true);
        var tree=mapper.readTree(mapper.writeValueAsString(view));
        assertTrue(tree.has("overdraftLimit"));
        assertTrue(tree.get("overdraftLimit").isNull());
    }

    private PaymentMessage payment(String amount,String description) {
        return new PaymentMessage("event","payment","merchant",amount,"JPY",null,description);
    }
    private LedgerRepository.AccountData account(String id,String currency,boolean settlement,BigDecimal balance) {
        return new LedgerRepository.AccountData(id,currency,balance,settlement?null:BigDecimal.ZERO,settlement);
    }
    private void assertRefused(PaymentMessage payment,String code) {
        int before=writes;
        assertEquals(code,assertThrows(LedgerFailure.class,()->service.settlePayment(payment)).code());
        assertEquals(before,writes);
    }
}
