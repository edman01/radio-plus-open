package fi.radioplus.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class OemFocusInteropPolicyTest {
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
