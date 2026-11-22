package fi.radioplus.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;

/** Generated test artwork only; no third-party logos are distributed. */
public final class LogoImportCompatibilityTest {
    @Test
    public void testPlayCannotResolveBundledResourcesOrRestoredTokens() {
        if (BuildConfig.BUNDLED_STATION_LOGOS) return;
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (String name : new String[]{"logo_nrj_finland", "logo_radio_suomipop",
                "logo_yle_radio_1", "logo_suomirock"}) {
            assertEquals(0, context.getResources().getIdentifier(
                    name, "drawable", context.getPackageName()));
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            FavoriteAdapter adapter = new FavoriteAdapter(context, null);
            for (String token : new String[]{"", "builtin:nrj", "file:missing.png", "none"}) {
                adapter.submit(Collections.singletonList(new FavoriteStation(
                        0, 96600, "NRJ", token)), "", Collections.emptySet());
                View card = adapter.getView(0, null, new FrameLayout(context));
                assertEquals(View.GONE, card.findViewById(R.id.favorite_logo).getVisibility());
                assertEquals("NRJ", ((android.widget.TextView)
                        card.findViewById(R.id.favorite_name)).getText().toString());
            }
        });
    }

    @Test
    public void testCommonLogoSizesPreservePixelsAndAspectRatio() throws Throwable {
        checkSize(160, 120, 160, 120);
        checkSize(168, 126, 168, 126);
        checkSize(400, 240, 400, 240);
        checkSize(500, 500, 500, 500);
        checkSize(1182, 1182, 512, 512);
    }

    @Test public void advertisedFormatsImportAndReplacingTheSameLogoRefreshesItsPixels() throws Throwable {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        FavoriteStation station = new FavoriteStation(90, 1234567, "Format test");
        FavoriteAdapter adapter = new FavoriteAdapter(context, null);
        Bitmap image = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888);
        Bitmap.CompressFormat[] formats = {Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.JPEG,
                Bitmap.CompressFormat.WEBP};
        int[] colors = {Color.RED, Color.BLUE, Color.GREEN};
        String lastToken = null;
        try {
            for (int i = 0; i < formats.length; i++) {
                image.eraseColor(colors[i]);
                File source = File.createTempFile("radio-plus-format-", ".img", context.getCacheDir());
                try {
                    try (FileOutputStream out = new FileOutputStream(source)) {
                        assertTrue(image.compress(formats[i], 95, out));
                    }
                    String token = FavoriteLogoStore.importLogo(context, station, Uri.fromFile(source));
                    if (lastToken != null) assertEquals(lastToken, token);
                    lastToken = token;
                    adapter.invalidateCustomLogo(token);
                    Bitmap decoded = adapter.customLogoBitmap(token);
                    assertNotNull(decoded);
                    assertEquals(512, decoded.getWidth());
                    assertEquals(288, decoded.getHeight());
                    int pixel = decoded.getPixel(128, 72);
                    assertTrue(Math.abs(Color.red(pixel) - Color.red(colors[i])) < 8);
                    assertTrue(Math.abs(Color.green(pixel) - Color.green(colors[i])) < 8);
                    assertTrue(Math.abs(Color.blue(pixel) - Color.blue(colors[i])) < 8);
                    assertTrue("The imported source remains on the device", source.isFile());
                } finally {
                    source.delete();
                }
            }
        } finally {
            FavoriteLogoStore.delete(context, lastToken);
            image.recycle();
        }
    }

    private void checkSize(int width, int height, int expectedWidth, int expectedHeight)
            throws Throwable {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File source = File.createTempFile("logo-compat-", ".png", context.getCacheDir());
        FavoriteStation station = new FavoriteStation(0, 107900, "Logo compatibility test");
        Bitmap original = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        original.eraseColor(Color.TRANSPARENT);
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                original.setPixel(x, y, ((x + y) & 1) == 0 ? Color.RED : Color.BLUE);
            }
        }
        String token = null;
        Bitmap imported = null;
        try {
            try (FileOutputStream stream = new FileOutputStream(source)) {
                assertTrue(original.compress(Bitmap.CompressFormat.PNG, 100, stream));
            }
            token = FavoriteLogoStore.importLogo(context, station, Uri.fromFile(source));
            imported = BitmapFactory.decodeFile(
                    FavoriteLogoStore.fileForToken(context, token).getAbsolutePath());
            assertNotNull(imported);
            assertEquals(expectedWidth, imported.getWidth());
            assertEquals(expectedHeight, imported.getHeight());
            if (width == expectedWidth && height == expectedHeight) {
                int[] before = new int[width * height];
                int[] after = new int[width * height];
                original.getPixels(before, 0, width, 0, 0, width, height);
                imported.getPixels(after, 0, width, 0, 0, width, height);
                assertTrue("Import must preserve every small-logo pixel",
                        java.util.Arrays.equals(before, after));
            }
            final String storedToken = token;
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                FavoriteStation custom = new FavoriteStation(0, 107900,
                        "Logo compatibility test", storedToken);
                FavoriteAdapter adapter = new FavoriteAdapter(context, null);
                adapter.submit(Collections.singletonList(custom), "", Collections.emptySet());
                View card = adapter.getView(0, null, new FrameLayout(context));
                ImageView logo = card.findViewById(R.id.favorite_logo);
                assertEquals(ImageView.ScaleType.CENTER_INSIDE, logo.getScaleType());
                assertEquals(expectedWidth, logo.getDrawable().getIntrinsicWidth());
                assertEquals(expectedHeight, logo.getDrawable().getIntrinsicHeight());
                // Recycled built-in artwork must not inherit the custom-logo policy.
                adapter.submit(Collections.singletonList(new FavoriteStation(0, 98100,
                        "SUOMIPOP")), "", Collections.emptySet());
                adapter.getView(0, card, new FrameLayout(context));
                assertEquals(ImageView.ScaleType.FIT_CENTER, logo.getScaleType());
                if (!BuildConfig.BUNDLED_STATION_LOGOS) {
                    assertEquals(View.GONE, logo.getVisibility());
                    assertNull(logo.getDrawable());
                }
                adapter.submit(Collections.singletonList(new FavoriteStation(
                        0, 98100, "SUOMIPOP", StationLogoResolver.NO_LOGO)),
                        "", Collections.emptySet());
                adapter.getView(0, card, new FrameLayout(context));
                assertEquals(View.GONE, logo.getVisibility());
                assertNull(logo.getDrawable());
            });
            FavoriteLogoStore.delete(context, token);
            assertFalse(FavoriteLogoStore.fileForToken(context, token).exists());
        } finally {
            if (token != null) FavoriteLogoStore.delete(context, token);
            if (imported != null) imported.recycle();
            original.recycle();
            source.delete();
        }
    }
}
