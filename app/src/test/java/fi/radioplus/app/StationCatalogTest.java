package fi.radioplus.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StationCatalogTest {

    @Test
    public void autoScanAddsStationsToCatalogWithoutCreatingFavorites() {
        Map<Integer, String> names = new LinkedHashMap<>();
        names.put(98_100, "Suomipop");

        StationCatalog.MergeResult result = StationCatalog.mergeDiscovered(
                Collections.emptyList(),
                0,
                new int[]{98_100, 101_700},
                names
        );

        assertEquals(2, result.added);
        assertEquals(2, result.stations.size());
        assertEquals("Suomipop", result.stations.get(0).name);

        List<FavoriteStation> favorites = Collections.emptyList();
        assertTrue(favorites.isEmpty());
    }

    @Test
    public void repeatedScanPreservesCustomMetadataAndOnlyCountsNewStations() {
        FavoriteStation existing = new FavoriteStation(
                0,
                98_100,
                "Oma nimi",
                "builtin:radio_suomipop"
        );

        StationCatalog.MergeResult result = StationCatalog.mergeDiscovered(
                Collections.singletonList(existing),
                0,
                new int[]{98_100, 101_700},
                Collections.singletonMap(98_100, "RDS nimi")
        );

        assertEquals(1, result.added);
        assertEquals("Oma nimi", result.stations.get(0).name);
        assertEquals("builtin:radio_suomipop", result.stations.get(0).logo);
    }

    @Test
    public void legacyEntriesAppendWithoutChangingExistingOrder() {
        List<FavoriteStation> migrated = StationCatalog.mergeLegacy(
                Collections.singletonList(
                        new FavoriteStation(0, 101_700, "YleX")
                ),
                Arrays.asList(
                        new FavoriteStation(0, 98_100, "Suomipop"),
                        new FavoriteStation(0, 101_700, "")
                )
        );

        assertEquals(2, migrated.size());
        assertEquals(101_700, migrated.get(0).frequency);
        assertEquals("YleX", migrated.get(0).name);
        assertEquals(98_100, migrated.get(1).frequency);
    }

    @Test
    public void repeatedScanPreservesUserDefinedOrder() {
        List<FavoriteStation> existing = Arrays.asList(
                new FavoriteStation(0, 101_700, "YleX"),
                new FavoriteStation(0, 92_400, "Radio Suomi")
        );

        StationCatalog.MergeResult result = StationCatalog.mergeDiscovered(
                existing,
                0,
                new int[]{92_400, 98_100, 101_700},
                Collections.singletonMap(98_100, "Suomipop")
        );

        assertEquals(101_700, result.stations.get(0).frequency);
        assertEquals(92_400, result.stations.get(1).frequency);
        assertEquals(98_100, result.stations.get(2).frequency);
    }
}
