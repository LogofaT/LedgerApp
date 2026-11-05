package ledger.api;

import java.util.List;

public final class Models {
    private Models() {
    }

    public record OpenAccount(String accountId, String currency, String overdraftLimit) {
    }

    public record EntryInput(String accountId, String direction, String amount) {
    }

    public record CreateTransaction(String currency, String description, List<EntryInput> entries) {
    }

    public record AccountView(String accountId, String currency, String balance,
                              @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
                              String overdraftLimit, boolean settlement) {
    }

    public record EntryView(int entryIndex, String accountId, String direction, String amount) {
    }

    public record TransactionView(String transactionId, String recordedAt, String currency,
                                  String description, String reversalOf, String requestId, String paymentId,
                                  List<EntryView> entries) {
    }

    public record ProblemView(String type, String title, int status, String detail, String code,
                              String field, String correlationId) {
    }

    public record CommandResult<T>(int status, String location, T body, String rawBody) {
        public CommandResult(int status, String location, T body) {
            this(status, location, body, null);
        }
    }

    public record PaymentMessage(String eventId, String paymentId, String merchantAccountId, String amount,
                                 String currency, String requestId, String description) {
    }
}
