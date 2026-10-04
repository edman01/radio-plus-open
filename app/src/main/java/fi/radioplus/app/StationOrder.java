package fi.radioplus.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Preserves and mutates the user-defined order of station cards. */
final class StationOrder {
    private StationOrder() {
    }

    static boolean move(List<FavoriteStation> stations, int fromIndex, int toIndex) {
        if (stations == null
                || fromIndex < 0
                || toIndex < 0
                || fromIndex >= stations.size()
                || toIndex >= stations.size()
                || fromIndex == toIndex) {
            return false;
        }
        FavoriteStation moved = stations.remove(fromIndex);
        stations.add(toIndex, moved);
        return true;
    }

    static int indexOf(List<FavoriteStation> stations, String stationKey) {
        if (stations == null || stationKey == null) {
            return -1;
        }
        for (int index = 0; index < stations.size(); index++) {
            FavoriteStation station = stations.get(index);
            if (station != null && station.key().equals(stationKey)) {
                return index;
            }
        }
        return -1;
    }

    static List<FavoriteStation> sanitize(List<FavoriteStation> stations) {
        LinkedHashMap<String, FavoriteStation> unique = new LinkedHashMap<>();
        if (stations != null) {
            for (FavoriteStation station : stations) {
                if (station != null
                        && FrequencyRules.isValid(station.band, station.frequency)) {
                    unique.put(station.key(), station);
                }
            }
        }
        return new ArrayList<>(unique.values());
    }
}
