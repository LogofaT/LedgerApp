package ledger.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import ledger.persistence.LedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LedgerRequestsTest {
    private LedgerRepository db;
    private LedgerRequests requests;
    private LedgerJson json;

    @BeforeEach
    void setUp() {
        db = mock(LedgerRepository.class);
        json = new LedgerJson(new ObjectMapper());
        requests = new LedgerRequests(json, db, 3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"7", "true", "null", "[]", "{}"})
    void accountMoneyRequiresJsonString(String value) {
        refused(() -> requests.account(accountBody("EUR", value)), 400, "INVALID_FIELD_TYPE", "overdraftLimit");
        verifyNoInteractions(db);
    }

    @ParameterizedTest
    @ValueSource(strings = {"7", "true", "null", "[]", "{}"})
    void nestedMoneyRequiresJsonString(String value) {
        refused(() -> requests.transaction(transactionBody("EUR", "\"test\"",
                entry("a", "DEBIT", value) + "," + entry("b", "CREDIT", "\"7\""))),
                400, "INVALID_FIELD_TYPE", "entries[0].amount");
        verifyNoInteractions(db);
    }

    @ParameterizedTest
    @ValueSource(strings = {"7", "true", "\"entries\"", "{}"})
    void entriesMustBeArray(String value) {
        refused(() -> requests.transaction("{\"entries\":" + value + "}"),
                400, "INVALID_FIELD_TYPE", "entries");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "7", "true", "\"entry\"", "[]"})
    void entriesMustContainObjects(String value) {
        refused(() -> requests.transaction("{\"entries\":[" + value + "]}"),
                400, "INVALID_ENTRY", "entries[0]");
    }

    @Test
    void unknownFieldsAreRejectedAtExactPathBeforeValueChecks() {
        refused(() -> requests.account("{\"currency\":7,\"extra\":{\"nested\":true}}"),
                400, "UNKNOWN_FIELD", "extra");
        refused(() -> requests.transaction("{\"currency\":false,\"entries\":[],\"extra\":1}"),
                400, "UNKNOWN_FIELD", "extra");
        refused(() -> requests.transaction("{\"currency\":false,\"entries\":[{\"extra\":{\"nested\":1}}]}"),
                400, "UNKNOWN_FIELD", "entries[0].extra");
        verifyNoInteractions(db);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{\"accountId\":", "{\"entries\":[}"})
    void malformedJsonIsDistinctFromMissingOrWrongRoot(String body) {
        refused(() -> requests.account(body), 400, "MALFORMED_REQUEST", null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "true", "7", "\"body\""})
    void rootMustBeObject(String body) {
        refused(() -> requests.transaction(body), 400, "INVALID_REQUEST", null);
    }

    @Test
    void missingNullAndEmptyBodyAreRequiredButExplicitNullFieldsAreTypeErrors() {
        for (String body : new String[] {null, "", "null"}) {
            refused(() -> requests.account(body), 400, "INVALID_REQUEST", null);
        }
        refused(() -> requests.account("{\"accountId\":null}"), 400, "INVALID_FIELD_TYPE", "accountId");
        refused(() -> requests.account("{}"), 400, "INVALID_FIELD", "accountId");
        refused(() -> requests.transaction("{\"currency\":\"EUR\",\"description\":\"x\",\"entries\":null}"),
                422, "TOO_FEW_ENTRIES", "entries");
    }

    @Test
    void structuralParsingOfAllEntriesPrecedesTopLevelSemanticValidation() {
        refused(() -> requests.transaction(transactionBody("bad", "\"\"", entry("a", "DEBIT", "\"1\"")
                        + "," + entry("b", "CREDIT", "2"))),
                400, "INVALID_FIELD_TYPE", "entries[1].amount");
        refused(() -> requests.transaction("{\"currency\":7,\"description\":false,\"entries\":[null]}"),
                400, "INVALID_ENTRY", "entries[0]");
        verifyNoInteractions(db);
    }

    @Test
    void semanticValidationOrderIsCurrencyDescriptionCountThenEntries() {
        refused(() -> requests.transaction(transactionBody("bad", "\"\"", "")),
                400, "INVALID_CURRENCY_CODE", "currency");
        refused(() -> requests.transaction(transactionBody("EUR", "\"\"", "")),
                400, "INVALID_FIELD", "description");
        refused(() -> requests.transaction(transactionBody("EUR", "\"test\"", entry("", "bad", "\"0\""))),
                422, "TOO_FEW_ENTRIES", "entries");
        refused(() -> requests.transaction(transactionBody("EUR", "\"test\"",
                        (entry("", "bad", "\"0\"") + ",").repeat(3) + entry("", "bad", "\"0\""))),
                413, "TOO_MANY_ENTRIES", "entries");
        verifyNoInteractions(db);
    }

    @Test
    void entryValidationKeepsAccountLookupsBetweenSemanticChecks() {
        String invalidSecond = entry("", "bad", "\"0\"");
        refused(() -> requests.transaction(transactionBody("EUR", "\"test\"",
                        entry("missing", "DEBIT", "\"1\"") + "," + invalidSecond)),
                404, "ACCOUNT_NOT_FOUND", "entries[0].accountId");
        when(db.findAccount("usd")).thenReturn(Optional.of(
                new LedgerRepository.AccountData("usd", "USD", BigDecimal.ZERO, BigDecimal.ZERO, false)));
        refused(() -> requests.transaction(transactionBody("EUR", "\"test\"",
                        entry("usd", "DEBIT", "\"1\"") + "," + invalidSecond)),
                422, "CURRENCY_MISMATCH", "entries[0].accountId");
    }

    @Test
    void accountSemanticOrderRemainsIdCurrencyLimitReservedAndPattern() {
        refused(() -> requests.account("{\"currency\":\"bad\",\"overdraftLimit\":\"bad\"}"),
                400, "INVALID_FIELD", "accountId");
        refused(() -> requests.account("{\"accountId\":\"SETTLEMENT-EUR\",\"currency\":\"bad\",\"overdraftLimit\":\"bad\"}"),
                400, "INVALID_CURRENCY_CODE", "currency");
        refused(() -> requests.account("{\"accountId\":\"SETTLEMENT-EUR\",\"currency\":\"EUR\",\"overdraftLimit\":\"bad\"}"),
                400, "INVALID_AMOUNT", "overdraftLimit");
        refused(() -> requests.account("{\"accountId\":\"SETTLEMENT-EUR\",\"currency\":\"EUR\",\"overdraftLimit\":\"0\"}"),
                422, "RESERVED_ACCOUNT_ID", "accountId");
        refused(() -> requests.account("{\"accountId\":\"bad:id\",\"currency\":\"EUR\",\"overdraftLimit\":\"0\"}"),
                400, "INVALID_ACCOUNT_ID", "accountId");
    }

    @ParameterizedTest
    @CsvSource({"EUR,7,7.00", "USD,7.1,7.10", "JPY,7,7"})
    void typedAccountInputRetainsExactCurrencyScale(String currency, String amount, String expected) {
        var input = requests.account(accountBody(currency, "\"" + amount + "\""));
        assertEquals(new BigDecimal(expected), input.overdraftLimit());
        assertEquals("a", input.accountId());
        assertEquals(currency, input.currency());
    }

    @ParameterizedTest
    @CsvSource({"EUR,0.00", "USD,0.00", "JPY,0"})
    void omittedOverdraftDefaultsToCurrencyScaledZero(String currency, String expected) {
        var input = requests.account("{\"accountId\":\"a\",\"currency\":\"" + currency + "\"}");
        assertEquals(new BigDecimal(expected), input.overdraftLimit());
        verifyNoInteractions(db);
    }

    @Test
    void canonicalIdentitySortsObjectsRecursivelyButPreservesArraysAndStringMoney() {
        assertEquals("{\"a\":[{\"x\":2,\"y\":1},1],\"z\":\"7.00\"}",
                json.canonicalPayload(" {\"z\":\"7.00\",\"a\":[{\"y\":1,\"x\":2},1]} "));
        assertNotEquals(json.canonicalPayload("{\"amount\":\"7\"}"), json.canonicalPayload("{\"amount\":\"7.00\"}"));
        assertNotEquals(json.canonicalPayload("[1,2]"), json.canonicalPayload("[2,1]"));
        assertEquals("null", json.canonicalPayload(null));
        assertEquals("null", json.canonicalPayload(""));
        assertEquals("null", json.canonicalPayload("null"));
        assertEquals("RAW:{", json.canonicalPayload("{"));
        assertEquals("RAW: {", json.canonicalPayload(" {"));
    }

    @Test
    void unexpectedParserFailurePropagatesRatherThanBecomingMalformedOrRawIdentity() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        ObjectReader reader = mock(ObjectReader.class);
        IllegalStateException failure = new IllegalStateException("mapper unavailable");
        when(mapper.reader(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)).thenReturn(reader);
        when(reader.readTree(anyString())).thenThrow(failure);
        LedgerJson codec = new LedgerJson(mapper);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> codec.canonicalPayload("{}")));
        assertSame(failure, assertThrows(IllegalStateException.class, () -> codec.requestObject("{}")));
    }

    @ParameterizedTest
    @ValueSource(strings = {" {\"extra\":true}", "{\"extra\":true}", " 7", " \"x\"", " null", " [1]", " garbage", " }", " {"})
    void trailingContentAfterOneJsonValueIsMalformedWithDistinctRawIdentity(String trailing) {
        String clean = "{\"accountId\":\"a\"}";
        String body = clean + trailing;
        assertEquals("RAW:" + body, json.canonicalPayload(body));
        assertNotEquals(LedgerJson.fingerprint(json.canonicalPayload(clean)),
                LedgerJson.fingerprint(json.canonicalPayload(body)));
        refused(() -> json.requestObject(body), 400, "MALFORMED_REQUEST", null);
        refused(() -> requests.account(body), 400, "MALFORMED_REQUEST", null);
        verifyNoInteractions(db);
    }

    @Test
    void trailingContentAfterScalarOrArrayIsMalformed() {
        assertEquals("RAW:7 8", json.canonicalPayload("7 8"));
        assertEquals("RAW:[1] [2]", json.canonicalPayload("[1] [2]"));
        assertEquals("RAW:null null", json.canonicalPayload("null null"));
        refused(() -> json.requestObject("null {}"), 400, "MALFORMED_REQUEST", null);
    }

    @Test
    void singleJsonValueSurroundedByWhitespaceIsAccepted() {
        String body = " \r\n\t{\"b\":\"2\",\"accountId\":\"a\"} \r\n\t";
        assertEquals("{\"accountId\":\"a\",\"b\":\"2\"}", json.canonicalPayload(body));
        assertEquals("a", json.requestObject(body).get("accountId").textValue());
        assertNull(json.requestObject("  "));
        assertNull(json.requestObject(" null "));
        assertEquals("null", json.canonicalPayload("  "));
    }

    @Test
    void strictParsingDoesNotMutateSharedMapper() {
        ObjectMapper mapper = new ObjectMapper();
        LedgerJson codec = new LedgerJson(mapper);
        assertFalse(mapper.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS));
        assertEquals("RAW:{} {}", codec.canonicalPayload("{} {}"));
        assertFalse(mapper.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS));
    }
    private String accountBody(String currency, String amount) {
        return "{\"accountId\":\"a\",\"currency\":\"" + currency + "\",\"overdraftLimit\":" + amount + "}";
    }

    private String transactionBody(String currency, String description, String entries) {
        return "{\"currency\":\"" + currency + "\",\"description\":" + description + ",\"entries\":[" + entries + "]}";
    }

    private String entry(String account, String direction, String amount) {
        return "{\"accountId\":\"" + account + "\",\"direction\":\"" + direction + "\",\"amount\":" + amount + "}";
    }

    private void refused(Runnable action, int status, String code, String field) {
        LedgerFailure failure = assertThrows(LedgerFailure.class, action::run);
        assertAll(() -> assertEquals(status, failure.status()), () -> assertEquals(code, failure.code()),
                () -> assertEquals(field, failure.field()));
    }
}
