package fi.radioplus.app;

/**
 * Timing policy for resolving RDS PS names after an automatic scan.
 *
 * The tuner must first confirm the requested frequency. Only then does the
 * RDS-name timeout begin, because Junsun units can take several seconds to
 * change band/frequency after a full-band scan.
 */
final class StationNameResolutionPolicy {
    static final long POLL_INTERVAL_MS = 800L;
    static final long TUNE_CONFIRM_TIMEOUT_MS = 8_000L;
    static final long RDS_NAME_TIMEOUT_MS = 20_000L;
    static final int MAX_TUNE_ATTEMPTS = 2;

    enum Decision {
        WAIT,
        RETUNE,
        SAVE,
        SKIP
    }

    private StationNameResolutionPolicy() {
    }

    static Decision decide(
            boolean matchingState,
            boolean hasRdsName,
            long tuneElapsedMs,
            long matchedElapsedMs,
            int tuneAttempt
    ) {
        if (matchingState) {
            if (hasRdsName) {
                return Decision.SAVE;
            }
            return matchedElapsedMs < RDS_NAME_TIMEOUT_MS
                    ? Decision.WAIT
                    : Decision.SKIP;
        }
        if (tuneElapsedMs < TUNE_CONFIRM_TIMEOUT_MS) {
            return Decision.WAIT;
        }
        return tuneAttempt < MAX_TUNE_ATTEMPTS
                ? Decision.RETUNE
                : Decision.SKIP;
    }
}
