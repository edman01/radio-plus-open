package fi.radioplus.app;

final class MediaStationId {
    private static final String PREFIX = "station:";

    private MediaStationId() {
    }

    static String encode(FavoriteStation station) {
        return PREFIX + station.band + ":" + station.frequency;
    }

    static FavoriteStation decode(String mediaId) {
        if (mediaId == null || !mediaId.startsWith(PREFIX)) {
            return null;
        }
        String[] parts = mediaId.substring(PREFIX.length()).split(":", -1);
        if (parts.length != 2) {
            return null;
        }
        try {
            int band = Integer.parseInt(parts[0]);
            int frequency = Integer.parseInt(parts[1]);
            if (!FrequencyRules.isValid(band, frequency)) {
                return null;
            }
            return new FavoriteStation(band, frequency, "");
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
