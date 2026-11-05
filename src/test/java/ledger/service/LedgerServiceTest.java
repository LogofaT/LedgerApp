package ledger.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import ledger.persistence.LedgerRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LedgerServiceTest {
    @Test
    void aggregateFinalOverdraftRefusalHasNoFinancialWrites() {
        LedgerRepository repository = mock(LedgerRepository.class);
        when(repository.findAccount("a")).thenReturn(Optional.of(
                new LedgerRepository.AccountData("a", "EUR", BigDecimal.ZERO, BigDecimal.ZERO, false)));
        when(repository.findAccount("b")).thenReturn(Optional.of(
                new LedgerRepository.AccountData("b", "EUR", BigDecimal.ZERO, BigDecimal.ZERO, false)));
        LedgerService service = new LedgerService(repository, new ObjectMapper(), 100);
        String body = "{\"currency\":\"EUR\",\"description\":\"overdraft\",\"entries\":["
                + "{\"accountId\":\"a\",\"direction\":\"DEBIT\",\"amount\":\"10\"},"
                + "{\"accountId\":\"a\",\"direction\":\"CREDIT\",\"amount\":\"5\"},"
                + "{\"accountId\":\"b\",\"direction\":\"CREDIT\",\"amount\":\"5\"}]}";

        var result = service.createTransaction("overdraft", body, "correlation");

        assertEquals(422, result.status());
        assertTrue(result.rawBody().contains("\"code\":\"OVERDRAFT_EXCEEDED\""));
        assertTrue(result.rawBody().contains("\"field\":\"a\""));
        var order = inOrder(repository);
        order.verify(repository).lockGate();
        order.verify(repository).findIdempotencyReceipt("TRANSACTION", "overdraft");
        verify(repository).insertIdempotencyReceipt(any());
        verify(repository, never()).insertTransaction(any());
        verify(repository, never()).insertPosting(anyString(), anyInt(), any());
        verify(repository, never()).updateAccountBalance(anyString(), any());
        verify(repository, never()).insertOutbox(anyString(), anyString());
    }

    @Test
    void invalidKeyStillLocksGateBeforeAnyOtherSqlAndIsNotReceipted() {
        LedgerRepository repository = mock(LedgerRepository.class);
        LedgerService service = new LedgerService(repository, new ObjectMapper(), 100);

        LedgerFailure failure = assertThrows(LedgerFailure.class, () -> service.openAccount(null, "{", null));

        assertEquals("INVALID_IDEMPOTENCY_KEY", failure.code());
        verify(repository).lockGate();
        verifyNoMoreInteractions(repository);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void intermediateStorageOverflowIsAcceptedWhenFinalBalanceFits(boolean positive) {
        String max="999999999999999999.99";
        LedgerRepository repository=mock(LedgerRepository.class);
        BigDecimal starting=new BigDecimal(max).subtract(BigDecimal.ONE);
        var account=new LedgerRepository.AccountData("a","EUR",positive?starting:starting.negate(),
                new BigDecimal(max),false);
        when(repository.findIdempotencyReceipt("TRANSACTION","overflow")).thenReturn(Optional.empty());
        when(repository.findAccount("a")).thenReturn(Optional.of(account));

        LedgerService service=new LedgerService(repository,new ObjectMapper(),100);
        String first=positive?"CREDIT":"DEBIT",second=positive?"DEBIT":"CREDIT";
        String body="{\"currency\":\"EUR\",\"description\":\"intermediate overflow accepted\",\"entries\":["
                +"{\"accountId\":\"a\",\"direction\":\""+first+"\",\"amount\":\"1.00\"},"
                +"{\"accountId\":\"a\",\"direction\":\""+first+"\",\"amount\":\"0.01\"},"
                +"{\"accountId\":\"a\",\"direction\":\""+second+"\",\"amount\":\"1.01\"}]}";

        var result=service.createTransaction("overflow",body,"unit-correlation");

        assertEquals(201,result.status());
        verify(repository).insertIdempotencyReceipt(argThat(receipt ->
                receipt.operation().equals("TRANSACTION") && receipt.key().equals("overflow")
                        && receipt.httpStatus()==201));
        verify(repository).insertTransaction(any());
        verify(repository,times(3)).insertPosting(anyString(),anyInt(),any());
        verify(repository).updateAccountBalance("a",positive?starting:starting.negate());
        verify(repository).insertOutbox(anyString(),anyString());
    }
}
