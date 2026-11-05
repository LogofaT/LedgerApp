package ledger.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import ledger.api.Models.CommandResult;
import ledger.api.Models.ProblemView;
import ledger.api.Problems;
import ledger.persistence.LedgerRepository;
import ledger.persistence.LedgerRepository.IdempotencyReceiptData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReceiptExecutorTest {
    private LedgerRepository db;
    private LedgerJson json;
    private ReceiptExecutor executor;

    @BeforeEach
    void setUp() {
        db = mock(LedgerRepository.class);
        json = new LedgerJson(new ObjectMapper());
        executor = new ReceiptExecutor(db, json);
    }

    @Test
    void canonicalIdentityReplaysExactStoredStatusLocationAndBytesWithoutHandler() throws Exception {
        AtomicReference<IdempotencyReceiptData> stored = captureReceipt("ACCOUNT", "key");
        var first = executor.execute("ACCOUNT", "key", json.canonicalPayload("{\"b\":2,\"a\":\"1\"}"),
                "original", () -> new CommandResult<>(201, "/original", new String[] {"original", "body"}));
        assertEquals(sha256("{\"a\":\"1\",\"b\":2}"), stored.get().fingerprint());
        var replay = executor.execute("ACCOUNT", "key", json.canonicalPayload(" {\"a\":\"1\", \"b\":2} "),
                "new-correlation", () -> { throw new AssertionError("Replay must bypass command validation"); });
        assertEquals(first.status(), replay.status());
        assertEquals(first.location(), replay.location());
        assertEquals(first.rawBody(), replay.rawBody());
        assertNull(replay.body(), "Do not deserialize and reconstruct stored responses");
        verify(db, times(1)).insertIdempotencyReceipt(any());
    }

    @Test
    void replayDoesNotNormalizeOrReserializeEvenOddStoredWhitespace() throws Exception {
        String raw = " { \"code\" : \"OLD\", \"correlationId\" : \"original\" }\n";
        when(db.findIdempotencyReceipt("ACCOUNT", "key")).thenReturn(Optional.of(
                new IdempotencyReceiptData("ACCOUNT", "key", sha256("{}"), 422, raw, "/stored")));
        var result = executor.execute("ACCOUNT", "key", "{}", "new", () -> { throw new AssertionError(); });
        assertEquals(raw, result.rawBody());
        assertEquals(422, result.status());
        assertEquals("/stored", result.location());
        verify(db, never()).insertIdempotencyReceipt(any());
    }

    @Test
    void mismatchIsNotReceiptedAndDoesNotRunHandler() {
        captureReceipt("TRANSACTION", "key");
        executor.execute("TRANSACTION", "key", "{\"amount\":\"7\"}", null, () -> new CommandResult<>(201, null, "ok"));
        LedgerFailure failure = assertThrows(LedgerFailure.class, () ->
                executor.execute("TRANSACTION", "key", "{\"amount\":\"7.00\"}", null, () -> { throw new AssertionError(); }));
        assertEquals(409, failure.status());
        assertEquals("IDEMPOTENCY_KEY_REUSED", failure.code());
        assertNull(failure.field());
        verify(db, times(1)).insertIdempotencyReceipt(any());
    }

    @Test
    void malformedBodyUsesExactRawIdentityAndReplaysOriginalRefusal() throws Exception {
        AtomicReference<IdempotencyReceiptData> stored = captureReceipt("ACCOUNT", "key");
        LedgerService service = new LedgerService(db, new ObjectMapper(), 100);
        var first = service.openAccount("key", "{", "original");
        assertEquals(sha256("RAW:{"), stored.get().fingerprint());
        assertEquals(400, first.status());
        assertEquals(first.rawBody(), service.openAccount("key", "{", "new").rawBody());
        assertTrue(first.rawBody().contains("MALFORMED_REQUEST"));
        assertTrue(first.rawBody().contains("original"));
        assertEquals("IDEMPOTENCY_KEY_REUSED", assertThrows(LedgerFailure.class,
                () -> service.openAccount("key", " {", null)).code());
        verify(db, times(3)).lockGate();
        verify(db, times(1)).insertIdempotencyReceipt(any());
    }

    @Test
    void reversalIdentityIncludesExactTargetAndReplaysBeforeLookingUpTarget() throws Exception {
        AtomicReference<IdempotencyReceiptData> stored = captureReceipt("REVERSAL", "key");
        LedgerService service = new LedgerService(db, new ObjectMapper(), 100);
        var first = service.reverse("key", "target-a", "original");
        assertEquals(sha256("target-a"), stored.get().fingerprint());
        assertEquals(404, first.status());
        assertEquals(first.rawBody(), service.reverse("key", "target-a", "new").rawBody());
        assertEquals("IDEMPOTENCY_KEY_REUSED", assertThrows(LedgerFailure.class,
                () -> service.reverse("key", "target-b", null)).code());
        verify(db, times(1)).findTransaction("target-a");
        verify(db, never()).findTransaction("target-b");
    }

    @Test
    void expectedBusinessFailureIsReceiptedUsingSharedProblemFactory() {
        LedgerFailure refusal = new LedgerFailure(422, "OVERDRAFT_EXCEEDED", "Refused", "a");
        var result = executor.execute("TRANSACTION", "key", "body", "correlation", () -> { throw refusal; });
        assertEquals(Problems.from(refusal, "correlation"), result.body());
        assertInstanceOf(ProblemView.class, result.body());
        ArgumentCaptor<IdempotencyReceiptData> receipt = ArgumentCaptor.forClass(IdempotencyReceiptData.class);
        verify(db).insertIdempotencyReceipt(receipt.capture());
        assertEquals(result.rawBody(), receipt.getValue().responseBody());
        assertEquals(422, receipt.getValue().httpStatus());
        verify(db, never()).insertTransaction(any());
    }

    @Test
    void infrastructureFailureFromHandlerOrLookupEscapesWithoutReceipt() {
        var failure = new DataAccessResourceFailureException("database unavailable");
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> executor.execute("ACCOUNT", "key", "{}", null, () -> { throw failure; })));
        when(db.findIdempotencyReceipt("ACCOUNT", "key")).thenThrow(failure);
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> executor.execute("ACCOUNT", "key", "{}", null, () -> { throw new AssertionError(); })));
        verify(db, never()).insertIdempotencyReceipt(any());
    }

    @Test
    void serializationFailureEscapesWithoutReceipt() {
        var result = new CommandResult<>(201, null, new Object());
        assertThrows(IllegalStateException.class, () ->
                executor.execute("ACCOUNT", "key", "{}", null, () -> result));
        verify(db, never()).insertIdempotencyReceipt(any());
    }

    @Test
    void receiptWriteFailureEscapesInsteadOfReturningApparentSuccess() {
        var failure = new DataAccessResourceFailureException("receipt write failed");
        doThrow(failure).when(db).insertIdempotencyReceipt(any());
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class, () ->
                executor.execute("ACCOUNT", "key", "{}", null, () -> new CommandResult<>(201, null, "ok"))));
        verify(db, times(1)).insertIdempotencyReceipt(any());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"bad key", "bad/key", "bad\nkey"})
    void invalidKeyHasSingleOwnerAndPrecedesLookupOrHandler(String key) {
        assertEquals("INVALID_IDEMPOTENCY_KEY", assertThrows(LedgerFailure.class,
                () -> executor.execute("ACCOUNT", key, "{}", null, () -> { throw new AssertionError(); })).code());
        verifyNoInteractions(db);
    }

    private AtomicReference<IdempotencyReceiptData> captureReceipt(String operation, String key) {
        AtomicReference<IdempotencyReceiptData> stored = new AtomicReference<>();
        when(db.findIdempotencyReceipt(operation, key)).thenAnswer(call -> Optional.ofNullable(stored.get()));
        doAnswer(call -> {
            stored.set(call.getArgument(0));
            return null;
        }).when(db).insertIdempotencyReceipt(any());
        return stored;
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
