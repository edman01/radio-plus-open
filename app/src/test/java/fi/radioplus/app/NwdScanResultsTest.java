package fi.radioplus.app;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NwdScanResultsTest {
    private static int[] complete(int start, int step, int count) {
        int[] values = new int[count];
        for (int i = 0; i < count; i++) values[i] = start + i * step;
        return values;
    }

    @Test public void unambiguousFullBanksPassWithoutMutatingFrequencies() {
        int[] fm = complete(90000, 100, 18), am = complete(603, 9, 12);
        int[] original = fm.clone();
        NwdScanResults.validate(0, fm, 87500, 108000, 100);
        NwdScanResults.validate(3, am, 522, 1620, 9);
        assertArrayEquals(original, fm);
    }

    @Test public void defaultFilledAmAndFmBanksAreNotSuccessfulScans() {
        for (int padding : new int[]{522, 531}) {
            int[] values = new int[12]; Arrays.fill(values, padding);
            assertThrows(IllegalArgumentException.class,
                    () -> NwdScanResults.validate(3, values, 522, 1620, 9));
        }
        int[] values = new int[18]; Arrays.fill(values, 87500);
        assertThrows(IllegalArgumentException.class,
                () -> NwdScanResults.validate(0, values, 87500, 108000, 100));
    }

    @Test public void evenOnePossiblePaddingSlotKeepsWholeResultUnconfirmed() {
        int[] values = complete(603, 9, 12); values[11] = 531;
        assertThrows(IllegalArgumentException.class,
                () -> NwdScanResults.validate(3, values, 522, 1620, 9));
        assertTrue("Ambiguity must never ban manual tuning to a real station", FrequencyRules.isValid(3, 531));
    }

    @Test public void clampedRegionPaddingAndDuplicatesCannotBecomeHits() {
        int[] clamped = complete(90000, 100, 18); clamped[17] = 88000;
        assertThrows(IllegalArgumentException.class,
                () -> NwdScanResults.validate(0, clamped, 88000, 107000, 100));
        int[] duplicated = complete(90000, 100, 18); duplicated[17] = duplicated[0];
        assertThrows(IllegalArgumentException.class,
                () -> NwdScanResults.validate(0, duplicated, 87500, 108000, 100));
    }

    @Test public void invalidIncompleteAndOffGridResultsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> NwdScanResults.validate(0, new int[0], 87500, 108000, 100));
        int[] values = complete(90000, 100, 18); values[17] = 90050;
        assertThrows(IllegalArgumentException.class,
                () -> NwdScanResults.validate(0, values, 87500, 108000, 100));
        assertThrows(IllegalArgumentException.class,
                () -> NwdScanResults.validate(0, complete(90000, 100, 18), 87500, 108000, 0));
    }
}
