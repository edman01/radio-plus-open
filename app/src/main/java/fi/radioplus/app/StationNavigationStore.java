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

    List<FavoriteStation> load() {
        return favoritesSelected() ? favorites.load() : stations.load();
    }
}
