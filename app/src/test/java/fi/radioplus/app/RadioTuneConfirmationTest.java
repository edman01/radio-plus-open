package fi.radioplus.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RadioTuneConfirmationTest {
    @Test
    public void acceptsOnlyTheRequestedBandAndFrequency() {
        assertTrue(RadioTuneConfirmation.matches(0, 101700, 0, 101700));
        assertFalse(RadioTuneConfirmation.matches(0, 101700, 0, 98100));
        assertFalse(RadioTuneConfirmation.matches(0, 101700, 1, 101700));
    }
}
