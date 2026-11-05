package ledger.service;

import ledger.persistence.LedgerRepository.AccountData;
import ledger.persistence.LedgerRepository.PostingData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PostingPlanTest {
    private static final BigDecimal MAX = new BigDecimal("999999999999999999.99");

    @Test
    void repeatedAccountUsesAggregateOverdraftNotEachIntermediateBalance() {
        List<PostingData> posts = List.of(post("a", "DEBIT", "10"), post("a", "CREDIT", "10"));
        AccountData snapshot = account("a", "0", BigDecimal.ZERO);
        PostingPlan plan = PostingPlan.prepare(posts, Map.of("a", snapshot));
        assertEquals(BigDecimal.ZERO, plan.endingBalances().get("a"));
        assertEquals(BigDecimal.ZERO, snapshot.balance(), "Preparation must not mutate snapshots");
        assertEquals(posts, plan.entries().stream().map(PostingPlan.PlannedEntry::posting).toList());
        assertThrows(UnsupportedOperationException.class, () -> plan.entries().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.endingBalances().clear());
    }

    @Test
    void aggregateOverdraftIsCheckedBeforeAnyIntermediateStorageFailure() {
        List<PostingData> posts = List.of(post("a", "DEBIT", MAX.toPlainString()), post("a", "CREDIT", "1"));
        LedgerFailure failure = assertThrows(LedgerFailure.class,
                () -> PostingPlan.prepare(posts, Map.of("a", account("a", "-1", BigDecimal.ZERO))));
        assertFailure(failure, "OVERDRAFT_EXCEEDED", "a", "Transaction would exceed the account overdraft limit");
    }

    @Test
    void overdraftExactlyAtLimitIsAllowedAndNullMeansUnlimitedNotZero() {
        PostingPlan finite = PostingPlan.prepare(List.of(post("a", "DEBIT", "5")),
                Map.of("a", account("a", "0", new BigDecimal("5"))));
        assertEquals(new BigDecimal("-5"), finite.endingBalances().get("a"));
        PostingPlan unlimited = PostingPlan.prepare(List.of(post("a", "DEBIT", MAX.toPlainString())),
                Map.of("a", account("a", "0", null)));
        assertEquals(MAX.negate(), unlimited.endingBalances().get("a"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void signedFinalStorageBoundsStillApplyToUnlimitedAccounts(boolean positive) {
        String direction = positive ? "CREDIT" : "DEBIT";
        String starting = (positive ? MAX : MAX.negate()).toPlainString();
        LedgerFailure failure = assertThrows(LedgerFailure.class, () ->
                PostingPlan.prepare(List.of(post("a", direction, "0.01")),
                        Map.of("a", account("a", starting, null))));
        assertFailure(failure, "BALANCE_OUT_OF_RANGE", "a", "Final account balance exceeds ledger storage bounds");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void intermediateStorageOverflowIsAllowedWhenFinalAggregateFits(boolean positive) {
        String first = positive ? "CREDIT" : "DEBIT";
        String last = positive ? "DEBIT" : "CREDIT";
        BigDecimal starting = MAX.subtract(BigDecimal.ONE);
        if (!positive) {
            starting = starting.negate();
        }
        List<PostingData> posts = List.of(post("a", first, "1"), post("a", first, "0.01"), post("a", last, "1.01"));
        Map<String, AccountData> accounts = Map.of("a", account("a", starting.toPlainString(), null));
        PostingPlan plan = PostingPlan.prepare(posts, accounts);
        assertEquals(starting, plan.endingBalances().get("a"));
        assertEquals(posts, plan.entries().stream().map(PostingPlan.PlannedEntry::posting).toList());
    }

    @Test
    void accountFailureOrderFollowsFirstOccurrenceAndFinalChecksPrecedeOrderedChecks() {
        var posts = List.of(post("a", "CREDIT", "1"), post("b", "DEBIT", "1"));
        var accounts = Map.of("a", account("a", MAX.toPlainString(), null), "b", account("b", "0", BigDecimal.ZERO));
        LedgerFailure failure = assertThrows(LedgerFailure.class, () -> PostingPlan.prepare(posts, accounts));
        assertEquals("BALANCE_OUT_OF_RANGE", failure.code());
        assertEquals("a", failure.field());
        assertEquals("ACCOUNT_NOT_FOUND", assertThrows(LedgerFailure.class,
                () -> PostingPlan.prepare(posts, Map.of())).code());
    }

    @Test
    void planContainsExactOrderedPostingsAndFinalProjectionsForMultipleAccounts() {
        var plan = PostingPlan.prepare(List.of(post("b", "CREDIT", "3.10"), post("a", "DEBIT", "2.00"),
                        post("b", "DEBIT", "1.10")),
                Map.of("a", account("a", "5.00", BigDecimal.ZERO), "b", account("b", "1.00", BigDecimal.ZERO)));
        assertEquals(List.of(0, 1, 2), plan.entries().stream().map(PostingPlan.PlannedEntry::index).toList());
        assertEquals(List.of("b", "a"), List.copyOf(plan.endingBalances().keySet()));
        assertEquals(Map.of("a", new BigDecimal("3.00"), "b", new BigDecimal("3.00")), plan.endingBalances());
    }

    private PostingData post(String account, String direction, String amount) {
        return new PostingData(account, direction, new BigDecimal(amount));
    }

    private AccountData account(String id, String balance, BigDecimal limit) {
        return new AccountData(id, "EUR", new BigDecimal(balance), limit, limit == null);
    }

    private void assertFailure(LedgerFailure failure, String code, String field, String detail) {
        assertEquals(422, failure.status());
        assertEquals(code, failure.code());
        assertEquals(field, failure.field());
        assertEquals(detail, failure.getMessage());
    }
}
