package fi.radioplus.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.SystemClock;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import android.widget.Button;
import android.widget.TextView;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import java.lang.reflect.Field;
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

/** Stock-emulator preview only; browser launches are intercepted before leaving the app. */
public final class AboutAppUiTest {
    private static final String PROJECT_URL = "https://github.com/edman01/radio-plus-open";
    private static final String[] PREFERENCES = {
            "radio_plus_favorites", "radio_plus_station_catalog",
            "radio_plus_navigation", "radio_plus_migrations", "radio_plus_ui"
    };
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Map<String, Map<String, ?>> savedPreferences = new HashMap<>();
    private Context context;
    private MainActivity activity;
    private AlertDialog about;
    private BrowserMonitor browserMonitor;

    @Before public void setUp() {
        assumeTrue("Preview requires a debug build", BuildConfig.DEBUG);
        assumeFalse("Use a stock Android emulator", hasVendorFramework());
        context = instrumentation.getTargetContext();
        assertNull("Stop playback before running this preview fixture",
                field(RadioPlaybackService.class, "runningInstance", null));
        for (String name : PREFERENCES) {
            SharedPreferences preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE);
            savedPreferences.put(name, new HashMap<>(preferences.getAll()));
            assertTrue(preferences.edit().clear().commit());
        }
        startPreview();
    }

    @After public void tearDown() {
        if (browserMonitor != null) instrumentation.removeMonitor(browserMonitor);
        try {
            instrumentation.runOnMainSync(() -> {
                if (about != null) about.dismiss();
                if (activity != null) activity.finish();
            });
            instrumentation.waitForIdleSync();
        } finally {
            for (Map.Entry<String, Map<String, ?>> entry : savedPreferences.entrySet()) {
                restorePreferences(entry.getKey(), entry.getValue());
            }
            if (!savedPreferences.isEmpty()) StationWidgetProvider.requestDataRefresh(context);
        }
    }

    @Test public void settingsGeneralAboutShowsLocalizedVersionAndProjectWithoutTruncation() {
        for (String language : AppLanguage.codes()) {
            AppLanguage.set(context, language);
            Context localized = AppLanguage.wrap(context);
            for (int id : new int[]{R.string.settings_about, R.string.about_title,
                    R.string.about_open_github, R.string.about_browser_unavailable,
                    R.string.settings_back}) {
                assertFalse(language + ": empty About label", localized.getString(id).trim().isEmpty());
            }
            assertTrue(localized.getString(R.string.about_version, BuildConfig.VERSION_NAME)
                    .contains(BuildConfig.VERSION_NAME));
        }
        String englishTitle = null;
        for (String language : new String[]{"en", "fi"}) {
            instrumentation.runOnMainSync(activity::finish);
            instrumentation.waitForIdleSync();
            AppLanguage.set(context, language);
            startPreview();
            openAboutThroughSettings();
            if (englishTitle == null) englishTitle = activity.getString(R.string.about_title);
            else assertNotEquals("Finnish title must be translated", englishTitle,
                    activity.getString(R.string.about_title));
            instrumentation.runOnMainSync(() -> {
                assertEquals(language, activity.getResources().getConfiguration()
                        .getLocales().get(0).getLanguage());
                TextView title = findText(about.getWindow().getDecorView(),
                        activity.getString(R.string.about_title));
                assertNotNull("Localized About title", title);
                TextView message = about.findViewById(android.R.id.message);
                assertEquals(activity.getString(R.string.about_version, BuildConfig.VERSION_NAME)
                        + "\n\n" + PROJECT_URL, message.getText().toString());
                assertTextFits(title);
                assertTextFits(message); // Full measured text may live inside a scrolling viewport.
                assertEquals(activity.getString(R.string.about_open_github),
                        about.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
                assertEquals(activity.getString(R.string.settings_back),
                        about.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
                assertTextFits(about.getButton(AlertDialog.BUTTON_POSITIVE));
                assertTextFits(about.getButton(AlertDialog.BUTTON_NEGATIVE));
            });
            clickAboutButton(AlertDialog.BUTTON_NEGATIVE);
            assertFalse(about.isShowing());
            assertNull(field(MainActivity.class, "aboutDialog", activity));
            assertTrue(((RadioSettingsDialog) field(MainActivity.class,
                    "settingsDialog", activity)).isShowing());
        }
        assertNoPlayback();
    }

    @Test public void githubRequiresATapUsesCanonicalHttpsAndHandlesMissingBrowser() {
        browserMonitor = new BrowserMonitor();
        instrumentation.addMonitor(browserMonitor);
        openAboutThroughSettings();
        assertEquals("Opening About must not launch a browser", 0, browserMonitor.launches);
        browserMonitor.unavailable = true;
        clickAboutButton(AlertDialog.BUTTON_POSITIVE);
        assertEquals(1, browserMonitor.launches);
        assertTrue("Missing browser must leave About available", about.isShowing());
        assertSame(about, field(MainActivity.class, "aboutDialog", activity));
        browserMonitor.unavailable = false;
        clickAboutButton(AlertDialog.BUTTON_POSITIVE);
        assertEquals(2, browserMonitor.launches);
        assertEquals(Intent.ACTION_VIEW, browserMonitor.intent.getAction());
        assertEquals("https", browserMonitor.intent.getData().getScheme());
        assertEquals(PROJECT_URL, browserMonitor.intent.getDataString());
        assertFalse(about.isShowing());
        assertNull(field(MainActivity.class, "aboutDialog", activity));
        assertFalse(activity.isFinishing());
        assertNoPlayback();
    }

    @Test public void activityRecreationDismissesAndReleasesItsAboutDialog() {
        openAboutThroughSettings();
        MainActivity old = activity;
        AlertDialog oldAbout = about;
        MainActivity[] recreated = {null};
        instrumentation.runOnMainSync(old::recreate);
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
        assertFalse(oldAbout.isShowing());
        assertNull(field(MainActivity.class, "aboutDialog", old));
        assertNull("Recreation must not reopen About", field(MainActivity.class, "aboutDialog", activity));
        openAboutThroughSettings();
        assertNotSame(oldAbout, about);
        assertNoPlayback();
    }

    @Test public void visibleBackButtonsReturnToSettingsThenRadioWithoutStartingPlayback() {
        assumeTrue("Window inspection requires API 29+", android.os.Build.VERSION.SDK_INT >= 29);
        openAboutThroughSettings();
        RadioSettingsDialog settings = (RadioSettingsDialog) field(MainActivity.class,
                "settingsDialog", activity);
        clickAboutButton(AlertDialog.BUTTON_NEGATIVE);
        for (int id : new int[]{R.id.settings_language, R.id.settings_steering}) {
            clickSettingsAction(settings, id);
            clickChildBack();
            assertTrue("Child Back returns to settings", settings.isShowing());
            assertNotNull("General category preserved", settings.findViewById(R.id.settings_language));
        }
        instrumentation.runOnMainSync(() -> {
            TextView radio = findText(settings.getWindow().getDecorView(),
                    activity.getString(R.string.settings_radio));
            assertNotNull(radio);
            assertTrue(radio.performClick());
        });
        instrumentation.waitForIdleSync();
        clickSettingsAction(settings, R.id.settings_sensitivity);
        clickChildBack();
        assertTrue(settings.isShowing());
        assertNotNull("Radio category preserved", settings.findViewById(R.id.settings_sensitivity));
        instrumentation.runOnMainSync(() -> {
            Button back = settings.findViewById(R.id.settings_close);
            assertEquals(activity.getString(R.string.settings_back), back.getText().toString());
            assertTextFits(back);
            assertTrue(back.performClick());
        });
        instrumentation.waitForIdleSync();
        assertFalse("Root Back returns to radio", settings.isShowing());
        assertFalse(activity.isFinishing());
        assertNoPlayback();
    }

    private void clickSettingsAction(RadioSettingsDialog settings, int id) {
        instrumentation.runOnMainSync(() -> {
            View action = settings.findViewById(id);
            assertNotNull(action);
            action.requestRectangleOnScreen(new Rect(0, 0, action.getWidth(), action.getHeight()), true);
        });
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            View action = settings.findViewById(id);
            assertTrue("Settings action must be visible", action.getGlobalVisibleRect(new Rect()));
            assertTrue(action.performClick());
        });
        instrumentation.waitForIdleSync();
    }

    private void clickChildBack() {
        instrumentation.runOnMainSync(() -> {
            Button back = null;
            for (View root : WindowInspector.getGlobalWindowViews()) {
                Button candidate = root.findViewById(android.R.id.button2);
                if (candidate != null && candidate.isShown()) back = candidate;
            }
            assertNotNull("Visible child Back button", back);
            assertEquals(activity.getString(R.string.settings_back), back.getText().toString());
            assertTextFits(back);
            assertTrue(back.performClick());
        });
        instrumentation.waitForIdleSync();
    }

    private void startPreview() {
        activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("preview", true));
        instrumentation.waitForIdleSync();
        assertTrue((Boolean) field(MainActivity.class, "debugPreview", activity));
    }

    private void openAboutThroughSettings() {
        instrumentation.runOnMainSync(() -> assertTrue(activity.findViewById(R.id.more_button).performClick()));
        instrumentation.waitForIdleSync();
        RadioSettingsDialog settings = (RadioSettingsDialog) field(MainActivity.class, "settingsDialog", activity);
        assertNotNull(settings);
        instrumentation.runOnMainSync(() -> {
            TextView general = findText(settings.getWindow().getDecorView(), activity.getString(R.string.settings_general));
            assertNotNull("General category", general);
            assertTrue(general.performClick());
        });
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            View action = settings.findViewById(R.id.settings_about);
            assertNotNull("About entry belongs to General", action);
            action.requestRectangleOnScreen(new Rect(0, 0, action.getWidth(), action.getHeight()), true);
        });
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            View action = settings.findViewById(R.id.settings_about);
            assertTrue("About entry must be reachable by scrolling", action.getGlobalVisibleRect(new Rect()));
            assertTrue(action.performClick());
            about = (AlertDialog) field(MainActivity.class, "aboutDialog", activity);
        });
        assertNotNull(about);
        instrumentation.waitForIdleSync();
        await(() -> {
            boolean[] laidOut = {false};
            instrumentation.runOnMainSync(() -> {
                TextView message = about.findViewById(android.R.id.message);
                laidOut[0] = about.isShowing() && message != null && message.getLayout() != null
                        && about.getButton(AlertDialog.BUTTON_POSITIVE).getWidth() > 0;
            });
            return laidOut[0];
        }, "About must be laid out");
        assertTrue("Settings remains available behind About", settings.isShowing());
    }

    private void clickAboutButton(int which) {
        instrumentation.runOnMainSync(() -> assertTrue(about.getButton(which).performClick()));
        instrumentation.waitForIdleSync();
    }

    private void assertNoPlayback() {
        assertNull("About must not start playback", field(RadioPlaybackService.class, "runningInstance", null));
    }

    private static void assertTextFits(TextView text) {
        Layout layout = text.getLayout();
        assertNotNull("Text must have a layout: " + text.getText(), layout);
        assertTrue("Text height: " + text.getText(), layout.getHeight() <= text.getHeight()
                - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom() + 2);
        for (int line = 0; line < layout.getLineCount(); line++) {
            assertEquals("Text ellipsis: " + text.getText(), 0, layout.getEllipsisCount(line));
            assertTrue("Text width: " + text.getText(), layout.getLineWidth(line) <= layout.getWidth() + 2);
        }
    }

    private static TextView findText(View view, String label) {
        if (view instanceof TextView && label.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                TextView match = findText(group.getChildAt(index), label);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static final class BrowserMonitor extends Instrumentation.ActivityMonitor {
        volatile int launches;
        volatile boolean unavailable;
        volatile Intent intent;

        @Override public Instrumentation.ActivityResult onStartActivity(Intent launched) {
            intent = new Intent(launched);
            launches++;
            if (unavailable) throw new ActivityNotFoundException("Test fixture: browser unavailable");
            // Block every start while installed, including unexpected non-browser intents.
            return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
        }
    }

    private static void await(BooleanSupplier condition, String message) {
        long deadline = SystemClock.elapsedRealtime() + 5_000L;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20L);
        assertTrue(message, condition.getAsBoolean());
    }

    private static Object field(Class<?> type, String name, Object target) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException error) { throw new AssertionError(name, error); }
    }

    private static boolean hasVendorFramework() {
        for (String name : new String[]{"android.radio.RadioPlayer", "android.sourceservice.SourceInfo"}) {
            try { Class.forName(name); return true; }
            catch (ClassNotFoundException ignored) { /* Stock emulator only. */ }
        }
        return false;
    }

    private void restorePreferences(String name, Map<String, ?> values) {
        SharedPreferences.Editor editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear();
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
