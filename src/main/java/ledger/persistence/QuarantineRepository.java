package ledger.persistence;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import static ledger.persistence.jooq.tables.MessageQuarantine.MESSAGE_QUARANTINE;

@Repository
public class QuarantineRepository {
    private final DSLContext dsl;
    private final LedgerRepository ledgerRepository;

    public QuarantineRepository(DSLContext dsl, LedgerRepository ledgerRepository) {
        this.dsl = dsl;
        this.ledgerRepository = ledgerRepository;
    }

    public void lockGate() {
        ledgerRepository.lockGate();
    }

    public void store(String eventId, String paymentId, String reason, String payload) {
        dsl.insertInto(MESSAGE_QUARANTINE)
                .set(MESSAGE_QUARANTINE.EVENT_ID, bounded(eventId, 120))
                .set(MESSAGE_QUARANTINE.PAYMENT_ID, bounded(paymentId, 100))
                .set(MESSAGE_QUARANTINE.REASON_CODE, bounded(reason, 60))
                .set(MESSAGE_QUARANTINE.PAYLOAD, payload)
                .execute();
    }

    private String bounded(String value, int max) {
        return value == null ? null : value.substring(0, Math.min(value.length(), max));
    }
}
