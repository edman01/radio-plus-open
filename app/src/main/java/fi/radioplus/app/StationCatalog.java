package fi.radioplus.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class StationCatalog {
    static final class MergeResult {
        final List<FavoriteStation> stations;
        final int added;

        MergeResult(List<FavoriteStation> stations, int added) {
            this.stations = stations;
            this.added = added;
        }
    }

    private StationCatalog() {
    }

    static MergeResult mergeDiscovered(
            List<FavoriteStation> existing,
            int band,
            int[] frequencies,
            Map<Integer, String> observedNames
    ) {
        LinkedHashMap<String, FavoriteStation> merged = new LinkedHashMap<>();
        if (existing != null) {
            for (FavoriteStation station : existing) {
                if (station != null
                        && FrequencyRules.isValid(station.band, station.frequency)) {
                    merged.put(station.key(), station);
                }
            }
        }

        int added = 0;
        if (frequencies != null) {
            for (int frequency : frequencies) {
                if (!FrequencyRules.isValid(band, frequency)) {
                    continue;
                }
                String key = band + ":" + frequency;
                FavoriteStation previous = merged.get(key);
                String observedName = observedNames == null
                        ? ""
                        : RadioMetadataReader.clean(observedNames.get(frequency));
                if (previous == null) {
                    merged.put(key, new FavoriteStation(band, frequency, observedName));
                    added++;
                } else if (previous.name.isEmpty() && !observedName.isEmpty()) {
                    merged.put(key, previous.withName(observedName));
                }
            }
        }

        return new MergeResult(new ArrayList<>(merged.values()), added);
    }

    static List<FavoriteStation> mergeLegacy(
            List<FavoriteStation> existing,
            List<FavoriteStation> legacy
    ) {
        LinkedHashMap<String, FavoriteStation> merged = new LinkedHashMap<>();
        if (existing != null) {
            for (FavoriteStation station : existing) {
                if (station != null
                        && FrequencyRules.isValid(station.band, station.frequency)) {
                    merged.put(station.key(), station);
                }
            }
        }
        if (legacy != null) {
            for (FavoriteStation station : legacy) {
                if (station == null
                        || !FrequencyRules.isValid(station.band, station.frequency)) {
                    continue;
                }
                FavoriteStation current = merged.get(station.key());
                if (current == null) {
                    merged.put(station.key(), station);
                } else {
                    String name = current.name.isEmpty() ? station.name : current.name;
                    String logo = current.logo.isEmpty() ? station.logo : current.logo;
                    merged.put(
                            station.key(),
                            new FavoriteStation(
                                    current.band,
                                    current.frequency,
                                    name,
                                    logo
                            )
                    );
                }
            }
        }
        return new ArrayList<>(merged.values());
    }
}
