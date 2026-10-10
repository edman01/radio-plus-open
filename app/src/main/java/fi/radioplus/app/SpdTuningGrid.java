package fi.radioplus.app;

/** Band-aware validation of SPD frequency snapshots. All app values are kHz. */
final class SpdTuningGrid {
    final int band;
    final String nativeBand;
    final int scale;
    final int minimum;
    final int maximum;
    final int step;
    final int frequency;

    private SpdTuningGrid(String nativeBand, int band, int scale,
            int minimum, int maximum, int step, int frequency) {
        this.nativeBand = nativeBand;
        this.band = band;
        this.scale = scale;
        this.minimum = minimum;
        this.maximum = maximum;
        this.step = step;
        this.frequency = frequency;
    }

    static SpdTuningGrid fromSnapshot(String nativeBand, int rawFrequency,
            int rawMinimum, int rawMaximum, int rawStep) {
        int band = appBand(nativeBand);
        if (rawMinimum <= 0 || rawMaximum < rawMinimum || rawStep <= 0
                || rawFrequency < rawMinimum || rawFrequency > rawMaximum
                || ((long) rawFrequency - rawMinimum) % rawStep != 0) {
            throw new IllegalArgumentException("Incomplete SPD frequency grid");
        }
        // FM units are identified by the complete reported grid, never by the
        // current number alone. AM 900 kHz must stay 900 kHz, not become FM 90 MHz.
        int selectedScale = 0;
        for (int scale : band < 3 ? new int[]{1, 10, 100} : new int[]{1}) {
            long min = (long) rawMinimum * scale;
            long max = (long) rawMaximum * scale;
            long spacing = (long) rawStep * scale;
            boolean plausible = band < 3
                    ? min >= 65_000 && min < 108_000 && max <= 120_000
                        && max > min && spacing <= 500
                    : min >= 150 && min < 3_000 && max <= 3_000
                        && max > min && spacing <= 20;
            if (plausible) {
                if (selectedScale != 0) throw new IllegalArgumentException("Ambiguous SPD frequency units");
                selectedScale = scale;
            }
        }
        if (selectedScale == 0) throw new IllegalArgumentException("Unrecognized SPD frequency units");
        return new SpdTuningGrid(nativeBand, band, selectedScale,
                Math.multiplyExact(rawMinimum, selectedScale),
                Math.multiplyExact(rawMaximum, selectedScale),
                Math.multiplyExact(rawStep, selectedScale),
                Math.multiplyExact(rawFrequency, selectedScale));
    }

    static int appBand(String band) {
        if ("FM".equals(band) || "FM_FAVORITES".equals(band)) return 0;
        if ("AM".equals(band) || "AM_FAVORITES".equals(band)) return 3;
        if ("FM1".equals(band)) return 0;
        if ("FM2".equals(band)) return 1;
        if ("FM3".equals(band)) return 2;
        if ("AM1".equals(band) || "AM2".equals(band)) return 3;
        throw new IllegalArgumentException("Unrecognized SPD band");
    }

    boolean contains(int khz) {
        return FrequencyRules.isValid(band, khz) && khz >= minimum && khz <= maximum
                && ((long) khz - minimum) % step == 0;
    }

    void requireRepresentableCurrentFrequency() {
        if (!contains(frequency)) {
            throw new IllegalArgumentException("SPD current frequency is outside the supported app grid");
        }
    }

    int next(int direction) {
        int candidate = FrequencyRules.stepFrom(band, frequency, direction);
        int min = band < 3 ? FrequencyRules.FM_MIN : FrequencyRules.AM_MIN;
        int max = band < 3 ? FrequencyRules.FM_MAX : FrequencyRules.AM_MAX;
        int appStep = band < 3 ? FrequencyRules.FM_STEP : FrequencyRules.AM_STEP;
        for (int guard = 0; guard <= (max - min) / appStep; guard++) {
            if (contains(candidate)) return candidate;
            candidate = FrequencyRules.stepFrom(band, candidate, direction);
        }
        throw new IllegalArgumentException("SPD grid has no supported tuning target");
    }
}
