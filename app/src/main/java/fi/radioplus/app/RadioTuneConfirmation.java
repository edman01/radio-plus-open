package fi.radioplus.app;

/**
 * Keeps optimistic UI metadata separate from the tuner state reported by the
 * Junsun radio service.
 */
final class RadioTuneConfirmation {
    static final int MAX_ATTEMPTS = 3;

    private RadioTuneConfirmation() {
    }

    static boolean matches(
            int targetBand,
            int targetFrequency,
            int observedBand,
            int observedFrequency
    ) {
        return targetBand == observedBand && targetFrequency == observedFrequency;
    }
}
