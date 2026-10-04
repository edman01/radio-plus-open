package fi.radioplus.app;

import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

/** Emulator preview verifies diagnostic consent/lifecycle, without any OEM tuner. */
public final class SteeringDiagnosticsUiTest {
    private static final long TIMEOUT_MS = 5_000L;
    private static final String[] PREFERENCES = {
            "radio_plus_favorites", "radio_plus_station_catalog",
            "radio_plus_navigation", "radio_plus_migrations", "radio_plus_ui"
    };
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Map<String, Map<String, ?>> savedPreferences = new HashMap<>();
    private final SteeringDiagnosticTrace capture = SteeringDiagnosticTrace.get();
    private Context context;
    private MainActivity activity;
    private SteeringDiagnosticsDialog dialog;
    private Dialog focusOwner;
    private boolean ownsCapture;

    @Before public void setUp() {
        assumeTrue("Preview requires a debug build", BuildConfig.DEBUG);
        assumeFalse("Use a stock Android emulator", hasVendorFramework());
        context = instrumentation.getTargetContext();
        assertNull("Stop the test app before running diagnostic instrumentation",
                field(RadioPlaybackService.class, "runningInstance", null));
        assertFalse("Do not replace an existing diagnostic recording", capture.isRecording());
        capture.clear();
        ownsCapture = true;
        for (String name : PREFERENCES) {
            SharedPreferences preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE);
            savedPreferences.put(name, new HashMap<>(preferences.getAll()));
            assertTrue(preferences.edit().clear().commit());
        }
        startPreviewActivity();
    }

    @After public void tearDown() {
        try {
            instrumentation.runOnMainSync(() -> {
                if (focusOwner != null) focusOwner.dismiss();
                if (dialog != null) dialog.dismiss();
                if (activity != null) activity.finish();
            });
            instrumentation.waitForIdleSync();
        } finally {
            if (ownsCapture) capture.clear();
            if (context != null) {
                for (Map.Entry<String, Map<String, ?>> entry : savedPreferences.entrySet()) {
                    restorePreferences(entry.getKey(), entry.getValue());
                }
                if (!savedPreferences.isEmpty()) StationWidgetProvider.requestDataRefresh(context);
            }
        }
    }

    @Test public void captureRequiresStartAndStopFreezesUnknownRawKeysUntilCloseClears() {
        assertFalse(capture.isRecording());
        assertActionEnabled("start", true);
        assertActionEnabled("stop", false);
        assertActionEnabled("previous", false);
        assertActionEnabled("next", false);
        sendUnknownRawPress();
        assertEmptyCapture();

        clickAction("start");
        assertTrue(capture.isRecording());
        assertActionEnabled("start", false);
        assertActionEnabled("stop", true);
        sendUnknownRawPress();
        String recorded = capture.report();
        assertTrue(recorded.contains("KEY source=test-window code=0 action=0 meta=0 repeat=0"));
        assertTrue(recorded.contains("KEY source=test-window code=0 action=1 meta=0 repeat=0"));
        assertTrue(recorded.contains("scan=285 flags=8 input=257"));
        assertTrue(recorded.contains("keys=2 events=0 total=2 retained=2 dropped=0"));
        assertNoHardwareDispatch();

        clickAction("stop");
        assertFalse(capture.isRecording());
        assertActionEnabled("start", true);
        assertActionEnabled("stop", false);
        String stopped = capture.report();
        sendUnknownRawPress();
        assertEquals("Stopped diagnostic data must be an immutable snapshot", stopped, capture.report());
        clickClose();
        assertEmptyCapture();
        assertNull("The owning activity must release its dialog reference",
                field(MainActivity.class, "steeringDiagnosticsDialog", activity));
        assertNoHardwareDispatch();
    }

    @Test public void previewTestButtonsRecordTechnicalEventsWithoutTuning() {
        clickAction("start");
        clickAction("next");
        clickAction("previous");
        String report = capture.report();
        assertTrue(report.contains("EVENT source=test-button detail=next"));
        assertTrue(report.contains("EVENT source=test-button detail=previous"));
        assertTrue(report.contains("preview: hardware dispatch disabled"));
        assertTrue(report.contains("keys=0 events=4 total=4 retained=4 dropped=0"));
        assertNoHardwareDispatch();
    }

    @Test public void finnishDiagnosticControlsRemainLocalizedAndFunctional() {
        instrumentation.runOnMainSync(() -> {
            dialog.dismiss();
            activity.finish();
        });
        instrumentation.waitForIdleSync();
        AppLanguage.set(context, "fi");
        startPreviewActivity();
        String[] labels = new String[4];
        instrumentation.runOnMainSync(() -> {
            labels[0] = ((Button) field(SteeringDiagnosticsDialog.class, "start", dialog)).getText().toString();
            labels[1] = ((Button) field(SteeringDiagnosticsDialog.class, "stop", dialog)).getText().toString();
            labels[2] = ((Button) field(SteeringDiagnosticsDialog.class, "previous", dialog)).getText().toString();
            labels[3] = ((Button) field(SteeringDiagnosticsDialog.class, "next", dialog)).getText().toString();
        });
        assertArrayEquals(new String[]{"Aloita 30 s", "Lopeta", "Testaa edellinen", "Testaa seuraava"}, labels);
        clickAction("start");
        sendUnknownRawPress();
        clickAction("stop");
        assertTrue(capture.report().contains("KEY source=test-window code=0"));
        assertNoHardwareDispatch();
        clickClose();
        assertEmptyCapture();
    }

    @Test public void losingDialogWindowFocusStopsCaptureAndDoesNotResumeAutomatically() {
        clickAction("start");
        sendUnknownRawPress();
        instrumentation.runOnMainSync(() -> {
            focusOwner = new Dialog(activity);
            TextView content = new TextView(activity);
            content.setText("Diagnostic focus test");
            focusOwner.setContentView(content);
            focusOwner.show();
        });
        await(() -> !capture.isRecording(), "A second window must stop the diagnostic capture");
        String stopped = capture.report();
        assertTrue(stopped.contains("status=stopped"));
        assertTrue(stopped.contains("source=test-window"));
        instrumentation.runOnMainSync(focusOwner::dismiss);
        awaitDialogFocus();
        sendUnknownRawPress();
        assertFalse("Returning focus must require explicit opt-in again", capture.isRecording());
        assertEquals(stopped, capture.report());
        assertNoHardwareDispatch();
    }

    @Test public void backgroundingTheActivityStopsCapture() {
        clickAction("start");
        sendUnknownRawPress();
        boolean[] moved = {false};
        instrumentation.runOnMainSync(() -> moved[0] = activity.moveTaskToBack(true));
        assertTrue("The preview activity must move to the background", moved[0]);
        await(() -> !capture.isRecording(), "Backgrounding must stop diagnostic capture");
        String stopped = capture.report();
        capture.key("after-background", 87, 0, 0, 0);
        assertEquals("Background input must not be collected", stopped, capture.report());
        assertNoHardwareDispatch();
    }

    @Test public void activityRecreationDismissesItsOwnedDialogAndClearsAllRecordedData() {
        clickAction("start");
        sendUnknownRawPress();
        MainActivity old = activity;
        SteeringDiagnosticsDialog oldDialog = dialog;
        instrumentation.runOnMainSync(old::recreate);
        MainActivity[] recreated = {null};
        await(() -> {
            instrumentation.runOnMainSync(() -> {
                for (Activity candidate : ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED)) {
                    if (candidate instanceof MainActivity && candidate != old) {
                        recreated[0] = (MainActivity) candidate;
                    }
                }
            });
            return recreated[0] != null;
        }, "MainActivity must recreate");
        activity = recreated[0];
        instrumentation.waitForIdleSync();
        assertFalse(oldDialog.isShowing());
        assertNull(field(MainActivity.class, "steeringDiagnosticsDialog", old));
        assertNull(field(MainActivity.class, "steeringDiagnosticsDialog", activity));
        assertEmptyCapture();
        showOwnedDialog();
        assertFalse("A new dialog must not resume the previous capture", capture.isRecording());
        assertActionEnabled("start", true);
        assertNoHardwareDispatch();
    }

    private void showOwnedDialog() {
        instrumentation.runOnMainSync(() -> {
            invoke(activity, "showSteeringDiagnostics");
            dialog = (SteeringDiagnosticsDialog)
                    field(MainActivity.class, "steeringDiagnosticsDialog", activity);
        });
        assertNotNull(dialog);
        instrumentation.waitForIdleSync();
        awaitDialogFocus();
    }

    private void startPreviewActivity() {
        activity = (MainActivity) instrumentation.startActivitySync(
                new Intent(context, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        .putExtra("preview", true));
        instrumentation.waitForIdleSync();
        assertTrue((Boolean) field(MainActivity.class, "debugPreview", activity));
        showOwnedDialog();
    }

    private void awaitDialogFocus() {
        await(() -> {
            boolean[] focused = {false};
            instrumentation.runOnMainSync(() -> focused[0] = dialog.isShowing()
                    && dialog.getWindow() != null && dialog.getWindow().getDecorView().hasWindowFocus());
            return focused[0];
        }, "The diagnostic dialog must own window focus");
    }

    private void sendUnknownRawPress() {
        long down = SystemClock.uptimeMillis();
        instrumentation.runOnMainSync(() -> {
            for (int action : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
                dialog.dispatchKeyEvent(new KeyEvent(down, down + action, action,
                        KeyEvent.KEYCODE_UNKNOWN, 0, 0, -1, 285,
                        KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD));
            }
        });
        instrumentation.waitForIdleSync();
    }

    private void clickAction(String name) {
        boolean[] clicked = {false};
        instrumentation.runOnMainSync(() -> {
            Button button = (Button) field(SteeringDiagnosticsDialog.class, name, dialog);
            clicked[0] = button.isEnabled() && button.performClick();
        });
        assertTrue("Diagnostic action must be enabled and clickable: " + name, clicked[0]);
        instrumentation.waitForIdleSync();
    }

    private void assertActionEnabled(String name, boolean expected) {
        boolean[] enabled = {false};
        instrumentation.runOnMainSync(() -> enabled[0] = ((Button)
                field(SteeringDiagnosticsDialog.class, name, dialog)).isEnabled());
        assertEquals(name, expected, enabled[0]);
    }

    private void clickClose() {
        boolean[] clicked = {false};
        instrumentation.runOnMainSync(() -> {
            Button close = findButton(dialog.getWindow().getDecorView(),
                    activity.getString(R.string.settings_back));
            clicked[0] = close != null && close.performClick();
        });
        assertTrue("Close button must dismiss the dialog", clicked[0]);
        instrumentation.waitForIdleSync();
        assertFalse(dialog.isShowing());
    }

    private static Button findButton(View view, String label) {
        if (view instanceof Button && label.contentEquals(((Button) view).getText())) {
            return (Button) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                Button match = findButton(group.getChildAt(index), label);
                if (match != null) return match;
            }
        }
        return null;
    }

    private void assertEmptyCapture() {
        assertFalse(capture.isRecording());
        String report = capture.report();
        assertTrue(report.contains("status=inactive"));
        assertTrue(report.contains("keys=0 events=0 total=0 retained=0 dropped=0"));
        assertFalse(report.contains("\n+"));
    }

    private void assertNoHardwareDispatch() {
        assertNull("Diagnostics in preview must not start the playback service",
                field(RadioPlaybackService.class, "runningInstance", null));
        RadioServiceClient.RadioState[] state = {null};
        instrumentation.runOnMainSync(() -> state[0] = (RadioServiceClient.RadioState)
                field(MainActivity.class, "currentState", activity));
        assertNotNull(state[0]);
        assertEquals(0, state[0].band);
        assertEquals("Unknown keys/test controls must not tune even the preview", 98_100, state[0].frequency);
    }

    private static void await(BooleanSupplier condition, String message) {
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(20L);
        }
        assertTrue(message, condition.getAsBoolean());
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

    private static void invoke(Object target, String name) {
        try {
            Method method = target.getClass().getDeclaredMethod(name);
            method.setAccessible(true);
            method.invoke(target);
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
                // A physical head unit is outside this fixture's scope.
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
