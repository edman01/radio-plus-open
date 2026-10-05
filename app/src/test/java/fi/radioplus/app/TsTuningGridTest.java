package fi.radioplus.app;

import org.junit.Test;
import static org.junit.Assert.*;

public final class TsTuningGridTest {
    @Test public void fmFrequencyBecomesAnIndexNotAnAbsoluteValue() {
        assertEquals(106, TsTuningGrid.indexFor(9810, 8750, 8760, 206));
        assertEquals(212, TsTuningGrid.indexFor(9810, 8750, 8755, 411));
        assertEquals(0, TsTuningGrid.indexFor(8750, 8750, 8760, 206));
        assertEquals(205, TsTuningGrid.indexFor(10800, 8750, 8760, 206));
    }
    @Test public void amUsesItsOwnRegionAndSpacing() {
        assertEquals(53, TsTuningGrid.indexFor(999, 522, 531, 123));
        assertEquals(63, TsTuningGrid.indexFor(1160, 530, 540, 118));
        assertEquals(-1, TsTuningGrid.indexFor(999, 530, 540, 118));
    }
    @Test public void invalidOrUnsupportedGridCannotTune() {
        assertEquals(-1, TsTuningGrid.indexFor(8750, 0, 10, 206));
        assertEquals(-1, TsTuningGrid.indexFor(9810, 8750, 8750, 206));
        assertEquals(-1, TsTuningGrid.indexFor(9810, 8750, 8700, 206));
        assertEquals(-1, TsTuningGrid.indexFor(9810, 8750, 8760, 1));
        assertEquals(-1, TsTuningGrid.indexFor(9810, 8750, 8760, 10001));
        assertEquals(-1, TsTuningGrid.indexFor(8740, 8750, 8760, 206));
        assertEquals(-1, TsTuningGrid.indexFor(10810, 8750, 8760, 206));
        assertEquals(-1, TsTuningGrid.indexFor(8751, 8750, 8760, 206));
        assertEquals(-1, TsTuningGrid.indexFor(Integer.MAX_VALUE, 1, 2, 206));
    }
}
