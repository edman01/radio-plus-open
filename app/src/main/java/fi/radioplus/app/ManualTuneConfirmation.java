package fi.radioplus.app;

/** Prevents saving a typed frequency before the radio hardware confirms it. */
final class ManualTuneConfirmation {
    private ManualTuneConfirmation() {
    }

    static boolean isConfirmed(
            int requestedBand,
            int requestedFrequency,
            int actualBand,
            int actualFrequency,
            boolean radioBusy
    ) {
        return !radioBusy
                && requestedFrequency >= 0
                && requestedBand == actualBand
                && requestedFrequency == actualFrequency;
    }
}
