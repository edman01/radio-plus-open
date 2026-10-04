package fi.radioplus.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class FrequencyRulesTest {
    @Test
    public void europeanFmAndAmRangesUseTunerStepGrid() {
        assertTrue(FrequencyRules.isValid(0, 87_500));
        assertTrue(FrequencyRules.isValid(2, 108_000));
        assertFalse(FrequencyRules.isValid(0, 87_550));
        assertFalse(FrequencyRules.isValid(0, 108_100));

        assertTrue(FrequencyRules.isValid(3, 522));
        assertTrue(FrequencyRules.isValid(3, 1_620));
        assertFalse(FrequencyRules.isValid(3, 523));
        assertFalse(FrequencyRules.isValid(3, 1_621));
    }

    @Test
    public void duplicateRealPresetIsKeptButRepeatedPaddingIsRemoved() {
        assertArrayEquals(
                new int[]{101_700},
                RadioServiceClient.sanitizePresets(
                        0,
                        new int[]{87_500, 87_500, 101_700, 101_700}
                )
        );
    }

    @Test
    public void invalidBandsAndFrequenciesAreRejected() {
        assertFalse(FrequencyRules.isValid(-1, 101_700));
        assertFalse(FrequencyRules.isValid(4, 101_700));
        assertFalse(FrequencyRules.isValid(3, 101_700));
    }

    @Test
    public void stepsFmOnTheExactHundredKilohertzGrid() {
        assertEquals(101_800, FrequencyRules.stepFrom(0, 101_700, 1));
        assertEquals(101_600, FrequencyRules.stepFrom(0, 101_700, -1));
        assertEquals(101_800, FrequencyRules.stepFrom(0, 101_750, 1));
        assertEquals(101_700, FrequencyRules.stepFrom(0, 101_750, -1));
    }

    @Test
    public void stepsAmOnTheNineKilohertzGridAndWrapsBounds() {
        assertEquals(1_008, FrequencyRules.stepFrom(3, 999, 1));
        assertEquals(990, FrequencyRules.stepFrom(3, 999, -1));
        assertEquals(FrequencyRules.FM_MIN, FrequencyRules.stepFrom(0, 108_000, 1));
        assertEquals(FrequencyRules.FM_MAX, FrequencyRules.stepFrom(0, 87_500, -1));
        assertEquals(FrequencyRules.AM_MIN, FrequencyRules.stepFrom(3, 1_620, 1));
        assertEquals(FrequencyRules.AM_MAX, FrequencyRules.stepFrom(3, 522, -1));
    }
}
