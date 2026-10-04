package fi.radioplus.app;

import android.annotation.SuppressLint;
import android.content.Context;

final class AutoStartPreferences {
    private static final String PREFERENCES = "radio_plus_startup";
    private static final String KEY_ENABLED = "auto_start_enabled";

    private AutoStartPreferences() {
    }

    static boolean isEnabled(Context context) {
        return storageContext(context)
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false);
    }

    @SuppressLint("ApplySharedPref")
    static void setEnabled(Context context, boolean enabled) {
        // Commit synchronously so an ACC sleep immediately after changing the
        // setting cannot leave the boot receiver with the previous value.
        storageContext(context)
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, enabled)
                .commit();
    }

    private static Context storageContext(Context context) {
        Context application = context.getApplicationContext();
        return application.createDeviceProtectedStorageContext();
    }
}
