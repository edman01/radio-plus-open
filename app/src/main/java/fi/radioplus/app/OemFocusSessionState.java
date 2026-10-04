package fi.radioplus.app;

/** Tracks audio-focus ownership only for the lifetime of one OEM binder session. */
final class OemFocusSessionState {
    private boolean focusReleasedByRadioPlus;
    private boolean routeEverRequested;

    synchronized void onServiceConnected() {
        // FMPlugService creates a fresh AudioFocusRequest in onCreate(). A
        // release marker from an older/dead binder must never be applied to
        // this new request, or the next activation can duplicate vendor focus.
        focusReleasedByRadioPlus = false;
        routeEverRequested = false;
    }

    synchronized void onServiceDisconnected() {
        routeEverRequested = false;
    }

    synchronized boolean wasFocusReleasedByRadioPlus() {
        return focusReleasedByRadioPlus;
    }

    synchronized void markFocusReleasedByRadioPlus() {
        focusReleasedByRadioPlus = true;
    }

    synchronized void markFocusResumed() {
        focusReleasedByRadioPlus = false;
    }

    synchronized boolean shouldRequestFocus(
            boolean sourceKnown,
            boolean radioOwnsSource,
            boolean muteKnown,
            boolean muted
    ) {
        return OemFocusInteropPolicy.shouldRequestFocus(
                !routeEverRequested,
                focusReleasedByRadioPlus,
                sourceKnown,
                radioOwnsSource,
                muteKnown,
                muted
        );
    }

    synchronized void markRouteRequested() {
        routeEverRequested = true;
    }
}
