package fi.radioplus.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.List;

/** Shares the selected station list between the screen and background media controls. */
final class StationNavigationStore {
    private final SharedPreferences preferences;
    private final FavoriteStore favorites;
    private final StationStore stations;

    StationNavigationStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(
                "radio_plus_navigation", Context.MODE_PRIVATE);
        favorites = new FavoriteStore(context);
        stations = new StationStore(context);
    }

    boolean favoritesSelected() {
        // Preserve the existing initial screen on an install without navigation state.
        return preferences.getBoolean("favorites_selected", true);
    }

    void setFavoritesSelected(boolean selected) {
        if (favoritesSelected() == selected) return;
        preferences.edit().putBoolean("favorites_selected", selected).apply();
        RadioPlaybackService.notifyMediaLibraryChanged();
    }

    // The tab on screen is not necessarily the list the playing station came from.
    // On upgrade keep dev40's favorites behavior until a list selection is confirmed.
    boolean playbackFromFavorites() {
        return preferences.getBoolean("playback_from_favorites", true);
    }

    void rememberPlaybackList(boolean fromFavorites) {
        if (playbackFromFavorites() != fromFavorites)
            preferences.edit().putBoolean("playback_from_favorites", fromFavorites).apply();
    }

    List<FavoriteStation> load(boolean fromFavorites) {
        return fromFavorites ? favorites.load() : stations.load();
    }

    List<FavoriteStation> load() {
        return load(favoritesSelected());
    }
}
