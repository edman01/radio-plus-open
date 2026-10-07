package fi.radioplus.app;

/** Narrow units/range contract for the inspected Reglink S5.40 firmware. */
final class ReglinkTuningRules {
    private ReglinkTuningRules() { }

    static int appBand(String band) {
        if ("fm".equals(band)) return 0;
        if ("am".equals(band)) return 3;
        return -1;
    }

    static String nativeBand(int band) {
        if (FrequencyRules.isFm(band)) return "fm";
        return band == 3 ? "am" : "";
    }

    static boolean validTarget(int band, int khz) {
        if (!FrequencyRules.isValid(band, khz)) return false;
        // The inspected MCU defaults use this narrower AM range. No regional
        // configuration is written, and unsupported/onboard AM is rejected.
        return FrequencyRules.isFm(band) || (khz >= 531 && khz <= 1602);
    }

    static int rawFrequency(int band, int khz) {
        if (!validTarget(band, khz)) return -1;
        return FrequencyRules.isFm(band) ? khz / 10 : khz;
    }

    static int observedKhz(String band, int raw) {
        if ("fm".equals(band)) {
            // Native FM readbacks also include the OEM's 50 kHz seek grid.
            if (raw < 8750 || raw > 10800 || raw % 5 != 0) return -1;
            return raw * 10;
        }
        if ("am".equals(band) && raw >= 531 && raw <= 1602
                && (raw - 531) % 9 == 0) return raw;
        return -1;
    }
}
