package ledger.persistence;

import org.jooq.DSLContext;
import org.jooq.JSON;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

import static ledger.persistence.jooq.tables.TransactionOutbox.TRANSACTION_OUTBOX;
import static org.jooq.impl.DSL.currentTimestamp;

@Repository
public class OutboxRepository {
    private final DSLContext dsl;

    public OutboxRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<OutboxEvent> findPending(int limit) {
        return dsl.select(TRANSACTION_OUTBOX.EVENT_ID, TRANSACTION_OUTBOX.PAYLOAD)
                .from(TRANSACTION_OUTBOX)
                .where(TRANSACTION_OUTBOX.PUBLISHED_AT.isNull())
                .orderBy(TRANSACTION_OUTBOX.CREATED_AT, TRANSACTION_OUTBOX.EVENT_ID)
                .limit(limit)
                .fetch(r -> new OutboxEvent(r.get(TRANSACTION_OUTBOX.EVENT_ID),
                        r.get(TRANSACTION_OUTBOX.PAYLOAD).data()));
    }

    public void markPublished(String eventId) {
        dsl.update(TRANSACTION_OUTBOX)
                .set(TRANSACTION_OUTBOX.PUBLISHED_AT, currentTimestamp(6).cast(LocalDateTime.class))
                .set(TRANSACTION_OUTBOX.ATTEMPTS, TRANSACTION_OUTBOX.ATTEMPTS.add(1))
                .setNull(TRANSACTION_OUTBOX.LAST_ERROR)
                .where(TRANSACTION_OUTBOX.EVENT_ID.eq(eventId)
                        .and(TRANSACTION_OUTBOX.PUBLISHED_AT.isNull()))
                .execute();
    }

    public void recordFailure(String eventId, String reason) {
        dsl.update(TRANSACTION_OUTBOX)
                .set(TRANSACTION_OUTBOX.ATTEMPTS, TRANSACTION_OUTBOX.ATTEMPTS.add(1))
                .set(TRANSACTION_OUTBOX.LAST_ERROR, reason)
                .where(TRANSACTION_OUTBOX.EVENT_ID.eq(eventId)
                        .and(TRANSACTION_OUTBOX.PUBLISHED_AT.isNull()))
                .execute();
    }

    public record OutboxEvent(String eventId, String payload) {}
}
