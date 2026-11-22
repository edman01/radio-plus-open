package fi.radioplus.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.GridView;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
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

    @Test public void headerSkipClearsAnEarlierTileSelection() {
        click(R.id.stations_button);
        instrumentation.runOnMainSync(() -> {
            setField("pendingTuneKey", "0:92400");
            setField("pendingTuneAt", SystemClock.elapsedRealtime());
            setField("favoritePageManuallySelected", true);
            setField("playbackPausedByStationTap", true);
        });
        click(R.id.seek_up_button);
        assertPreviewFrequency(99_700);
        assertEquals("0:99700", field(MainActivity.class, "activeFavoriteKey", activity));
        assertEquals("", field(MainActivity.class, "pendingTuneKey", activity));
        assertEquals(0L, field(MainActivity.class, "pendingTuneAt", activity));
        assertEquals(false, field(MainActivity.class, "favoritePageManuallySelected", activity));
        assertEquals(false, field(MainActivity.class, "playbackPausedByStationTap", activity));
    }

    @Test public void listeningToUnnamedCatalogTilesDoesNotOpenTheEditorUnlessRenameIsChosen() {
        FavoriteStation first = new FavoriteStation(0, 88600, "");
        FavoriteStation next = new FavoriteStation(0, 98400, "");
        instrumentation.runOnMainSync(() -> {
            new StationStore(context).replaceOrder(java.util.Arrays.asList(first, next));
            invoke(activity, "showStationCatalog", new Class<?>[]{boolean.class}, false);
        });
        tapTile(0);
        assertPreviewFrequency(first.frequency);
        assertListeningWithoutDialog();
        click(R.id.seek_up_button);
        assertPreviewFrequency(next.frequency);
        assertListeningWithoutDialog();

        // An intentional long press and Rename still open the real name editor.
        long down = SystemClock.uptimeMillis();
        gesture(down, MotionEvent.ACTION_DOWN, 1);
        SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout() + 100L);
        gesture(down, MotionEvent.ACTION_UP, 1);
        String rename = AppLanguage.text(context, "Nimeä uudelleen", "Rename");
        assertTrue(findDialogText(rename).performAction(AccessibilityNodeInfo.ACTION_CLICK));
        String title = AppLanguage.text(context, "Nimeä ", "Name ") + next.frequencyLabel();
        assertNotNull(findDialogText(title));
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
    }

    private AccessibilityNodeInfo findDialogText(String text) {
        long end = SystemClock.uptimeMillis() + TIMEOUT_MS;
        do {
            AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text))
                    if (text.equals(String.valueOf(node.getText()))) return node;
            }
            SystemClock.sleep(40);
        } while (SystemClock.uptimeMillis() < end);
        throw new AssertionError("Dialog did not show: " + text);
    }
    private void tapTile(int index) {
        long down = SystemClock.uptimeMillis();
        gesture(down, MotionEvent.ACTION_DOWN, index);
        gesture(down, MotionEvent.ACTION_UP, index);
    }

    private void assertListeningWithoutDialog() {
        SystemClock.sleep(500L); // longer than the old preview rename timer
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> assertTrue("Tuning must leave focus in the station list",
                activity.hasWindowFocus()));
    }

    @Test public void favoritesDragCanContinueAfterAnEdgeReleaseWithoutReopeningTheMenu() {
        ArrayList<FavoriteStation> stations = startReorder(true, 1);
        long down = SystemClock.uptimeMillis();
        gesture(down, MotionEvent.ACTION_DOWN, 1);
        gesture(down, MotionEvent.ACTION_MOVE, RIGHT_EDGE);
        assertPage(1);
        // A finger resting on the edge must not race ahead another page.
        SystemClock.sleep(600);
        gesture(down, MotionEvent.ACTION_MOVE, RIGHT_EDGE);
        assertPage(1);
        gesture(down, MotionEvent.ACTION_UP, RIGHT_EDGE);
        assertEquals(true, field(MainActivity.class, "favoriteReordering", activity));
        // A tuner metadata refresh during the drag must not reload the old order.
        instrumentation.runOnMainSync(() -> invoke(activity, "refreshFavorites", new Class<?>[]{}));
        long secondDown = SystemClock.uptimeMillis();
        gesture(secondDown, MotionEvent.ACTION_DOWN, 0);
        gesture(secondDown, MotionEvent.ACTION_MOVE, 3);
        gesture(secondDown, MotionEvent.ACTION_UP, 3);
        assertEquals(false, field(MainActivity.class, "favoriteReordering", activity));
        FavoriteStation moved = stations.remove(1);
        stations.add(9, moved);
        assertEquals(stations, new FavoriteStore(context).load());
        assertFalse(stations.equals(new StationStore(context).load()));
    }

    @Test public void catalogDragSurvivesTouchCancellationOnThePreviousPage() {
        ArrayList<FavoriteStation> stations = startReorder(false, 8);
        long down = SystemClock.uptimeMillis();
        gesture(down, MotionEvent.ACTION_DOWN, 2);
        gesture(down, MotionEvent.ACTION_MOVE, LEFT_EDGE);
        assertPage(0);
        gesture(down, MotionEvent.ACTION_CANCEL, LEFT_EDGE);
        assertEquals(true, field(MainActivity.class, "favoriteReordering", activity));
        // A fresh touch can drop directly on the wanted slot, without a MOVE.
        long secondDown = SystemClock.uptimeMillis();
        gesture(secondDown, MotionEvent.ACTION_DOWN, 2);
        gesture(secondDown, MotionEvent.ACTION_UP, 2);
        FavoriteStation moved = stations.remove(8);
        stations.add(2, moved);
        assertEquals(stations, new StationStore(context).load());
        assertFalse(stations.equals(new FavoriteStore(context).load()));
    }

    @Test public void oneContinuousDragCanDropOnAPartiallyFilledLastPage() {
        ArrayList<FavoriteStation> stations = startReorder(true, 7);
        long down = SystemClock.uptimeMillis();
        gesture(down, MotionEvent.ACTION_DOWN, 1);
        gesture(down, MotionEvent.ACTION_MOVE, RIGHT_EDGE);
        assertPage(2);
        gesture(down, MotionEvent.ACTION_MOVE, 1);
        gesture(down, MotionEvent.ACTION_UP, 1);
        FavoriteStation moved = stations.remove(7);
        stations.add(13, moved);
        assertEquals(stations, new FavoriteStore(context).load());
        assertEquals(false, field(MainActivity.class, "favoriteReordering", activity));
        assertNull(field(RadioPlaybackService.class, "runningInstance", null));
    }

    private static final int LEFT_EDGE = -1, RIGHT_EDGE = -2;

    private ArrayList<FavoriteStation> startReorder(boolean favorites, int sourceIndex) {
        ArrayList<FavoriteStation> stations = new ArrayList<>();
        for (int i = 0; i < 14; i++) stations.add(new FavoriteStation(0, 90000 + i * 100, "Preset " + i));
        instrumentation.runOnMainSync(() -> {
            new FavoriteStore(context).replaceOrder(stations);
            new StationStore(context).replaceOrder(stations);
            invoke(activity, "showStationCatalog", new Class<?>[]{boolean.class}, favorites);
            setField("favoritePage", sourceIndex / 6);
            invoke(activity, "renderFavoritePage", new Class<?>[]{});
            invoke(activity, "beginFavoriteReorder", new Class<?>[]{String.class}, stations.get(sourceIndex).key());
        });
        instrumentation.waitForIdleSync();
        return stations;
    }

    private void assertPage(int expected) {
        assertEquals(expected, field(MainActivity.class, "favoritePage", activity));
    }

    private void gesture(long down, int action, int slot) {
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            GridView grid = activity.findViewById(R.id.favorites_list);
            View tile = grid.getChildAt(Math.max(slot, 0));
            assertNotNull("A real rendered station card is required", tile);
            float x = slot == LEFT_EDGE ? 2 : slot == RIGHT_EDGE ? grid.getWidth()-2 :
                    (tile.getLeft()+tile.getRight()) / 2f;
            float y = (tile.getTop()+tile.getBottom()) / 2f;
            MotionEvent e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
            try { assertTrue(grid.dispatchTouchEvent(e)); } finally { e.recycle(); }
        });
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
