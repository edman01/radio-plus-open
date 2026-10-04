package fi.radioplus.app;

import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.Test;
import static org.junit.Assert.*;

/** Run on API 29+ emulator at each supported viewport; no physical tuner is required. */
public final class LanguageLayoutTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test public void allLanguagesTranslateResourcesAndDynamicText() {
        Context context = instrumentation.getTargetContext();
        String previous = AppLanguage.get(context);
        try {
            assertEquals(7, AppLanguage.codes().length);
            assertEquals(7, AppLanguage.names().length);
            for (String language : AppLanguage.codes()) {
                AppLanguage.set(context, language);
                assertEquals(language, AppLanguage.get(context));
                assertEquals(language, AppLanguage.wrap(context).getResources()
                        .getConfiguration().getLocales().get(0).getLanguage());
                for (java.util.Map.Entry<String, Integer> entry : TranslationCatalog.entries().entrySet()) {
                    String actual = AppLanguage.wrap(context).getString(entry.getValue());
                    assertFalse(language + ": " + entry.getKey(), actual.isEmpty());
                    // "Pause" is also the correct German and French spelling.
                    if (!language.equals("en") && !language.equals("fi")) {
                        if (!entry.getKey().equals("Pause"))
                            assertNotEquals(language + ": untranslated " + entry.getKey(), entry.getKey(), actual);
                        assertEquals(actual, AppLanguage.text(context, "unused", entry.getKey()));
                    }
                    assertEquals("placeholder count for " + entry.getKey(),
                            placeholders(entry.getKey()), placeholders(actual));
                }
                assertEquals("USER CUSTOM NAME", AppLanguage.stationName(context, "USER CUSTOM NAME"));
                assertEquals("101.7 MHz", AppLanguage.stationName(context, "101.7 MHz"));
                assertEquals(AppLanguage.displayName(context),
                        AppLanguage.names()[AppLanguage.selectedIndex(context)]);
            }
            AppLanguage.set(context, "pt-BR");
            assertEquals("pt", AppLanguage.get(context));
            AppLanguage.set(context, "unknown");
            assertEquals("en", AppLanguage.get(context));
        } finally { AppLanguage.set(context, previous); }
    }

    private List<String> placeholders(String value) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("%(?:[0-9]+\\$)?[ds]").matcher(value);
        List<String> result = new ArrayList<>();
        while (matcher.find()) result.add(matcher.group());
        return result;
    }

    @Test public void languagePickerPersistsSelectionAndRecreatesActivity() {
        if (android.os.Build.VERSION.SDK_INT < 29) return;
        Context context = instrumentation.getTargetContext();
        String previous = AppLanguage.get(context);
        Activity current = null;
        try {
            AppLanguage.set(context, "en");
            current = instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .putExtra("preview", true));
            for (int index : new int[]{2, 3, 4, 5, 6, 1, 0}) {
                Activity old = current;
                    instrumentation.runOnMainSync(() -> invoke(old, "showLanguageDialog", new Class<?>[]{}));
                    instrumentation.waitForIdleSync();
                    instrumentation.runOnMainSync(() -> {
                        android.widget.ListView list = null;
                        for (View root : WindowInspector.getGlobalWindowViews()) {
                            android.widget.ListView candidate = findList(root);
                            if (candidate != null) list = candidate;
                        }
                        assertNotNull("language list", list);
                        assertEquals(7, list.getAdapter().getCount());
                        View row = list.getAdapter().getView(index, null, list);
                        list.performItemClick(row, index, list.getAdapter().getItemId(index));
                    });
                    // Dismissing the picker can briefly resume the old activity before
                    // recreation. Wait for the new resumed instance, not that callback.
                    Activity[] recreated = {null};
                    long deadline = android.os.SystemClock.uptimeMillis() + 5000;
                    while (recreated[0] == null && android.os.SystemClock.uptimeMillis() < deadline) {
                        instrumentation.runOnMainSync(() -> {
                            for (Activity candidate : androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                                    .getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)) {
                                if (candidate instanceof MainActivity && candidate != old) recreated[0] = candidate;
                            }
                        });
                        if (recreated[0] == null) android.os.SystemClock.sleep(50);
                    }
                    current = recreated[0];
                    assertNotNull("activity recreated", current);
                    assertEquals(AppLanguage.codes()[index], AppLanguage.get(context));
                    assertEquals(AppLanguage.codes()[index], current.getResources()
                            .getConfiguration().getLocales().get(0).getLanguage());
            }
        } finally {
            if (current != null) {
                Activity last = current;
                instrumentation.runOnMainSync(last::finish);
            }
            AppLanguage.set(context, previous);
        }
    }

    private android.widget.ListView findList(View view) {
        if (view instanceof android.widget.ListView) return (android.widget.ListView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.ListView result = findList(group.getChildAt(i));
                if (result != null) return result;
            }
        }
        return null;
    }

    @Test public void translatedScreensFitViewport() throws Exception {
        if (android.os.Build.VERSION.SDK_INT < 29) return;
        Context context = instrumentation.getTargetContext();
        String previous = AppLanguage.get(context);
        try {
            for (String language : AppLanguage.codes()) {
                AppLanguage.set(context, language);
                Intent intent = new Intent(context, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        .putExtra("preview", true);
                Activity activity = instrumentation.startActivitySync(intent);
                try {
                    inspect(language + "-main");
                    RadioSettingsDialog[] settings = new RadioSettingsDialog[1];
                    instrumentation.runOnMainSync(() -> {
                        settings[0] = new RadioSettingsDialog(activity, false, new RadioSettingsDialog.Listener() {
                            public void onLanguage() {}
                            public void onSensitivity() {}
                            public void onSteeringKeys() {}
                            public void onAutoStartChanged(boolean enabled) {}
                        });
                        settings[0].show();
                    });
                    inspect(language + "-settings-radio");
                    instrumentation.runOnMainSync(() -> invoke(settings[0], "showCategory", new Class<?>[]{int.class}, 1));
                    inspect(language + "-settings-general");
                    instrumentation.runOnMainSync(() -> settings[0].dismiss());
                    Dialog[] station = new Dialog[1];
                    instrumentation.runOnMainSync(() -> {
                        String[] labels = {
                            AppLanguage.text(activity, "Poista suosikeista", "Remove from favorites"),
                            AppLanguage.text(activity, "Nimeä uudelleen", "Rename"),
                            AppLanguage.text(activity, "Vaihda logo", "Change logo"),
                            AppLanguage.text(activity, "Järjestä suosikkeja", "Reorder favorites")
                        };
                        station[0] = new StationOptionsDialog(activity, "Velora", "98.1 MHz", labels, true, action -> {});
                        station[0].show();
                    });
                    inspect(language + "-station");
                    instrumentation.runOnMainSync(() -> station[0].dismiss());
                    for (String method : new String[]{"showLanguageDialog", "showReceptionModeDialog",
                            "showStationActionsDialog", "showManualTuningDialog"}) {
                        instrumentation.runOnMainSync(() -> invoke(activity, method, new Class<?>[]{}));
                        inspect(language + "-" + method);
                        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
                        instrumentation.waitForIdleSync();
                    }
                } finally {
                    instrumentation.runOnMainSync(activity::finish);
                    instrumentation.waitForIdleSync();
                }
            }
        } finally { AppLanguage.set(context, previous); }
    }

    private static void invoke(Object target, String name, Class<?>[] signature, Object... args) {
        try {
            Method method = target.getClass().getDeclaredMethod(name, signature);
            method.setAccessible(true);
            method.invoke(target, args);
        } catch (Exception e) { throw new AssertionError(name, e); }
    }

    private void inspect(String name) throws Exception {
        instrumentation.waitForIdleSync();
        // Allow the next layout/draw following dialog show or configuration recreation.
        android.os.SystemClock.sleep(200);
        List<String> failures = new ArrayList<>();
        instrumentation.runOnMainSync(() -> {
            for (View root : WindowInspector.getGlobalWindowViews()) audit(root, failures);
        });
        Bundle args = InstrumentationRegistry.getArguments();
        if ("true".equals(args.getString("screenshots"))) {
            File directory = instrumentation.getTargetContext().getExternalFilesDir("language-audit");
            assertNotNull(directory);
            Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
            if (bitmap != null) {
                try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
                } finally { bitmap.recycle(); }
            }
        }
        assertTrue(name + ": " + failures, failures.isEmpty());
    }

    private void audit(View view, List<String> failures) {
        if (!view.isShown()) return;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            Rect visible = new Rect();
            if (text.getText().length() > 0 && text.getGlobalVisibleRect(visible)
                    && text.getWidth() > 4 && text.getHeight() > 4 && text.getLayout() != null) {
                android.text.Layout layout = text.getLayout();
                // Scrolling clips the viewport, not the measured text view; compare its full size.
                int space = text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom();
                if (layout.getHeight() > space + 2) failures.add("height: " + text.getText());
                for (int line = 0; line < layout.getLineCount(); line++) {
                    // User-provided station names intentionally ellipsize; audit translated UI labels.
                    if (layout.getEllipsisCount(line) > 0
                            && text.getId() != R.id.favorite_name)
                        failures.add("ellipsis: " + text.getText());
                    if (layout.getLineWidth(line) > layout.getWidth() + 2)
                        failures.add("width: " + text.getText());
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) audit(group.getChildAt(i), failures);
        }
    }
}
