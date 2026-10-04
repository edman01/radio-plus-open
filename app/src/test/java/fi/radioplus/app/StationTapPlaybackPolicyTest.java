package fi.radioplus.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class StationTapPlaybackPolicyTest {
    @Test
    public void activePlayingStationPausesOnSecondTap() {
        assertTrue(StationTapPlaybackPolicy.shouldPause("0:98100", "0:98100", true));
    }

    @Test
    public void stoppedStationStartsAgainInsteadOfPausing() {
        assertFalse(StationTapPlaybackPolicy.shouldPause("0:98100", "0:98100", false));
    }

    @Test
    public void differentStationTunesNormally() {
        assertFalse(StationTapPlaybackPolicy.shouldPause("0:99900", "0:98100", true));
    }
}
