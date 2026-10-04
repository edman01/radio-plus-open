package fi.radioplus.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

public final class StationOrderTest {
    @Test
    public void moveReordersStationCards() {
        ArrayList<FavoriteStation> stations = new ArrayList<>(Arrays.asList(
                new FavoriteStation(0, 92_400, "A"),
                new FavoriteStation(0, 98_100, "B"),
                new FavoriteStation(0, 101_700, "C")
        ));

        assertTrue(StationOrder.move(stations, 2, 0));
        assertEquals(101_700, stations.get(0).frequency);
        assertEquals(92_400, stations.get(1).frequency);
        assertEquals(98_100, stations.get(2).frequency);
    }

    @Test
    public void moveRejectsInvalidPositions() {
        ArrayList<FavoriteStation> stations = new ArrayList<>();
        stations.add(new FavoriteStation(0, 92_400, "A"));

        assertFalse(StationOrder.move(stations, 0, 0));
        assertFalse(StationOrder.move(stations, -1, 0));
        assertFalse(StationOrder.move(stations, 0, 1));
    }
}
