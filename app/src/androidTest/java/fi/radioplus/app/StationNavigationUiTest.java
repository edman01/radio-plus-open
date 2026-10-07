package fi.radioplus.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

/** Tests UI/list-context persistence in debug preview, not physical tuner behavior. */
public final class StationNavigationUiTest {
    private static final long TIMEOUT_MS = 5_000L;
    private static final String[] PREFERENCES = {
            "radio_plus_favorites", "radio_plus_station_catalog",
            "radio_plus_navigation", "radio_plus_migrations"
    };
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Map<String, Map<String, ?>> savedPreferences = new HashMap<>();
    private Context context;
    private MainActivity activity;
    private Field detectionField;
    private Object previousDetection;

    @Before public void setUp() throws Exception {
        assumeTrue("Preview requires a debug build", BuildConfig.DEBUG);
        assumeFalse("Use a stock Android emulator", hasVendorFramework());
        detectionField = RadioApiFactory.class.getDeclaredField("latest");
        detectionField.setAccessible(true);
        previousDetection = detectionField.get(null);
        detectionField.set(null, new RadioApiFactory.Detection(RadioBackendProfile.HCN_CURRENT_31, "", "", ""));
        context = instrumentation.getTargetContext();
        assertNull("Stop the test app before running UI instrumentation",
                field(RadioPlaybackService.class, "runningInstance", null));
        for (String name : PREFERENCES) {
            SharedPreferences preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE);
            savedPreferences.put(name, new HashMap<>(preferences.getAll()));
            assertTrue(preferences.edit().clear().commit());
        }
        activity = (MainActivity) instrumentation.startActivitySync(
                new Intent(context, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        .putExtra("preview", true));
        instrumentation.waitForIdleSync();
        assertTrue((Boolean) field(MainActivity.class, "debugPreview", activity));
    }

    @After public void tearDown() throws Exception {
        try {
            if (activity != null) {
                instrumentation.runOnMainSync(activity::finish);
                instrumentation.waitForIdleSync();
            }
        } finally {
            if (detectionField != null) detectionField.set(null, previousDetection);
            if (context != null) {
                for (Map.Entry<String, Map<String, ?>> entry : savedPreferences.entrySet()) {
                    restorePreferences(entry.getKey(), entry.getValue());
                }
                if (!savedPreferences.isEmpty()) StationWidgetProvider.requestDataRefresh(context);
            }
        }
    }

    @Test public void listButtonsPersistSelectionAndRecreationKeepsCatalogNavigation() {
        assertSelectedList(true);
        assertPreviewFrequency(98_100);
        click(R.id.stations_button);
        assertSelectedList(false);
        click(R.id.seek_up_button);
        assertPreviewFrequency(99_700); // Catalog follows 98.1 with 99.7.
        click(R.id.seek_down_button);
        assertPreviewFrequency(98_100);

        recreateActivity();
        assertSelectedList(false);
        click(R.id.seek_up_button);
        assertPreviewFrequency(99_700);
        click(R.id.seek_down_button);
        assertPreviewFrequency(98_100);

        click(R.id.saved_button);
        assertSelectedList(true);
        click(R.id.seek_up_button);
        assertPreviewFrequency(92_400); // Favorites wrap after their last 98.1 row.
        click(R.id.seek_down_button);
        assertPreviewFrequency(98_100);
        recreateActivity();
        assertSelectedList(true);
        assertNull("Preview navigation must not start the OEM playback service",
                field(RadioPlaybackService.class, "runningInstance", null));
    }

    @Test public void automaticScanCompletionPersistsItsSwitchToTheCatalog() {
        assertSelectedList(true);
        // Start the actual preview scan lifecycle, then deliver its completion
        // immediately instead of waiting for the 15-second preview timer.
        // The shared scan-completion callback still handles completion.
        instrumentation.runOnMainSync(() -> {
            assertFalse("Preview must not need a tuner binding",
                    ((RadioServiceClient) field(MainActivity.class, "radioClient", activity)).isConnected());
            invoke(activity, "startAutoScan", new Class<?>[]{});
            invoke(activity, "finishAutoScan", new Class<?>[]{int.class}, 0);
        });
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        while (new StationNavigationStore(context).favoritesSelected()
                && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(20L);
        }
        instrumentation.waitForIdleSync();
        assertSelectedList(false);
        instrumentation.runOnMainSync(() -> {
            assertEquals("Preview completion must clear the finalizing state", false,
                    field(MainActivity.class, "autoScanFinalizing", activity));
            assertFalse("Preview completion must not create a tuner binding",
                    ((RadioServiceClient) field(MainActivity.class, "radioClient", activity)).isConnected());
        });
        recreateActivity();
        assertSelectedList(false);
        click(R.id.seek_up_button);
        assertPreviewFrequency(99_700);
        assertNull("Preview scan must not attach the OEM playback service",
                field(RadioPlaybackService.class, "runningInstance", null));
    }

    @Test public void headerSkipClearsAnEarlierTileSelectionAndRenamePrompt() {
        click(R.id.stations_button);
        android.os.Handler handler = (android.os.Handler)
                field(MainActivity.class, "mainHandler", activity);
        Runnable oldPrompt = () -> fail("The old station prompt must be canceled");
        instrumentation.runOnMainSync(() -> {
            setField("pendingTuneKey", "0:92400");
            setField("pendingTuneAt", SystemClock.elapsedRealtime());
            setField("favoritePageManuallySelected", true);
            setField("playbackPausedByStationTap", true);
            setField("pendingUnnamedStationPrompt", oldPrompt);
            handler.postDelayed(oldPrompt, 5_000L);
        });
        click(R.id.seek_up_button);
        assertPreviewFrequency(99_700);
        assertEquals("0:99700", field(MainActivity.class, "activeFavoriteKey", activity));
        assertEquals("", field(MainActivity.class, "pendingTuneKey", activity));
        assertEquals(0L, field(MainActivity.class, "pendingTuneAt", activity));
        assertEquals(false, field(MainActivity.class, "favoritePageManuallySelected", activity));
        assertEquals(false, field(MainActivity.class, "playbackPausedByStationTap", activity));
        assertNull(field(MainActivity.class, "pendingUnnamedStationPrompt", activity));
        assertFalse(handler.hasCallbacks(oldPrompt));
    }

    private void setField(String name, Object value) {
        try {
            Field field = MainActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(activity, value);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(name, error);
        }
    }

    private void click(int id) {
        boolean[] clicked = {false};
        instrumentation.runOnMainSync(() -> {
            View view = activity.findViewById(id);
            clicked[0] = view != null && view.isEnabled() && view.performClick();
        });
        assertTrue("Enabled UI button must have a click handler: " + id, clicked[0]);
        instrumentation.waitForIdleSync();
    }

    private void assertSelectedList(boolean favorites) {
        boolean[] selected = new boolean[2];
        instrumentation.runOnMainSync(() -> {
            selected[0] = activity.findViewById(R.id.saved_button).isSelected();
            selected[1] = activity.findViewById(R.id.stations_button).isSelected();
        });
        assertEquals("Visible Favorites selection", favorites, selected[0]);
        assertEquals("Visible Stations selection", !favorites, selected[1]);
        assertEquals("A fresh reader must observe the UI's saved navigation context",
                favorites, new StationNavigationStore(context).favoritesSelected());
    }

    private void assertPreviewFrequency(int expected) {
        RadioServiceClient.RadioState[] state = {null};
        instrumentation.runOnMainSync(() -> state[0] = (RadioServiceClient.RadioState)
                field(MainActivity.class, "currentState", activity));
        assertNotNull(state[0]);
        assertEquals("Preview band", 0, state[0].band);
        assertEquals("Preview frequency (hardware verified separately in MediaControlsTest)",
                expected, state[0].frequency);
    }

    private void recreateActivity() {
        MainActivity old = activity;
        instrumentation.runOnMainSync(old::recreate);
        MainActivity[] recreated = {null};
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        while (recreated[0] == null && SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync(() -> {
                for (Activity candidate : ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED)) {
                    if (candidate instanceof MainActivity && candidate != old) {
                        recreated[0] = (MainActivity) candidate;
                    }
                }
            });
            if (recreated[0] == null) SystemClock.sleep(20L);
        }
        assertNotNull("MainActivity must recreate", recreated[0]);
        activity = recreated[0];
        instrumentation.waitForIdleSync();
    }

    private static Object field(Class<?> type, String name, Object target) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(name, error);
        }
    }

    private static void invoke(Object target, String name, Class<?>[] signature, Object... arguments) {
        try {
            Method method = target.getClass().getDeclaredMethod(name, signature);
            method.setAccessible(true);
            method.invoke(target, arguments);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(name, error);
        }
    }

    private static boolean hasVendorFramework() {
        for (String name : new String[]{"android.radio.RadioPlayer", "android.sourceservice.SourceInfo"}) {
            try {
                Class.forName(name);
                return true;
            } catch (ClassNotFoundException ignored) {
                // This fixture deliberately excludes physical Junsun hardware.
            }
        }
        return false;
    }

    private void restorePreferences(String name, Map<String, ?> values) {
        SharedPreferences.Editor editor = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof Set) {
                @SuppressWarnings("unchecked") Set<String> strings = (Set<String>) value;
                editor.putStringSet(key, new HashSet<>(strings));
            } else throw new AssertionError("Unsupported preference type: " + key);
        }
        assertTrue("Restore preferences: " + name, editor.commit());
    }
}
