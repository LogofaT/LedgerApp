package ledger.api;

import ledger.api.Models.*;
import ledger.service.LedgerService;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1")
public class LedgerController {
    private final LedgerService ledger;
    public LedgerController(LedgerService ledger) { this.ledger=ledger; }

    @PostMapping("/accounts")
    public ResponseEntity<?> openAccount(@RequestHeader(value="Idempotency-Key",required=false) String key,
                                         @RequestHeader(value="X-Correlation-Id",required=false) String correlation,
                                         @RequestBody(required=false) byte[] request) {
        return response(ledger.openAccount(key,decode(request),correlation));
    }

    @GetMapping("/accounts/{accountId}")
    public AccountView account(@PathVariable String accountId) { return ledger.account(accountId); }

    @PostMapping("/transactions")
    public ResponseEntity<?> createTransaction(@RequestHeader(value="Idempotency-Key",required=false) String key,
                                               @RequestHeader(value="X-Correlation-Id",required=false) String correlation,
                                               @RequestBody(required=false) byte[] request) {
        return response(ledger.createTransaction(key,decode(request),correlation));
    }

    @GetMapping("/transactions/{transactionId}")
    public TransactionView transaction(@PathVariable String transactionId) { return ledger.transaction(transactionId); }

    @PostMapping("/transactions/{transactionId}/reversals")
    public ResponseEntity<?> reverse(@PathVariable String transactionId,
                                     @RequestHeader(value="Idempotency-Key",required=false) String key,
                                     @RequestHeader(value="X-Correlation-Id",required=false) String correlation) {
        return response(ledger.reverse(key,transactionId,correlation));
    }

    private ResponseEntity<?> response(CommandResult<?> result) {
        ResponseEntity.BodyBuilder builder=ResponseEntity.status(result.status());
        if(result.location()!=null) builder.location(URI.create(result.location()));
        MediaType type=result.status()>=400?MediaType.valueOf("application/problem+json"):MediaType.APPLICATION_JSON;
        builder.contentType(type);
        if(result.rawBody()!=null)return builder.body(result.rawBody().getBytes(StandardCharsets.UTF_8));
        return builder.body(result.body());
    }
    private String decode(byte[] body) { return body==null?null:new String(body,StandardCharsets.UTF_8); }
}
