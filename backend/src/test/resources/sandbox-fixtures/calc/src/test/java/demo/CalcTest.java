package demo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CalcTest {

    @Test
    void dividesNormally() {
        assertEquals(3, new Calc().safeDiv(6, 2));
    }

    @Test
    void divisionByZeroReturnsZero() {
        assertEquals(0, new Calc().safeDiv(6, 0));
    }
}
