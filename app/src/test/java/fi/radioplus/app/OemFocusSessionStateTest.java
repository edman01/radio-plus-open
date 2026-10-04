package fi.radioplus.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class OemFocusSessionStateTest {
    @Test
    public void newConnectionReclaimsPossiblyStaleVendorFocusOnce() {
        OemFocusSessionState state = new OemFocusSessionState();

        state.onServiceConnected();

        assertTrue(state.shouldRequestFocus(false, false, false, false));
        assertTrue(state.shouldRequestFocus(true, true, true, false));
        state.markRouteRequested();
        state.markFocusResumed();
        assertFalse(state.shouldRequestFocus(true, true, true, false));
    }

    @Test
    public void newConnectionReclaimsFocusFromAnotherKnownSource() {
        OemFocusSessionState state = new OemFocusSessionState();

        state.onServiceConnected();

        assertTrue(state.shouldRequestFocus(true, false, true, false));
    }

    @Test
    public void explicitPauseAndPlayWithinOneConnectionArePaired() {
        OemFocusSessionState state = new OemFocusSessionState();
        state.onServiceConnected();
        state.markFocusReleasedByRadioPlus();

        assertTrue(state.shouldRequestFocus(true, true, true, false));

        state.markFocusResumed();
        assertFalse(state.wasFocusReleasedByRadioPlus());
        state.markRouteRequested();
        assertFalse(state.shouldRequestFocus(true, true, true, false));
    }

    @Test
    public void reconnectClearsReleaseMarkerFromDeadBinder() {
        OemFocusSessionState state = new OemFocusSessionState();
        state.onServiceConnected();
        state.markRouteRequested();
        state.markFocusReleasedByRadioPlus();
        state.onServiceDisconnected();

        state.onServiceConnected();

        assertFalse(state.wasFocusReleasedByRadioPlus());
        assertTrue(state.shouldRequestFocus(true, false, true, false));
        state.markRouteRequested();
        state.markFocusResumed();
        assertTrue(state.shouldRequestFocus(true, false, true, false));
    }

    @Test
    public void observedOemMuteReclaimsFocusWithinSameConnection() {
        OemFocusSessionState state = new OemFocusSessionState();
        state.onServiceConnected();
        state.markRouteRequested();
        state.markFocusResumed();

        assertTrue(state.shouldRequestFocus(true, true, true, true));
    }
}
