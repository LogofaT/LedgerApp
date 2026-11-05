package ledger.service;

import com.fasterxml.jackson.databind.JsonNode;
import ledger.api.Models.CreateTransaction;
import ledger.api.Models.EntryInput;
import ledger.api.Models.OpenAccount;
import ledger.api.Models.PaymentMessage;
import ledger.persistence.LedgerRepository;
import ledger.persistence.LedgerRepository.PostingData;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Request validation only: no persistence writes. Order is part of the API contract. */
final class LedgerRequests {
    private final LedgerJson json;
    private final LedgerRepository db;
    private final int maxEntries;

    LedgerRequests(LedgerJson json, LedgerRepository db, int maxEntries) {
        this.json = json;
        this.db = db;
        this.maxEntries = maxEntries;
    }

    AccountInput account(String body) {
        OpenAccount request = parseAccount(body);
        if (request == null) {
            throw new LedgerFailure(400, "INVALID_REQUEST", "Request body is required", null);
        }
        String id = checkedText(request.accountId(), 64, "accountId");
        Money.scale(request.currency());
        BigDecimal limit = Money.parse(request.overdraftLimit() == null ? "0" : request.overdraftLimit(),
                request.currency(), "overdraftLimit", false);
        if (id.startsWith("SETTLEMENT-")) {
            throw new LedgerFailure(422, "RESERVED_ACCOUNT_ID", "Account ID is reserved", "accountId");
        }
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new LedgerFailure(400, "INVALID_ACCOUNT_ID", "Account ID contains invalid characters", "accountId");
        }
        return new AccountInput(id, request.currency(), limit);
    }

    TransactionInput transaction(String body) {
        CreateTransaction request = parseTransaction(body);
        if (request == null) {
            throw new LedgerFailure(400, "INVALID_REQUEST", "Request body is required", null);
        }
        String currency = request.currency();
        Money.scale(currency);
        String description = checkedText(request.description(), 240, "description");
        List<EntryInput> entries = request.entries();
        if (entries == null || entries.size() < 2) {
            throw new LedgerFailure(422, "TOO_FEW_ENTRIES", "At least two entries are required", "entries");
        }
        if (entries.size() > maxEntries) {
            throw new LedgerFailure(413, "TOO_MANY_ENTRIES", "Entry count exceeds configured limit", "entries");
        }
        return new TransactionInput(currency, description, validatePostings(entries, currency));
    }

    PaymentInput payment(PaymentMessage message) {
        if (message == null) {
            throw new LedgerFailure(400, "INVALID_PAYMENT", "Payment message is empty", null);
        }
        String paymentId = checkedIdentifier(message.paymentId(), 100, "paymentId");
        checkedIdentifier(message.eventId(), 120, "eventId");
        String requestId = message.requestId() == null ? null
                : checkedIdentifier(message.requestId(), 80, "requestId");
        String merchantId = checkedText(message.merchantAccountId(), 64, "merchantAccountId");
        BigDecimal amount = Money.parse(message.amount(), message.currency(), "amount", true);
        String description = message.description() == null ? "Payment settled"
                : checkedText(message.description(), 240, "description");
        String identity = json.write(new Object[] {
                paymentId, merchantId, message.currency(), Money.format(amount, message.currency())
        });
        return new PaymentInput(paymentId, merchantId, message.currency(), amount, requestId,
                description, LedgerJson.fingerprint(identity));
    }

    private List<PostingData> validatePostings(List<EntryInput> inputs, String currency) {
        List<PostingData> postings = new ArrayList<>();
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (int i = 0; i < inputs.size(); i++) {
            EntryInput entry = inputs.get(i);
            String field = "entries[" + i + "]";
            String id = checkedText(entry.accountId(), 64, field + ".accountId");
            String direction = entry.direction();
            if (!"DEBIT".equals(direction) && !"CREDIT".equals(direction)) {
                throw new LedgerFailure(400, "INVALID_DIRECTION", "Direction must be DEBIT or CREDIT", field + ".direction");
            }
            BigDecimal amount = Money.parse(entry.amount(), currency, field + ".amount", true);
            // Deliberately interleaved: account errors in entry N precede field errors in entry N+1.
            LedgerRepository.AccountData account = db.findAccount(id).orElseThrow(() ->
                    new LedgerFailure(404, "ACCOUNT_NOT_FOUND", "Account does not exist", field + ".accountId"));
            if (!currency.equals(account.currency())) {
                throw new LedgerFailure(422, "CURRENCY_MISMATCH",
                        "All entries and accounts must use the transaction currency", field + ".accountId");
            }
            if ("DEBIT".equals(direction)) {
                debits = debits.add(amount);
            } else {
                credits = credits.add(amount);
            }
            postings.add(new PostingData(id, direction, amount));
        }
        if (debits.signum() == 0 || credits.signum() == 0 || debits.compareTo(credits) != 0) {
            throw new LedgerFailure(422, "UNBALANCED_TRANSACTION", "Debits and credits must be positive and equal", "entries");
        }
        return List.copyOf(postings);
    }

    private OpenAccount parseAccount(String body) {
        JsonNode root = json.requestObject(body);
        if (root == null) {
            return null;
        }
        json.allowedFields(root, Set.of("accountId", "currency", "overdraftLimit"), null);
        return new OpenAccount(json.textField(root, "accountId", null), json.textField(root, "currency", null),
                json.textField(root, "overdraftLimit", null));
    }

    private CreateTransaction parseTransaction(String body) {
        JsonNode root = json.requestObject(body);
        if (root == null) {
            return null;
        }
        json.allowedFields(root, Set.of("currency", "description", "entries"), null);
        JsonNode entryNodes = root.get("entries");
        List<EntryInput> entries = null;
        if (entryNodes != null && !entryNodes.isNull()) {
            if (!entryNodes.isArray()) {
                throw new LedgerFailure(400, "INVALID_FIELD_TYPE", "Entries must be a JSON array", "entries");
            }
            entries = new ArrayList<>();
            for (int i = 0; i < entryNodes.size(); i++) {
                JsonNode entry = entryNodes.get(i);
                String path = "entries[" + i + "]";
                if (!entry.isObject()) {
                    throw new LedgerFailure(400, "INVALID_ENTRY", "Entry must be a JSON object", path);
                }
                json.allowedFields(entry, Set.of("accountId", "direction", "amount"), path);
                entries.add(new EntryInput(json.textField(entry, "accountId", path),
                        json.textField(entry, "direction", path), json.textField(entry, "amount", path)));
            }
        }
        return new CreateTransaction(json.textField(root, "currency", null),
                json.textField(root, "description", null), entries);
    }

    private String checkedText(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new LedgerFailure(400, "INVALID_FIELD",
                    "Field is required and must be at most " + max + " characters", field);
        }
        return value;
    }

    private String checkedIdentifier(String value, int max, String field) {
        String checked = checkedText(value, max, field);
        if (!checked.matches("[A-Za-z0-9._:-]{1," + max + "}")) {
            throw new LedgerFailure(400, "INVALID_IDENTIFIER", "Identifier contains unsupported characters", field);
        }
        return checked;
    }

    record AccountInput(String accountId, String currency, BigDecimal overdraftLimit) {}
    record TransactionInput(String currency, String description, List<PostingData> postings) {}
    record PaymentInput(String paymentId, String merchantAccountId, String currency, BigDecimal amount,
                        String requestId, String description, String fingerprint) {}
}
