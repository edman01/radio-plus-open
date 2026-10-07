package fi.radioplus.app;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ReglinkTuningRulesTest {
    @Test public void nativeBandsAreExactLowercaseAndFmHasNoBanks() {
        assertEquals(0, ReglinkTuningRules.appBand("fm"));
        assertEquals(3, ReglinkTuningRules.appBand("am"));
        for (String band : new String[]{null, "", "FM", "AM", "fm2", "am2", "radio"}) {
            assertEquals(-1, ReglinkTuningRules.appBand(band));
        }
        assertEquals("fm", ReglinkTuningRules.nativeBand(0));
        assertEquals("fm", ReglinkTuningRules.nativeBand(1));
        assertEquals("fm", ReglinkTuningRules.nativeBand(2));
        assertEquals("am", ReglinkTuningRules.nativeBand(3));
        assertEquals("", ReglinkTuningRules.nativeBand(4));
        assertEquals("", ReglinkTuningRules.nativeBand(-1));
    }

    @Test public void targetUnitsAreTenKhzForFmAndKhzForAm() {
        assertEquals(9810, ReglinkTuningRules.rawFrequency(0, 98100));
        assertEquals(10800, ReglinkTuningRules.rawFrequency(2, 108000));
        assertEquals(531, ReglinkTuningRules.rawFrequency(3, 531));
        assertEquals(1602, ReglinkTuningRules.rawFrequency(3, 1602));
    }

    @Test public void targetsRejectZeroMagicValuesAndUnauditedAmEdges() {
        for (int frequency : new int[]{0, -1, 87400, 98150, 108100, Integer.MAX_VALUE}) {
            assertFalse(ReglinkTuningRules.validTarget(0, frequency));
            assertEquals(-1, ReglinkTuningRules.rawFrequency(0, frequency));
        }
        for (int frequency : new int[]{0, 522, 532, 1611, 1620, Integer.MIN_VALUE}) {
            assertFalse(ReglinkTuningRules.validTarget(3, frequency));
        }
        assertFalse(ReglinkTuningRules.validTarget(-1, 98100));
        assertFalse(ReglinkTuningRules.validTarget(4, 98100));
    }

    @Test public void observedFmAllowsNative50KhzButNeverGuessesOtherUnits() {
        assertEquals(98150, ReglinkTuningRules.observedKhz("fm", 9815));
        assertEquals(87500, ReglinkTuningRules.observedKhz("fm", 8750));
        assertEquals(108000, ReglinkTuningRules.observedKhz("fm", 10800));
        for (int frequency : new int[]{981, 98100, 98150, 9811, 8745, 10805, Integer.MAX_VALUE}) {
            assertEquals(-1, ReglinkTuningRules.observedKhz("fm", frequency));
        }
    }

    @Test public void observedAmAndUnknownBandsFailClosed() {
        assertEquals(999, ReglinkTuningRules.observedKhz("am", 999));
        assertEquals(-1, ReglinkTuningRules.observedKhz("am", 1000));
        assertEquals(-1, ReglinkTuningRules.observedKhz("am", 9990));
        assertEquals(-1, ReglinkTuningRules.observedKhz(null, 9810));
        assertEquals(-1, ReglinkTuningRules.observedKhz("FM", 9810));
    }
}
