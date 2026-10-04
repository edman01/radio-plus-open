package fi.radioplus.app;

import java.util.ArrayList;
import java.util.List;

/**
 * Selects the adjacent saved station for short steering-wheel presses.
 */
final class FavoriteNavigator {
    private FavoriteNavigator() {
    }

    static FavoriteStation select(
            List<FavoriteStation> stations,
            int band,
            int currentFrequency,
            boolean next
    ) {
        ArrayList<FavoriteStation> bandStations = new ArrayList<>();
        for (FavoriteStation station : stations) {
            if (station.band == band
                    && FrequencyRules.isValid(station.band, station.frequency)) {
                bandStations.add(station);
            }
        }
        if (bandStations.isEmpty()) {
            return null;
        }
        int currentIndex = -1;
        for (int index = 0; index < bandStations.size(); index++) {
            if (bandStations.get(index).frequency == currentFrequency) {
                currentIndex = index;
                break;
            }
        }
        if (currentIndex < 0) {
            return next
                    ? bandStations.get(0)
                    : bandStations.get(bandStations.size() - 1);
        }
        int target = next
                ? (currentIndex + 1) % bandStations.size()
                : (currentIndex - 1 + bandStations.size()) % bandStations.size();
        return bandStations.get(target);
    }

    /**
     * Selects the adjacent favorite exactly in the user's saved order.
     * Unlike tuner-band seeking this may cross an FM/AM bank boundary.
     */
    static FavoriteStation selectInStoredOrder(
            List<FavoriteStation> stations,
            int currentBand,
            int currentFrequency,
            boolean next
    ) {
        ArrayList<FavoriteStation> validStations = new ArrayList<>();
        for (FavoriteStation station : stations) {
            if (FrequencyRules.isValid(station.band, station.frequency)) {
                validStations.add(station);
            }
        }
        if (validStations.isEmpty()) {
            return null;
        }
        int currentIndex = -1;
        for (int index = 0; index < validStations.size(); index++) {
            FavoriteStation station = validStations.get(index);
            if (station.band == currentBand
                    && station.frequency == currentFrequency) {
                currentIndex = index;
                break;
            }
        }
        if (currentIndex < 0) {
            return next
                    ? validStations.get(0)
                    : validStations.get(validStations.size() - 1);
        }
        int target = next
                ? (currentIndex + 1) % validStations.size()
                : (currentIndex - 1 + validStations.size())
                % validStations.size();
        return validStations.get(target);
    }
}
