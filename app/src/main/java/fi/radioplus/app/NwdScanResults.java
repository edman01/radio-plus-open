package fi.radioplus.app;

import java.util.HashSet;
import java.util.Set;

/** Rejects ambiguous AW preset banks instead of inventing discovered stations. */
final class NwdScanResults {
    private NwdScanResults() { }

    static void validate(int band, int[] frequencies, int gridMin, int gridMax, int gridStep) {
        if (frequencies == null || frequencies.length != (band < 3 ? 18 : 12)
                || gridMin <= 0 || gridMax < gridMin || gridStep <= 0) {
            throw new IllegalArgumentException("Incomplete NWD scan bank/grid");
        }
        // Defaults in the fingerprinted AW service vary by region and can be
        // clamped to a regional edge. There is no exported hit count/valid bit.
        // These are NOT forbidden tuning frequencies: a real station may be
        // here. Reject the ambiguous result as a whole and preserve user data.
        int[] defaults = band < 3 ? new int[]{65000, 76000, 87500, 107900, 108000}
                : new int[]{520, 522, 530, 531};
        Set<Integer> seen = new HashSet<>();
        for (int frequency : frequencies) {
            if (!FrequencyRules.isValid(band, frequency) || frequency < gridMin
                    || frequency > gridMax || ((long) frequency - gridMin) % gridStep != 0
                    || !seen.add(frequency)) {
                throw new IllegalArgumentException("Invalid or ambiguous NWD scan entries");
            }
            for (int padding : defaults) {
                if (frequency == Math.max(gridMin, Math.min(gridMax, padding))) {
                    throw new IllegalArgumentException("NWD preset may be padding, not a scan hit");
                }
            }
        }
    }
}
