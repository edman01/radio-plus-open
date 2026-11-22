package fi.radioplus.app;

import android.content.Context;
import android.content.res.Configuration;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListAdapter;
import android.widget.TextView;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.*;

public final class StationLogoDialogTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test public void removeUsesOnlyIconColorAndOnlyWhenAnImageIsPresent() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            FrameLayout parent = new FrameLayout(context);
            String name = BuildConfig.BUNDLED_STATION_LOGOS ? StationLogoResolver.BUILTIN_NAMES[0] : "Test";
            FavoriteStation station = new FavoriteStation(0, 97000, name);
            int remove = BuildConfig.BUNDLED_STATION_LOGOS ? 2 : 1;
            ListAdapter withFile = choices(station.withLogo("file:test.png"), true);
            assertTrue(withFile.isEnabled(remove));
            View row = withFile.getView(remove, null, parent);
            assertRemove(row, true);
            ListAdapter noLogo = choices(station.withLogo(StationLogoResolver.NO_LOGO), false);
            assertFalse(noLogo.isEnabled(remove));
            assertRemove(noLogo.getView(remove, row, parent), false);
            ListAdapter automatic = choices(station, false);
            assertEquals(BuildConfig.BUNDLED_STATION_LOGOS, automatic.isEnabled(remove));
            ListAdapter unknown = choices(new FavoriteStation(0, 97000, "ZZ test", "file:missing.png"), false);
            assertFalse(unknown.isEnabled(remove));
            if (!BuildConfig.BUNDLED_STATION_LOGOS) {
                assertEquals(2, noLogo.getCount());
                assertFalse(choices(station.withLogo("builtin:restored"), false).isEnabled(remove));
            }
            int add = remove - 1;
            View fileRow = withFile.getView(add, row, parent);
            assertEquals(View.VISIBLE, fileRow.findViewById(R.id.choice_hint).getVisibility());
            assertEquals(View.VISIBLE, fileRow.findViewById(R.id.choice_check).getVisibility());
            assertEquals(View.INVISIBLE, noLogo.getView(add, fileRow, parent)
                    .findViewById(R.id.choice_check).getVisibility());
        });
    }

    @Test public void catalogueRowsKeepOrderAndSelectionDoesNotLeakAcrossRecycledRows() {
        if (!BuildConfig.BUNDLED_STATION_LOGOS) return;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            FrameLayout parent = new FrameLayout(context);
            FavoriteStation station = new FavoriteStation(0, 97000, StationLogoResolver.BUILTIN_NAMES[0]);
            ListAdapter automatic = choices(station, false);
            assertEquals(StationLogoResolver.BUILTIN_NAMES.length + 4, automatic.getCount());
            assertFalse(automatic.isEnabled(3)); // The library heading is not an action.
            View row = automatic.getView(0, null, parent);
            assertEquals(View.VISIBLE, row.findViewById(R.id.choice_check).getVisibility());
            ListAdapter explicit = choices(station.withLogo(StationLogoResolver.BUILTIN_TOKENS[1]), false);
            row = explicit.getView(5, row, parent);
            assertEquals(StationLogoResolver.BUILTIN_NAMES[1], ((TextView)row.findViewById(R.id.choice_text)).getText().toString());
            assertEquals(View.VISIBLE, row.findViewById(R.id.choice_check).getVisibility());
            assertNotNull(((ImageView)row.findViewById(R.id.choice_icon)).getDrawable());
            row = explicit.getView(4, row, parent);
            assertEquals(StationLogoResolver.BUILTIN_NAMES[0], ((TextView)row.findViewById(R.id.choice_text)).getText().toString());
            assertEquals(View.INVISIBLE, row.findViewById(R.id.choice_check).getVisibility());
            assertEquals(View.GONE, row.findViewById(R.id.choice_hint).getVisibility());
            assertRemove(explicit.getView(2, row, parent), true);
        });
    }

    @Test public void helpAndCatalogueHeadingCoverEverySupportedLanguage() {
        for (String language : AppLanguage.codes()) {
            Configuration config = new Configuration(context.getResources().getConfiguration());
            config.setLocale(Locale.forLanguageTag(language));
            Context localized = context.createConfigurationContext(config);
            String hint = localized.getString(R.string.station_logo_image_hint);
            for (String part : new String[]{"PNG","JPG","WebP","400","240"}) assertTrue(language, hint.contains(part));
            assertTrue(language, localized.getString(R.string.station_logo_library, 8).contains("8"));
            if (!"en".equals(language)) assertNotEquals("Untranslated help: " + language,
                    "PNG, JPG or WebP · e.g. 400 × 240 px", hint);
        }
    }

    private ListAdapter choices(FavoriteStation s, boolean file) {
        return new StationLogoDialog.Choices(context, s, s.name, file);
    }

    private void assertRemove(View row, boolean enabled) {
        assertEquals(enabled, row.isEnabled());
        assertEquals(context.getColor(enabled ? R.color.danger : R.color.text_muted),
                ((ImageView)row.findViewById(R.id.choice_icon)).getImageTintList().getDefaultColor());
        assertEquals(context.getColor(enabled ? R.color.text_primary : R.color.text_muted),
                ((TextView)row.findViewById(R.id.choice_text)).getCurrentTextColor());
        assertEquals(View.GONE, row.findViewById(R.id.choice_hint).getVisibility());
    }
}
