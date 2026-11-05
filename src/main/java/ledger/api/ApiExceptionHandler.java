package ledger.api;

import ledger.api.Models.ProblemView;
import ledger.service.LedgerFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.ErrorResponse;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(LedgerFailure.class)
    ResponseEntity<ProblemView> ledger(LedgerFailure ex) {
        return response(Problems.from(ex, MDC.get("correlationId")));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    ResponseEntity<ProblemView> malformed(Exception ex) {
        return response(Problems.create(400, "MALFORMED_REQUEST",
                "Request is malformed or has an invalid field", null, MDC.get("correlationId")));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemView> unexpected(Exception ex) {
        if (ex instanceof ErrorResponse error) {
            int status = error.getStatusCode().value();
            HttpStatus httpStatus = HttpStatus.resolve(status);
            String detail = httpStatus == null ? "HTTP request failed" : httpStatus.getReasonPhrase();
            return ResponseEntity.status(status).headers(error.getHeaders())
                    .header("Content-Type", "application/problem+json")
                    .body(Problems.create(status, "HTTP_" + status, detail, null, MDC.get("correlationId")));
        }
        // Explicit safe event, like the messaging logs: no exception message,
        // stack trace, request URI, SQL, financial payload, or credentials.
        log.error("ledger_api_unexpected_failure reason={}", ex.getClass().getSimpleName());
        return response(Problems.create(500, "INTERNAL_ERROR",
                "An internal error occurred", null, MDC.get("correlationId")));
    }

    private ResponseEntity<ProblemView> response(ProblemView body) {
        return ResponseEntity.status(body.status()).header("Content-Type", "application/problem+json").body(body);
    }
}
