package ledger.service;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Set;
import java.util.regex.Pattern;

public final class Money {
    private static final Pattern DECIMAL = Pattern.compile("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?");
    private static final Set<String> SUPPORTED = Set.of("EUR", "USD", "JPY");
    private static final BigDecimal MAX = new BigDecimal("999999999999999999.99");
    private Money() {}

    public static int scale(String currency) {
        if (currency == null || !currency.matches("[A-Z]{3}"))
            throw new LedgerFailure(400, "INVALID_CURRENCY_CODE", "Currency must be an uppercase ISO 4217 code", "currency");
        try { Currency.getInstance(currency); }
        catch (IllegalArgumentException ex) {
            throw new LedgerFailure(400, "INVALID_CURRENCY_CODE", "Currency is not a valid ISO 4217 code", "currency");
        }
        if (!SUPPORTED.contains(currency))
            throw new LedgerFailure(422, "UNSUPPORTED_CURRENCY", "Currency is valid but unsupported", "currency");
        return currency.equals("JPY") ? 0 : 2;
    }

    public static BigDecimal parse(String text, String currency, String field, boolean positive) {
        int scale = scale(currency);
        if (text == null || text.length() > 24 || !DECIMAL.matcher(text).matches())
            throw new LedgerFailure(400, "INVALID_AMOUNT", "Amount must be a plain decimal JSON string", field);
        BigDecimal value;
        try { value = new BigDecimal(text); }
        catch (NumberFormatException ex) { throw new LedgerFailure(400, "INVALID_AMOUNT", "Amount is invalid", field); }
        if (value.scale() > scale)
            throw new LedgerFailure(422, "CURRENCY_SCALE_EXCEEDED", "Amount exceeds currency precision", field);
        if (positive && value.signum() <= 0)
            throw new LedgerFailure(422, "AMOUNT_MUST_BE_POSITIVE", "Amount must be greater than zero", field);
        if (!positive && value.signum() < 0)
            throw new LedgerFailure(422, "NEGATIVE_AMOUNT", "Amount cannot be negative", field);
        if (value.compareTo(MAX) > 0)
            throw new LedgerFailure(422, "AMOUNT_OUT_OF_RANGE", "Amount exceeds ledger storage bounds", field);
        return value.setScale(scale);
    }

    public static String format(BigDecimal value, String currency) {
        return value.setScale(scale(currency)).toPlainString();
    }

    public static boolean withinStorage(BigDecimal value) {
        return value.abs().compareTo(MAX) <= 0;
    }
}
