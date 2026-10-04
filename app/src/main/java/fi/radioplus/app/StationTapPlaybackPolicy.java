package fi.radioplus.app;

final class StationTapPlaybackPolicy {
    private StationTapPlaybackPolicy() {
    }

    static boolean shouldPause(
            String tappedStationKey,
            String activeStationKey,
            boolean playbackRequested
    ) {
        return playbackRequested
                && tappedStationKey != null
                && !tappedStationKey.isEmpty()
                && tappedStationKey.equals(activeStationKey);
    }
}
