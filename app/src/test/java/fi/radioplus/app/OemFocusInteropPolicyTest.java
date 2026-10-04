package fi.radioplus.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class OemFocusInteropPolicyTest {
    @Test
    public void tuningTakeoverCoversEveryOwnershipAndHealthCombination() {
        for (int flags = 0; flags < 32; flags++) {
            boolean activeInPlaybackEpoch = (flags & 1) != 0;
            boolean routeActive = (flags & 2) != 0;
            boolean focusReleasedByRadioPlus = (flags & 4) != 0;
            boolean externalSourceObserved = (flags & 8) != 0;
            boolean mutedObserved = (flags & 16) != 0;
            // Only bits 0+1, with no adverse observations, describe a healthy
            // route already activated in this playback epoch.
            assertEquals("takeover flags=" + Integer.toBinaryString(flags), flags != 3,
                    OemFocusInteropPolicy.shouldTakeOverForTuning(
                            activeInPlaybackEpoch, routeActive, focusReleasedByRadioPlus,
                            externalSourceObserved, mutedObserved));
        }
    }

    @Test
    public void unknownHealthDoesNotRepeatTakeoverForAnAlreadyActiveEpoch() {
        // The caller maps unavailable source/mute information to no observation,
        // just as it maps a known radio-owned, unmuted source. Neither is loss.
        boolean externalSourceObserved = false;
        boolean mutedObserved = false;
        assertFalse(OemFocusInteropPolicy.shouldTakeOverForTuning(
                true, true, false, externalSourceObserved, mutedObserved));
        assertTrue("A new playback epoch must still activate even with unknown health",
                OemFocusInteropPolicy.shouldTakeOverForTuning(
                        false, true, false, externalSourceObserved, mutedObserved));
    }

    @Test
    public void firstActivationReclaimsPossiblyStaleVendorFocus() {
        assertTrue(OemFocusInteropPolicy.shouldRequestFocus(
                true, false, false, false, false, false));
        assertTrue(OemFocusInteropPolicy.shouldRequestFocus(
                true, false, true, true, true, false));
        assertFalse(OemFocusInteropPolicy.shouldRequestFocus(
                false, false, true, true, true, false));
    }

    @Test
    public void explicitPauseAndSourceChangeCanResumeFocus() {
        assertTrue(OemFocusInteropPolicy.shouldRequestFocus(
                false, true, true, true, true, false));
        assertTrue(OemFocusInteropPolicy.shouldRequestFocus(
                false, false, true, false, true, false));
        assertTrue(OemFocusInteropPolicy.shouldRequestFocus(
                true, false, true, false, true, false));
    }

    @Test
    public void explicitReleaseWithinSameBindingCanResumeBeforeFirstRoute() {
        assertTrue(OemFocusInteropPolicy.shouldRequestFocus(
                true, true, true, false, true, true));
    }

    @Test
    public void vendorMuteReclaimsFocusWithoutChangingSource() {
        assertTrue(OemFocusInteropPolicy.shouldRequestFocus(
                false, false, true, true, true, true));
        assertFalse(OemFocusInteropPolicy.shouldRequestFocus(
                false, false, true, true, true, false));
    }

    @Test
    public void explicitTakeoverReplacesOnlyAnUnreleasedFocusRequest() {
        assertTrue(OemFocusInteropPolicy.shouldReplaceFocusRequest(true, false));
        assertFalse(OemFocusInteropPolicy.shouldReplaceFocusRequest(true, true));
        assertFalse(OemFocusInteropPolicy.shouldReplaceFocusRequest(false, false));
    }

    @Test
    public void routeIsIdempotentUntilFocusIsResumed() {
        assertTrue(OemFocusInteropPolicy.shouldRequestRoute(false, false));
        assertFalse(OemFocusInteropPolicy.shouldRequestRoute(true, false));
        assertTrue(OemFocusInteropPolicy.shouldRequestRoute(true, true));
    }
}
