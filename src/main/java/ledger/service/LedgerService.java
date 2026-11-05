package ledger.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import ledger.api.Models.*;
import ledger.persistence.LedgerRepository;
import ledger.persistence.LedgerRepository.AccountData;
import ledger.persistence.LedgerRepository.PostingData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LedgerService {
    private final LedgerRepository db;
    private final LedgerJson json;
    private final LedgerRequests requests;
    private final ReceiptExecutor receipts;
    private final TransactionWriter writer;

    public LedgerService(LedgerRepository db, ObjectMapper json,
                         @Value("${ledger.max-entries:100}") int maxEntries) {
        this.db = db;
        this.json = new LedgerJson(json);
        this.requests = new LedgerRequests(this.json, db, maxEntries);
        this.receipts = new ReceiptExecutor(db, this.json);
        this.writer = new TransactionWriter(db, this.json);
    }

    @Transactional
    public CommandResult<?> openAccount(String key, String body, String correlation) {
        db.lockGate();
        return receipts.execute("ACCOUNT", key, json.canonicalPayload(body), correlation,
                () -> handleOpenAccount(body));
    }

    @Transactional
    public CommandResult<?> createTransaction(String key, String body, String correlation) {
        db.lockGate();
        return receipts.execute("TRANSACTION", key, json.canonicalPayload(body), correlation,
                () -> handleTransaction(body, correlation));
    }

    @Transactional
    public CommandResult<?> reverse(String key, String transactionId, String correlation) {
        db.lockGate();
        String identity = transactionId == null ? "null" : transactionId;
        return receipts.execute("REVERSAL", key, identity, correlation,
                () -> handleReversal(transactionId, correlation));
    }

    @Transactional
    public PaymentOutcome settlePayment(PaymentMessage message) {
        db.lockGate();
        return handlePayment(requests.payment(message));
    }

    private CommandResult<AccountView> handleOpenAccount(String body) {
        LedgerRequests.AccountInput input = requests.account(body);
        if (db.findAccount(input.accountId()).isPresent()) {
            throw new LedgerFailure(409, "ACCOUNT_EXISTS", "Account already exists", "accountId");
        }
        db.insertAccount(input.accountId(), input.currency(), input.overdraftLimit());
        return new CommandResult<>(201, "/api/v1/accounts/" + input.accountId(), account(input.accountId()));
    }

    private CommandResult<TransactionView> handleTransaction(String body, String correlation) {
        LedgerRequests.TransactionInput input = requests.transaction(body);
        PostingPlan plan = preparePostings(input.postings());
        return writer.persist(input.currency(), input.description(), null, correlation, null, plan);
    }

    private CommandResult<TransactionView> handleReversal(String transactionId, String correlation) {
        LedgerRepository.TransactionData original = db.findTransaction(transactionId).orElseThrow(() ->
                new LedgerFailure(404, "TRANSACTION_NOT_FOUND", "Transaction does not exist", "transactionId"));
        if (original.reversalOf() != null) {
            throw new LedgerFailure(422, "REVERSAL_OF_REVERSAL", "A reversal cannot itself be reversed", "transactionId");
        }
        if (db.hasReversal(transactionId)) {
            throw new LedgerFailure(409, "ALREADY_REVERSED", "Transaction was already reversed", "transactionId");
        }
        List<PostingData> flips = db.findPostings(transactionId).stream()
                .map(posting -> new PostingData(posting.accountId(),
                        "DEBIT".equals(posting.direction()) ? "CREDIT" : "DEBIT", posting.amount()))
                .toList();
        PostingPlan plan = preparePostings(flips);
        return writer.persist(original.currency(), "Reversal of " + transactionId,
                transactionId, correlation, null, plan);
    }

    private PaymentOutcome handlePayment(LedgerRequests.PaymentInput input) {
        var existing = db.findPaymentReceipt(input.paymentId());
        if (existing.isPresent()) {
            var receipt = existing.get();
            if (!input.fingerprint().equals(receipt.economicFingerprint())) {
                throw new LedgerFailure(409, "PAYMENT_ID_PAYLOAD_CONFLICT",
                        "Payment ID was reused with different economic data", "paymentId");
            }
            return new PaymentOutcome(receipt.outcome(), receipt.transactionId(), true);
        }
        validateMerchant(input);
        List<PostingData> postings = List.of(
                new PostingData("SETTLEMENT-" + input.currency(), "DEBIT", input.amount()),
                new PostingData(input.merchantAccountId(), "CREDIT", input.amount()));
        PostingPlan plan = preparePostings(postings);
        TransactionView transaction = writer.persist(input.currency(), input.description(), null,
                input.requestId(), input.paymentId(), plan).body();
        db.insertPaymentReceipt(input.paymentId(), input.fingerprint(), transaction.transactionId());
        return new PaymentOutcome("SETTLED", transaction.transactionId(), false);
    }

    private void validateMerchant(LedgerRequests.PaymentInput input) {
        AccountData merchant = db.findAccount(input.merchantAccountId()).orElseThrow(() ->
                new LedgerFailure(404, "ACCOUNT_NOT_FOUND", "Merchant account does not exist", "merchantAccountId"));
        if (merchant.settlement() || input.merchantAccountId().startsWith("SETTLEMENT-")) {
            throw new LedgerFailure(422, "NOT_MERCHANT_ACCOUNT",
                    "Settlement account cannot be a merchant", "merchantAccountId");
        }
        if (!input.currency().equals(merchant.currency())) {
            throw new LedgerFailure(422, "CURRENCY_MISMATCH",
                    "Merchant account must use the payment currency", "merchantAccountId");
        }
    }

    private PostingPlan preparePostings(List<PostingData> postings) {
        Map<String, AccountData> accounts = new LinkedHashMap<>();
        for (PostingData posting : postings) {
            String id = posting.accountId();
            if (!accounts.containsKey(id)) {
                accounts.put(id, db.findAccount(id).orElse(null));
            }
        }
        return PostingPlan.prepare(postings, accounts);
    }

    @Transactional(readOnly = true)
    public AccountView account(String id) {
        AccountData account = db.findAccount(id).orElseThrow(() ->
                new LedgerFailure(404, "ACCOUNT_NOT_FOUND", "Account does not exist", "accountId"));
        return accountView(account);
    }

    @Transactional(readOnly = true)
    public TransactionView transaction(String id) {
        LedgerRepository.TransactionData transaction = db.findTransaction(id).orElseThrow(() ->
                new LedgerFailure(404, "TRANSACTION_NOT_FOUND", "Transaction does not exist", "transactionId"));
        return mapTransaction(transaction);
    }

    private TransactionView mapTransaction(LedgerRepository.TransactionData header) {
        String currency = header.currency();
        List<EntryView> entries = db.findTransactionEntries(header.transactionId()).stream()
                .map(entry -> new EntryView(entry.entryIndex(), entry.accountId(), entry.direction(),
                        Money.format(entry.amount(), currency)))
                .toList();
        return new TransactionView(header.transactionId(), header.recordedAt().toString(), currency, header.description(),
                header.reversalOf(), header.requestId(), header.paymentId(), entries);
    }

    private AccountView accountView(AccountData account) {
        return new AccountView(account.accountId(), account.currency(), Money.format(account.balance(), account.currency()),
                account.overdraftLimit() == null ? null : Money.format(account.overdraftLimit(), account.currency()),
                account.settlement());
    }

    public record PaymentOutcome(String outcome, String transactionId, boolean duplicate) {}
}
