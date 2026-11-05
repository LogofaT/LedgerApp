package ledger.api;

import ledger.api.Models.ProblemView;
import ledger.service.LedgerFailure;

/** Identical problem representation for receipted and directly returned refusals. */
public final class Problems {
    private Problems() {}

    public static ProblemView from(LedgerFailure failure, String correlationId) {
        return create(failure.status(), failure.code(), failure.getMessage(), failure.field(), correlationId);
    }

    public static ProblemView create(int status, String code, String detail, String field, String correlationId) {
        return new ProblemView("about:blank", status >= 500 ? "Internal error" : "Request refused",
                status, detail, code, field, correlationId);
    }
}
