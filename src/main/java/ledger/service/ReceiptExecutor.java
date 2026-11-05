package ledger.service;

import ledger.api.Models.CommandResult;
import ledger.api.Problems;
import ledger.persistence.LedgerRepository;
import ledger.persistence.LedgerRepository.IdempotencyReceiptData;

import java.util.function.Supplier;

/** Runs inside the caller's Spring transaction, after the caller has locked the gate. */
final class ReceiptExecutor {
    private final LedgerRepository db;
    private final LedgerJson json;

    ReceiptExecutor(LedgerRepository db, LedgerJson json) {
        this.db = db;
        this.json = json;
    }

    CommandResult<?> execute(String operation, String key, String identity, String correlation,
                             Supplier<? extends CommandResult<?>> handler) {
        requireKey(key);
        String fingerprint = LedgerJson.fingerprint(identity);
        var existing = db.findIdempotencyReceipt(operation, key);
        if (existing.isPresent()) {
            IdempotencyReceiptData receipt = existing.get();
            if (!fingerprint.equals(receipt.fingerprint())) {
                throw new LedgerFailure(409, "IDEMPOTENCY_KEY_REUSED",
                        "Idempotency key was already used with a different request", null);
            }
            return new CommandResult<>(receipt.httpStatus(), receipt.location(), null, receipt.responseBody());
        }

        CommandResult<?> result;
        try {
            result = handler.get();
        } catch (LedgerFailure refusal) {
            result = new CommandResult<>(refusal.status(), null, Problems.from(refusal, correlation));
        }
        // Serialization and persistence failures must escape, never become terminal receipts.
        String rawBody = json.write(result.body());
        db.insertIdempotencyReceipt(new IdempotencyReceiptData(operation, key, fingerprint,
                result.status(), rawBody, result.location()));
        return new CommandResult<>(result.status(), result.location(), result.body(), rawBody);
    }

    private void requireKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,100}")) {
            throw new LedgerFailure(400, "INVALID_IDEMPOTENCY_KEY",
                    "A bounded Idempotency-Key header is required", null);
        }
    }
}
