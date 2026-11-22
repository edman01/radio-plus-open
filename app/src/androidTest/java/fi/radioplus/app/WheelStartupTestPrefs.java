package fi.radioplus.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Isolate only the two device-protected startup settings changed by the wheel fixtures. */
final class WheelStartupTestPrefs implements AutoCloseable {
    private final SharedPreferences prefs;
    private final Boolean autoStart, wheel;
    private static final String AUTO = "auto_start_enabled", WHEEL = "hcn_restore_wheel_on_start";

    WheelStartupTestPrefs(Context context) {
        prefs = context.createDeviceProtectedStorageContext().getSharedPreferences("radio_plus_startup", Context.MODE_PRIVATE);
        autoStart = prefs.contains(AUTO) ? prefs.getBoolean(AUTO, false) : null;
        wheel = prefs.contains(WHEEL) ? prefs.getBoolean(WHEEL, false) : null;
        if (!prefs.edit().remove(AUTO).remove(WHEEL).commit()) throw new AssertionError("startup prefs");
    }

    @Override public void close() {
        SharedPreferences.Editor edit = prefs.edit();
        if (autoStart == null) edit.remove(AUTO); else edit.putBoolean(AUTO, autoStart);
        if (wheel == null) edit.remove(WHEEL); else edit.putBoolean(WHEEL, wheel);
        if (!edit.commit()) throw new AssertionError("restore startup prefs");
    }
}
