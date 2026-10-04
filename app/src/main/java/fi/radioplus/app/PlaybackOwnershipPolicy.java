package fi.radioplus.app;

/** Confirms an external source without changing the tuner, mute, or audio focus. */
final class PlaybackOwnershipPolicy {
    private static final long SETTLING_GRACE_MS = 2500L;
    private static final int REQUIRED_EXTERNAL_OBSERVATIONS = 2;

    private boolean initialized;
    private long requestedAt;
    private int consecutiveExternalObservations;

    /** Starts a new grace period after an explicit playback or tuning request. */
    synchronized void reset(long now) {
        initialized = true;
        requestedAt = now;
        consecutiveExternalObservations = 0;
    }

    /**
     * Uses monotonic milliseconds. Only two consecutive known external-source
     * observations after the grace period can yield ownership. Unknown firmware
     * signals leave existing behavior unchanged and break any partial evidence.
     * Mute is deliberately not an input: global mute must retain media controls.
     * The service must reject observations from an obsolete request generation.
     */
    synchronized boolean shouldYield(long now, boolean sourceKnown, boolean radioOwnsSource) {
        if (!initialized || now - requestedAt < SETTLING_GRACE_MS
                || !sourceKnown || radioOwnsSource) {
            consecutiveExternalObservations = 0;
            return false;
        }
        if (consecutiveExternalObservations < REQUIRED_EXTERNAL_OBSERVATIONS) {
            consecutiveExternalObservations++;
        }
        return consecutiveExternalObservations == REQUIRED_EXTERNAL_OBSERVATIONS;
    }
}
