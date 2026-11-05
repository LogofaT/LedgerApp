package ledger.service;

public class LedgerFailure extends RuntimeException {
    private final int status;
    private final String code;
    private final String field;
    public LedgerFailure(int status, String code, String message, String field) {
        super(message);
        this.status = status;
        this.code = code;
        this.field = field;
    }
    public int status() { return status; }
    public String code() { return code; }
    public String field() { return field; }
}
