package ledger.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import ledger.persistence.LedgerRepository;
import ledger.service.LedgerFailure;
import ledger.service.LedgerService;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpenApiContractTest {
    private final Map<?, ?> contract = readContract();

    @Test
    void headersMatchRuntimeValidation() {
        LedgerService ledger = new LedgerService(mock(LedgerRepository.class), new ObjectMapper(), 100);
        String keyPattern = text("components", "parameters", "IdempotencyKey", "schema", "pattern");
        for (String key : List.of("a", "A:1._-", "bad key", "bad/key", "")) {
            boolean accepted = Pattern.matches(keyPattern, key) && !key.isEmpty();
            if (accepted) {
                assertDoesNotThrow(() -> ledger.openAccount(key, "{}", null));
            } else {
                assertEquals("INVALID_IDEMPOTENCY_KEY", assertThrows(LedgerFailure.class,
                        () -> ledger.openAccount(key, "{}", null)).code());
            }
        }
        assertEquals(100, at("components", "parameters", "IdempotencyKey", "schema", "maxLength"));
        assertEquals(keyPattern, text("components", "parameters", "CorrelationId", "schema", "pattern"));
        assertEquals(1, at("components", "parameters", "CorrelationId", "schema", "minLength"));
        assertEquals(80, at("components", "parameters", "CorrelationId", "schema", "maxLength"));
    }

    @Test
    void accountSchemaMatchesIdentifierRulesAndOptionalOverdraft() {
        LedgerRepository repository = mock(LedgerRepository.class);
        LedgerService ledger = new LedgerService(repository, new ObjectMapper(), 100);
        String idPattern = text("components", "schemas", "OpenAccount", "properties", "accountId", "pattern");
        for (String id : List.of("a", "A-1._", "bad:id", "bad id", "_bad", "a".repeat(65))) {
            when(repository.findAccount(id)).thenReturn(Optional.empty(), Optional.of(
                    new LedgerRepository.AccountData(id, "EUR", new BigDecimal("0.00"),
                            new BigDecimal("0.00"), false)));
            var result = ledger.openAccount("key-" + id.length(), "{\"accountId\":\"" + id
                    + "\",\"currency\":\"EUR\"}", null);
            assertEquals(Pattern.matches(idPattern, id) ? 201 : 400, result.status(), id);
        }
        assertEquals(List.of("accountId", "currency"), at("components", "schemas", "OpenAccount", "required"));
        assertEquals("0", text("components", "schemas", "OpenAccount", "properties", "overdraftLimit", "default"));
        var nullLimit = ledger.openAccount("null-limit",
                "{\"accountId\":\"a\",\"currency\":\"EUR\",\"overdraftLimit\":null}", null);
        assertEquals(400, nullLimit.status());
        assertEquals("INVALID_FIELD_TYPE", ((Models.ProblemView) nullLimit.body()).code());
    }

    @Test
    void requestShapesRejectUnknownPropertiesAndDocumentImplementedErrors() {
        assertEquals(false, at("components", "schemas", "OpenAccount", "additionalProperties"));
        assertEquals(false, at("components", "schemas", "CreateTransaction", "additionalProperties"));
        assertEquals(false, at("components", "schemas", "CreateTransaction", "properties", "entries", "items",
                "additionalProperties"));
        for (String status : List.of("400", "404", "409", "413", "422", "default")) {
            assertEquals("#/components/responses/Problem",
                    text("paths", "/api/v1/transactions", "post", "responses", status, "$ref"));
        }
        for (String status : List.of("400", "413", "default")) {
            assertNotNull(at("paths", "/api/v1/accounts", "post", "responses", status));
            assertNotNull(at("paths", "/api/v1/transactions/{transactionId}/reversals", "post", "responses", status));
        }
    }

    private Object at(String... path) {
        Object value = contract;
        for (String part : path) {
            assertInstanceOf(Map.class, value);
            value = ((Map<?, ?>) value).get(part);
            assertNotNull(value, "Missing contract field: " + String.join(".", path));
        }
        return value;
    }

    private String text(String... path) {
        return assertInstanceOf(String.class, at(path));
    }

    private static Map<?, ?> readContract() {
        try (InputStream input = OpenApiContractTest.class.getResourceAsStream("/static/openapi.yaml")) {
            assertNotNull(input);
            return assertInstanceOf(Map.class, new Yaml().load(input));
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read API contract", failure);
        }
    }
}
