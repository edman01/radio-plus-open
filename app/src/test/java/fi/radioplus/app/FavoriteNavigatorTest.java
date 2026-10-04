package fi.radioplus.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class FavoriteNavigatorTest {
    private final List<FavoriteStation> stations = Arrays.asList(
            new FavoriteStation(0, 92400, "Radio Suomi"),
            new FavoriteStation(0, 98100, "Suomipop"),
            new FavoriteStation(0, 101700, "YleX"),
            new FavoriteStation(3, 999, "AM")
    );

    @Test
    public void nextSelectsHigherFavorite() {
        FavoriteStation selected = FavoriteNavigator.select(
                stations,
                0,
                98100,
                true
        );

        assertEquals(101700, selected.frequency);
    }

    @Test
    public void previousSelectsLowerFavorite() {
        FavoriteStation selected = FavoriteNavigator.select(
                stations,
                0,
                98100,
                false
        );

        assertEquals(92400, selected.frequency);
    }

    @Test
    public void nextWrapsToFirstFavorite() {
        FavoriteStation selected = FavoriteNavigator.select(
                stations,
                0,
                101700,
                true
        );

        assertEquals(92400, selected.frequency);
    }

    @Test
    public void previousWrapsToLastFavorite() {
        FavoriteStation selected = FavoriteNavigator.select(
                stations,
                0,
                92400,
                false
        );

        assertEquals(101700, selected.frequency);
    }

    @Test
    public void noFavoriteOnBandReturnsNull() {
        assertNull(FavoriteNavigator.select(
                Collections.emptyList(),
                0,
                98100,
                true
        ));
    }

    @Test
    public void nextFollowsUserDefinedOrderInsteadOfFrequency() {
        List<FavoriteStation> custom = Arrays.asList(
                new FavoriteStation(0, 101700, "YleX"),
                new FavoriteStation(0, 92400, "Radio Suomi"),
                new FavoriteStation(0, 98100, "Suomipop")
        );

        FavoriteStation selected = FavoriteNavigator.select(
                custom,
                0,
                101700,
                true
        );

        assertEquals(92400, selected.frequency);
    }

    @Test
    public void storedOrderNavigationNeverFallsBackToStationHistory() {
        List<FavoriteStation> custom = Arrays.asList(
                new FavoriteStation(0, 101700, "YleX"),
                new FavoriteStation(0, 92400, "Radio Suomi"),
                new FavoriteStation(3, 999, "AM")
        );

        FavoriteStation selected = FavoriteNavigator.selectInStoredOrder(
                custom,
                0,
                92400,
                true
        );

        assertEquals(3, selected.band);
        assertEquals(999, selected.frequency);
    }

    @Test
    public void storedOrderNavigationWrapsAcrossTheWholeFavoriteList() {
        FavoriteStation selected = FavoriteNavigator.selectInStoredOrder(
                stations,
                3,
                999,
                true
        );

        assertEquals(0, selected.band);
        assertEquals(92400, selected.frequency);
    }

    @Test
    public void storedOrderStartsAtEdgeWhenCurrentStationIsNotFavorite() {
        FavoriteStation next = FavoriteNavigator.selectInStoredOrder(
                stations,
                0,
                99500,
                true
        );
        FavoriteStation previous = FavoriteNavigator.selectInStoredOrder(
                stations,
                0,
                99500,
                false
        );

        assertEquals(92400, next.frequency);
        assertEquals(999, previous.frequency);
    }
}
