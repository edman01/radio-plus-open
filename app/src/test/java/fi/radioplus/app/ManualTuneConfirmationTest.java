package fi.radioplus.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ManualTuneConfirmationTest {
    @Test
    public void confirmedFrequencyCanBeSaved() {
        assertTrue(ManualTuneConfirmation.isConfirmed(0, 101_700, 0, 101_700, false));
    }

    @Test
    public void staleOrStillSeekingFrequencyCannotBeSaved() {
        assertFalse(ManualTuneConfirmation.isConfirmed(0, 101_700, 0, 101_600, false));
        assertFalse(ManualTuneConfirmation.isConfirmed(0, 101_700, 0, 101_700, true));
        assertFalse(ManualTuneConfirmation.isConfirmed(0, -1, 0, 101_700, false));
    }

    @Test
    public void frequencyOnAnotherBandCannotBeSaved() {
        assertFalse(ManualTuneConfirmation.isConfirmed(0, 999, 3, 999, false));
    }
}
