package fi.radioplus.app;

import android.content.Context;
import android.content.SharedPreferences;

final class StationWidgetPreferences {
    private static final String PREFERENCES = "radio_plus_widgets";
    private static final String KEY_MODE_PREFIX = "mode_";

    private StationWidgetPreferences() {
    }

    static int getMode(Context context, int appWidgetId) {
        SharedPreferences preferences = context.getSharedPreferences(
                PREFERENCES,
                Context.MODE_PRIVATE
        );
        return StationWidgetMode.normalize(preferences.getInt(
                KEY_MODE_PREFIX + appWidgetId,
                StationWidgetMode.FAVORITES
        ));
    }

    static void setMode(Context context, int appWidgetId, int mode) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_MODE_PREFIX + appWidgetId, StationWidgetMode.normalize(mode))
                .apply();
    }

    static void delete(Context context, int appWidgetId) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_MODE_PREFIX + appWidgetId)
                .apply();
    }
}
