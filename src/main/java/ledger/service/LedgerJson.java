package ledger.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The ledger's persisted JSON representation and strict HTTP request shapes.
 * Only JSON syntax errors receive the raw-body identity fallback. A body must be
 * exactly one JSON value: trailing tokens after it are a syntax error.
 */
final class LedgerJson {
    private final ObjectMapper json;
    /** Strict reader derived from, never mutating, the shared mapper. */
    private final ObjectReader strict;

    LedgerJson(ObjectMapper json) {
        this.json = json;
        this.strict = json.reader(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    String canonicalPayload(String body) {
        if (body == null) {
            return "null";
        }
        JsonNode tree;
        try {
            tree = parse(body);
        } catch (JsonProcessingException malformed) {
            return "RAW:" + body;
        }
        return tree == null ? "null" : write(sorted(tree));
    }

    JsonNode requestObject(String body) {
        if (body == null) {
            return null;
        }
        JsonNode root;
        try {
            root = parse(body);
        } catch (JsonProcessingException malformed) {
            throw new LedgerFailure(400, "MALFORMED_REQUEST", "Request body is not valid JSON", null);
        }
        if (root == null || root.isNull()) {
            return null;
        }
        if (!root.isObject()) {
            throw new LedgerFailure(400, "INVALID_REQUEST", "Request body must be a JSON object", null);
        }
        return root;
    }

    void allowedFields(JsonNode node, Set<String> allowed, String prefix) {
        var names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw new LedgerFailure(400, "UNKNOWN_FIELD", "Field is not supported", path(prefix, name));
            }
        }
    }

    String textField(JsonNode node, String name, String prefix) {
        JsonNode value = node.get(name);
        if (value == null) {
            return null;
        }
        if (!value.isTextual()) {
            throw new LedgerFailure(400, "INVALID_FIELD_TYPE", "Field must be a JSON string", path(prefix, name));
        }
        return value.textValue();
    }

    String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot serialize ledger JSON", failure);
        }
    }

    static String fingerprint(String identity) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    /** Exactly one JSON value; empty content is null, as with ObjectMapper.readTree. */
    private JsonNode parse(String body) throws JsonProcessingException {
        JsonNode tree = strict.readTree(body);
        return tree == null || tree.isMissingNode() ? null : tree;
    }

    private String path(String prefix, String name) {
        return prefix == null ? name : prefix + "." + name;
    }

    private JsonNode sorted(JsonNode source) {
        if (source.isObject()) {
            ObjectNode result = json.createObjectNode();
            List<String> names = new ArrayList<>();
            source.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            for (String name : names) {
                result.set(name, sorted(source.get(name)));
            }
            return result;
        }
        if (source.isArray()) {
            ArrayNode result = json.createArrayNode();
            source.forEach(item -> result.add(sorted(item)));
            return result;
        }
        return source.deepCopy();
    }
}
