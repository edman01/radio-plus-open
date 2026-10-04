package fi.radioplus.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class StationWidgetModeTest {
    @Test
    public void invalidModeFallsBackToFavorites() {
        assertEquals(
                StationWidgetMode.FAVORITES,
                StationWidgetMode.normalize(99)
        );
        assertEquals("FAVORITES", StationWidgetMode.title(99));
    }

    @Test
    public void stationModeHasStationLabels() {
        assertEquals(
                "STATION LIST",
                StationWidgetMode.title(StationWidgetMode.STATIONS)
        );
        assertEquals(
                "No stations found yet",
                StationWidgetMode.emptyText(StationWidgetMode.STATIONS)
        );
    }
}
