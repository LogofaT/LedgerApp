package ledger.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MoneyTest {
    @Test void acceptsCurrencySpecificDecimalPrecision() {
        assertEquals("12.34",Money.format(Money.parse("12.34","EUR","amount",true),"EUR"));
        assertEquals("900",Money.format(Money.parse("900","JPY","amount",true),"JPY"));
    }

    @Test void rejectsNumericSyntaxThatCouldRoundOrChangeScale() {
        for(String invalid:new String[]{"1e2","01.00","1.001","-1","1."}) {
            assertThrows(LedgerFailure.class,()->Money.parse(invalid,"USD","amount",true),invalid);
        }
        assertThrows(LedgerFailure.class,()->Money.parse("1.0","JPY","amount",true));
    }

    @Test void distinguishesInvalidIsoFromValidUnsupportedCurrency() {
        LedgerFailure invalid=assertThrows(LedgerFailure.class,()->Money.scale("ZZZ"));
        LedgerFailure unsupported=assertThrows(LedgerFailure.class,()->Money.scale("GBP"));
        assertEquals("INVALID_CURRENCY_CODE",invalid.code());
        assertEquals("UNSUPPORTED_CURRENCY",unsupported.code());
    }

    @Test void enforcesPositivePostingAmountsAndFiniteStorageBounds() {
        assertThrows(LedgerFailure.class,()->Money.parse("0","USD","amount",true));
        assertEquals("999999999999999999.99",
                Money.format(Money.parse("999999999999999999.99","EUR","amount",true),"EUR"));
        assertThrows(LedgerFailure.class,()->Money.parse("1000000000000000000","EUR","amount",true));
        assertFalse(Money.withinStorage(new java.math.BigDecimal("1000000000000000000.00")));
        assertTrue(Money.withinStorage(new java.math.BigDecimal("-999999999999999999.99")));
        assertFalse(Money.withinStorage(new java.math.BigDecimal("-1000000000000000000.00")));
    }
}
