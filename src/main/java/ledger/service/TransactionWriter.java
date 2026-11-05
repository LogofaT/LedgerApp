package ledger.service;

import ledger.api.Models.CommandResult;
import ledger.api.Models.EntryView;
import ledger.api.Models.TransactionView;
import ledger.persistence.LedgerRepository;
import ledger.persistence.LedgerRepository.TransactionData;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Persistence of an already accepted plan; transaction and gate ownership stay in LedgerService. */
final class TransactionWriter {
    private final LedgerRepository db;
    private final LedgerJson json;

    TransactionWriter(LedgerRepository db, LedgerJson json) {
        this.db = db;
        this.json = json;
    }

    CommandResult<TransactionView> persist(String currency, String description, String reversalOf,
                                           String requestId, String paymentId, PostingPlan plan) {
        String id = UUID.randomUUID().toString();
        TransactionData header = new TransactionData(id, Instant.now().truncatedTo(ChronoUnit.MICROS), currency,
                description, reversalOf, requestId, paymentId);
        db.insertTransaction(header);
        for (PostingPlan.PlannedEntry entry : plan.entries()) {
            db.insertPosting(id, entry.index(), entry.posting());
        }
        plan.endingBalances().forEach(db::updateAccountBalance);
        TransactionView view = response(header, plan);
        db.insertOutbox(id, json.write(view));
        return new CommandResult<>(201, "/api/v1/transactions/" + id, view);
    }

    private TransactionView response(TransactionData header, PostingPlan plan) {
        String currency = header.currency();
        var entries = plan.entries().stream()
                .map(entry -> new EntryView(entry.index(), entry.posting().accountId(), entry.posting().direction(),
                        Money.format(entry.posting().amount(), currency)))
                .toList();
        return new TransactionView(header.transactionId(), header.recordedAt().toString(), currency, header.description(),
                header.reversalOf(), header.requestId(), header.paymentId(), entries);
    }
}
