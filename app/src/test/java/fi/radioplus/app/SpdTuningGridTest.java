package fi.radioplus.app;

import org.junit.Test;
import static org.junit.Assert.*;

public final class SpdTuningGridTest {
    @Test public void amFrequencyNeverUsesFmMagnitudeHeuristic() {
        for (String band : new String[]{"AM", "AM_FAVORITES", "AM1", "AM2"}) {
            SpdTuningGrid grid = SpdTuningGrid.fromSnapshot(band, 900, 522, 1620, 9);
            assertEquals(3, grid.band);
            assertEquals(900, grid.frequency);
            assertEquals(1, grid.scale);
            assertTrue(grid.contains(900));
            assertEquals(909, grid.next(1));
            assertEquals(891, grid.next(-1));
            assertFalse(grid.contains(90_000));
            grid.requireRepresentableCurrentFrequency();
        }
    }

    @Test public void singleBandAndOemFavoriteListNamesDoNotInventExtraBanks() {
        for (String band : new String[]{"FM", "FM_FAVORITES"}) {
            SpdTuningGrid grid = SpdTuningGrid.fromSnapshot(band, 98_100, 87_500, 108_000, 50);
            assertEquals(0, grid.band);
            assertEquals(band, grid.nativeBand);
            grid.requireRepresentableCurrentFrequency();
        }
    }

    @Test public void unrepresentableRegionalCurrentFrequencyMustNotActivateAudio() {
        for (SpdTuningGrid grid : new SpdTuningGrid[]{
                SpdTuningGrid.fromSnapshot("AM", 1000, 530, 1710, 10),
                SpdTuningGrid.fromSnapshot("FM", 87_550, 87_500, 108_000, 50),
                SpdTuningGrid.fromSnapshot("FM", 70_000, 65_000, 74_000, 100),
                SpdTuningGrid.fromSnapshot("AM", 600, 600, 610, 10)}) {
            assertThrows(IllegalArgumentException.class, grid::requireRepresentableCurrentFrequency);
        }
    }

    @Test public void completeFmGridDeterminesUnits() {
        int[][] raw = {{98_100, 87_500, 108_000, 100},
                {9810, 8750, 10800, 10}, {981, 875, 1080, 1}};
        int[] scale = {1, 10, 100};
        for (int i = 0; i < raw.length; i++) {
            SpdTuningGrid grid = SpdTuningGrid.fromSnapshot("FM2",
                    raw[i][0], raw[i][1], raw[i][2], raw[i][3]);
            assertEquals(1, grid.band);
            assertEquals(scale[i], grid.scale);
            assertEquals(98_100, grid.frequency);
            assertEquals(87_500, grid.minimum);
            assertEquals(108_000, grid.maximum);
            assertTrue(grid.contains(101_700));
        }
    }

    @Test public void manualStepsRespectBothRegionalAndAppGrid() {
        SpdTuningGrid grid = SpdTuningGrid.fromSnapshot("FM1", 9810, 8750, 10790, 20);
        assertFalse(grid.contains(98_200));
        assertEquals(98_300, grid.next(1));
        assertEquals(97_900, grid.next(-1));
        assertEquals(87_500,
                SpdTuningGrid.fromSnapshot("FM1", 10790, 8750, 10790, 20).next(1));
        assertEquals(1080,
                SpdTuningGrid.fromSnapshot("AM1", 1000, 530, 1710, 10).next(1));
        assertThrows(IllegalArgumentException.class,
                () -> SpdTuningGrid.fromSnapshot("AM1", 600, 600, 610, 10).next(1));
    }

    @Test public void unknownBandsAndIncompleteSnapshotsCannotSupplyDefaults() {
        for (String band : new String[]{null, "", "fm", "fm1", "MW", "AM3", "FM4"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> SpdTuningGrid.fromSnapshot(band, 9810, 8750, 10800, 10));
        }
        int[][] invalid = {{9810, 0, 10800, 10}, {9810, 8750, 10800, 0},
                {9810, 10800, 8750, 10}, {9811, 8750, 10800, 10},
                {0, 8750, 10800, 10}, {9810, 8750, Integer.MAX_VALUE, 10},
                {9810, 8750, 10800, Integer.MAX_VALUE}, {9810, 8750, 10800, -10}};
        for (int[] values : invalid) {
            assertThrows(IllegalArgumentException.class, () -> SpdTuningGrid.fromSnapshot(
                    "FM1", values[0], values[1], values[2], values[3]));
        }
        assertThrows(IllegalArgumentException.class,
                () -> SpdTuningGrid.fromSnapshot("AM1", 9810, 8750, 10800, 10));
    }
}
