package ledger.persistence;

import ledger.persistence.jooq.enums.LedgerEntryDirection;
import ledger.persistence.jooq.tables.records.LedgerTransactionRecord;
import org.jooq.DSLContext;
import org.jooq.JSON;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static ledger.persistence.jooq.tables.IdempotencyReceipt.IDEMPOTENCY_RECEIPT;
import static ledger.persistence.jooq.tables.LedgerAccount.LEDGER_ACCOUNT;
import static ledger.persistence.jooq.tables.LedgerEntry.LEDGER_ENTRY;
import static ledger.persistence.jooq.tables.LedgerGate.LEDGER_GATE;
import static ledger.persistence.jooq.tables.LedgerTransaction.LEDGER_TRANSACTION;
import static ledger.persistence.jooq.tables.PaymentReceipt.PAYMENT_RECEIPT;
import static ledger.persistence.jooq.tables.TransactionOutbox.TRANSACTION_OUTBOX;
/**
 * Focused persistence boundary for ledger commands and reads.
 *
 * jOOQ records and generated schema identifiers stay inside this class; the
 * service consumes the small application records declared at the bottom.
 */
@Repository
public class LedgerRepository {
    private final DSLContext dsl;

    public LedgerRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void lockGate() {
        dsl.select(LEDGER_GATE.ID)
                .from(LEDGER_GATE)
                .where(LEDGER_GATE.ID.eq((byte) 1))
                .forUpdate()
                .fetchOne();
    }

    public Optional<IdempotencyReceiptData> findIdempotencyReceipt(String operation, String key) {
        return dsl.selectFrom(IDEMPOTENCY_RECEIPT)
                .where(IDEMPOTENCY_RECEIPT.OPERATION.eq(operation)
                        .and(IDEMPOTENCY_RECEIPT.IDEMPOTENCY_KEY.eq(key)))
                .fetchOptional()
                .map(r -> new IdempotencyReceiptData(r.getOperation(), r.getIdempotencyKey(),
                        r.getFingerprint(), r.getHttpStatus(), r.getResponseBody(), r.getLocation()));
    }

    public void insertIdempotencyReceipt(IdempotencyReceiptData receipt) {
        dsl.insertInto(IDEMPOTENCY_RECEIPT)
                .set(IDEMPOTENCY_RECEIPT.OPERATION, receipt.operation())
                .set(IDEMPOTENCY_RECEIPT.IDEMPOTENCY_KEY, receipt.key())
                .set(IDEMPOTENCY_RECEIPT.FINGERPRINT, receipt.fingerprint())
                .set(IDEMPOTENCY_RECEIPT.HTTP_STATUS, (short) receipt.httpStatus())
                .set(IDEMPOTENCY_RECEIPT.RESPONSE_BODY, receipt.responseBody())
                .set(IDEMPOTENCY_RECEIPT.LOCATION, receipt.location())
                .execute();
    }

    public Optional<AccountData> findAccount(String id) {
        return dsl.selectFrom(LEDGER_ACCOUNT)
                .where(LEDGER_ACCOUNT.ACCOUNT_ID.eq(id))
                .fetchOptional()
                .map(r -> new AccountData(r.getAccountId(), r.getCurrency(), r.getBalance(),
                        r.getOverdraftLimit(), r.getSettlement() != 0));
    }

    public void insertAccount(String id, String currency, BigDecimal overdraftLimit) {
        dsl.insertInto(LEDGER_ACCOUNT)
                .set(LEDGER_ACCOUNT.ACCOUNT_ID, id)
                .set(LEDGER_ACCOUNT.CURRENCY, currency)
                .set(LEDGER_ACCOUNT.OVERDRAFT_LIMIT, overdraftLimit)
                .set(LEDGER_ACCOUNT.BALANCE, BigDecimal.ZERO)
                .set(LEDGER_ACCOUNT.SETTLEMENT, (byte) 0)
                .execute();
    }

    public Optional<TransactionData> findTransaction(String id) {
        return dsl.selectFrom(LEDGER_TRANSACTION)
                .where(LEDGER_TRANSACTION.TRANSACTION_ID.eq(id))
                .fetchOptional()
                .map(LedgerRepository::transactionData);
    }

    public boolean hasReversal(String transactionId) {
        return dsl.fetchExists(dsl.selectOne()
                .from(LEDGER_TRANSACTION)
                .where(LEDGER_TRANSACTION.REVERSAL_OF.eq(transactionId)));
    }

    public List<PostingData> findPostings(String transactionId) {
        return dsl.selectFrom(LEDGER_ENTRY)
                .where(LEDGER_ENTRY.TRANSACTION_ID.eq(transactionId))
                .orderBy(LEDGER_ENTRY.ENTRY_INDEX)
                .fetch(r -> new PostingData(r.getAccountId(), r.getDirection().getLiteral(), r.getAmount()));
    }

    public Optional<PaymentReceiptData> findPaymentReceipt(String paymentId) {
        return dsl.selectFrom(PAYMENT_RECEIPT)
                .where(PAYMENT_RECEIPT.PAYMENT_ID.eq(paymentId))
                .fetchOptional()
                .map(r -> new PaymentReceiptData(r.getEconomicFingerprint(), r.getTransactionId(), r.getOutcome()));
    }

    public void insertPaymentReceipt(String paymentId, String fingerprint, String transactionId) {
        dsl.insertInto(PAYMENT_RECEIPT)
                .set(PAYMENT_RECEIPT.PAYMENT_ID, paymentId)
                .set(PAYMENT_RECEIPT.ECONOMIC_FINGERPRINT, fingerprint)
                .set(PAYMENT_RECEIPT.TRANSACTION_ID, transactionId)
                .set(PAYMENT_RECEIPT.OUTCOME, "SETTLED")
                .execute();
    }

    public void insertTransaction(TransactionData transaction) {
        dsl.insertInto(LEDGER_TRANSACTION)
                .set(LEDGER_TRANSACTION.TRANSACTION_ID, transaction.transactionId())
                .set(LEDGER_TRANSACTION.CURRENCY, transaction.currency())
                .set(LEDGER_TRANSACTION.DESCRIPTION, transaction.description())
                .set(LEDGER_TRANSACTION.REVERSAL_OF, transaction.reversalOf())
                .set(LEDGER_TRANSACTION.REQUEST_ID, transaction.requestId())
                .set(LEDGER_TRANSACTION.PAYMENT_ID, transaction.paymentId())
                .set(LEDGER_TRANSACTION.CREATED_AT,
                        LocalDateTime.ofInstant(transaction.recordedAt(), ZoneOffset.UTC))
                .execute();
    }

    public void insertPosting(String transactionId, int index, PostingData posting) {
        dsl.insertInto(LEDGER_ENTRY)
                .set(LEDGER_ENTRY.TRANSACTION_ID, transactionId)
                .set(LEDGER_ENTRY.ENTRY_INDEX, (short) index)
                .set(LEDGER_ENTRY.ACCOUNT_ID, posting.accountId())
                .set(LEDGER_ENTRY.DIRECTION, LedgerEntryDirection.lookupLiteral(posting.direction()))
                .set(LEDGER_ENTRY.AMOUNT, posting.amount())
                .execute();
    }

    public void updateAccountBalance(String accountId, BigDecimal balance) {
        dsl.update(LEDGER_ACCOUNT)
                .set(LEDGER_ACCOUNT.BALANCE, balance)
                .where(LEDGER_ACCOUNT.ACCOUNT_ID.eq(accountId))
                .execute();
    }

    public void insertOutbox(String eventId, String payload) {
        dsl.insertInto(TRANSACTION_OUTBOX)
                .set(TRANSACTION_OUTBOX.EVENT_ID, eventId)
                .set(TRANSACTION_OUTBOX.TRANSACTION_ID, eventId)
                .set(TRANSACTION_OUTBOX.PAYLOAD, JSON.valueOf(payload))
                .execute();
    }

    public List<EntryData> findTransactionEntries(String transactionId) {
        return dsl.selectFrom(LEDGER_ENTRY)
                .where(LEDGER_ENTRY.TRANSACTION_ID.eq(transactionId))
                .orderBy(LEDGER_ENTRY.ENTRY_INDEX)
                .fetch(r -> new EntryData(r.getEntryIndex(), r.getAccountId(), r.getDirection().getLiteral(),
                        r.getAmount()));
    }

    private static TransactionData transactionData(LedgerTransactionRecord r) {
        return new TransactionData(r.getTransactionId(),
                r.getCreatedAt().toInstant(ZoneOffset.UTC), r.getCurrency(), r.getDescription(),
                r.getReversalOf(), r.getRequestId(), r.getPaymentId());
    }

    public record AccountData(String accountId, String currency, BigDecimal balance,
                              BigDecimal overdraftLimit, boolean settlement) {}
    public record TransactionData(String transactionId, Instant recordedAt, String currency,
                                  String description, String reversalOf, String requestId, String paymentId) {}
    public record PostingData(String accountId, String direction, BigDecimal amount) {}
    public record EntryData(int entryIndex, String accountId, String direction, BigDecimal amount) {}
    public record IdempotencyReceiptData(String operation, String key, String fingerprint, int httpStatus,
                                         String responseBody, String location) {}
    public record PaymentReceiptData(String economicFingerprint, String transactionId, String outcome) {}
}
