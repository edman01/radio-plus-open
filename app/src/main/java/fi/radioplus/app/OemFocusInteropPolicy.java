package fi.radioplus.app;

/** Keeps Radio+ from duplicating FMPlugService's singleton audio-focus request. */
final class OemFocusInteropPolicy {
    private OemFocusInteropPolicy() {
    }

    static boolean shouldRequestFocus(
            boolean firstRouteRequestSinceBinding,
            boolean explicitlyReleasedByRadioPlus,
            boolean sourceKnown,
            boolean radioOwnsSource,
            boolean muteKnown,
            boolean muted
    ) {
        // A binder connection does not mean that FMPlugService's onCreate()
        // focus is still valid. The vendor service stays alive for RDS updates
        // after AUDIOFOCUS_LOSS, but its listener mutes the tuner and abandons
        // focus. The stock radio fixes that by requesting focus in every UI
        // onResume(). Do the same once per Radio+ binder session, then only
        // after an explicit pause, another source, or an observed OEM mute.
        return firstRouteRequestSinceBinding
                || explicitlyReleasedByRadioPlus
                || (sourceKnown && !radioOwnsSource)
                || (muteKnown && muted);
    }

    static boolean shouldReplaceFocusRequest(
            boolean explicitTakeover,
            boolean focusAlreadyReleasedByRadioPlus
    ) {
        // Junsun can keep the same FMPlugService binder alive after YouTube or
        // another player takes over. SourceInfo and RadioPlayer mute state are
        // not reliable on every V7 ROM, so a direct user playback action must
        // replace the vendor's possibly stale AudioFocusRequest. Do not
        // abandon it twice when Radio+ already performed an explicit pause.
        return explicitTakeover && !focusAlreadyReleasedByRadioPlus;
    }

    static boolean shouldRequestRoute(boolean routeAlreadyActive, boolean focusWasResumed) {
        return !routeAlreadyActive || focusWasResumed;
    }
}
