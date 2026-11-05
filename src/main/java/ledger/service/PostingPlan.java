package ledger.service;

import ledger.persistence.LedgerRepository.AccountData;
import ledger.persistence.LedgerRepository.PostingData;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure preparation from locked account snapshots. Economic overdraft and
 * storage bounds apply to final aggregate balances.
 */
record PostingPlan(List<PlannedEntry> entries, Map<String, BigDecimal> endingBalances) {
    PostingPlan {
        entries = List.copyOf(entries);
        endingBalances = Collections.unmodifiableMap(new LinkedHashMap<>(endingBalances));
    }

    static PostingPlan prepare(List<PostingData> postings, Map<String, AccountData> accounts) {
        Map<String, BigDecimal> deltas = new LinkedHashMap<>();
        for (PostingData posting : postings) {
            deltas.merge(posting.accountId(), signedAmount(posting), BigDecimal::add);
        }

        Map<String, BigDecimal> ending = new LinkedHashMap<>();
        for (var delta : deltas.entrySet()) {
            String id = delta.getKey();
            AccountData account = accounts.get(id);
            if (account == null) {
                throw new LedgerFailure(404, "ACCOUNT_NOT_FOUND", "Account does not exist", "entries.accountId");
            }
            BigDecimal balance = account.balance().add(delta.getValue());
            if (account.overdraftLimit() != null && balance.compareTo(account.overdraftLimit().negate()) < 0) {
                throw new LedgerFailure(422, "OVERDRAFT_EXCEEDED",
                        "Transaction would exceed the account overdraft limit", id);
            }
            if (!Money.withinStorage(balance)) {
                throw new LedgerFailure(422, "BALANCE_OUT_OF_RANGE",
                        "Final account balance exceeds ledger storage bounds", id);
            }
            ending.put(id, balance);
        }

        List<PlannedEntry> entries = new ArrayList<>();
        for (int i = 0; i < postings.size(); i++) {
            PostingData posting = postings.get(i);
            entries.add(new PlannedEntry(i, posting));
        }
        return new PostingPlan(entries, ending);
    }

    private static BigDecimal signedAmount(PostingData posting) {
        return "CREDIT".equals(posting.direction()) ? posting.amount() : posting.amount().negate();
    }

    record PlannedEntry(int index, PostingData posting) {}
}
