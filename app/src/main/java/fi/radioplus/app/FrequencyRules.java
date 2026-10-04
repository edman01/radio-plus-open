package fi.radioplus.app;

final class FrequencyRules {
    static final int FM_MIN = 87_500;
    static final int FM_MAX = 108_000;
    static final int FM_STEP = 100;
    static final int AM_MIN = 522;
    static final int AM_MAX = 1_620;
    static final int AM_STEP = 9;

    private FrequencyRules() {
    }

    static boolean isFm(int band) {
        return band >= 0 && band < 3;
    }

    static boolean isValid(int band, int frequency) {
        if (isFm(band)) {
            return frequency >= FM_MIN
                    && frequency <= FM_MAX
                    && (frequency - FM_MIN) % FM_STEP == 0;
        }
        if (band == 3) {
            return frequency >= AM_MIN
                    && frequency <= AM_MAX
                    && (frequency - AM_MIN) % AM_STEP == 0;
        }
        return false;
    }

    static int stepFrom(int band, int currentFrequency, int direction) {
        boolean fm = isFm(band);
        int minimum = fm ? FM_MIN : AM_MIN;
        int maximum = fm ? FM_MAX : AM_MAX;
        int step = fm ? FM_STEP : AM_STEP;
        int clamped = Math.max(minimum, Math.min(maximum, currentFrequency));
        int offset = clamped - minimum;
        int next;
        if (direction >= 0) {
            next = minimum + ((offset / step) + 1) * step;
            if (next > maximum) {
                return minimum;
            }
        } else {
            next = minimum + (((offset + step - 1) / step) - 1) * step;
            if (next < minimum) {
                return maximum;
            }
        }
        return next;
    }
}
