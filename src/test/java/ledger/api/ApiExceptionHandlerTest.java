package ledger.api;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ledger.service.LedgerFailure;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.*;

class ApiExceptionHandlerTest {
    @Test
    void businessFailuresUseTheSameFactoryAsReceipts() {
        LedgerFailure failure = new LedgerFailure(422, "OVERDRAFT_EXCEEDED", "Refused", "a");
        MDC.put("correlationId", "safe-correlation");
        try {
            var response = new ApiExceptionHandler().ledger(failure);
            assertEquals(Problems.from(failure, "safe-correlation"), response.getBody());
            assertEquals(422, response.getStatusCode().value());
            assertEquals("application/problem+json", response.getHeaders().getFirst("Content-Type"));
        } finally {
            MDC.remove("correlationId");
        }
    }

    @Test
    void unexpectedFailureIsSanitizedAndExplicitlyLoggedWithoutMessageOrStackTrace() {
        Logger logger = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            var response = new ApiExceptionHandler().unexpected(
                    new IllegalStateException("sensitive financial payload and credentials"));
            assertEquals(500, response.getStatusCode().value());
            assertNotNull(response.getBody());
            assertEquals("INTERNAL_ERROR", response.getBody().code());
            assertEquals("An internal error occurred", response.getBody().detail());
            assertEquals(1, events.list.size());
            ILoggingEvent event = events.list.get(0);
            assertEquals("ledger_api_unexpected_failure reason=IllegalStateException", event.getFormattedMessage());
            assertNull(event.getThrowableProxy(), "Stack traces can contain SQL, payloads and credentials");
            assertFalse(response.getBody().toString().contains("sensitive"));
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }
}
