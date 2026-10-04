package fi.radioplus.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Locale;
import org.junit.Test;
import static org.junit.Assert.*;

public final class AppLanguageDefaultsTest {
    @Test
    public void freshInstallDefaultsToEnglishEvenOnFinnishDevice() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Configuration config = new Configuration(target.getResources().getConfiguration());
        config.setLocale(Locale.forLanguageTag("fi"));
        final String testPreferences = "qa_language_" + System.nanoTime();
        Context isolated = new ContextWrapper(target.createConfigurationContext(config)) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(testPreferences, mode);
            }
        };
        try {
            assertEquals("en", AppLanguage.get(isolated));
            assertEquals("en", AppLanguage.wrap(isolated).getResources()
                    .getConfiguration().getLocales().get(0).getLanguage());
            assertTrue(AppLanguage.set(isolated, "fi"));
            assertEquals("fi", AppLanguage.get(isolated));
            assertFalse(AppLanguage.set(isolated, "fi"));
            assertTrue(AppLanguage.set(isolated, "en"));
            assertEquals("en", AppLanguage.get(isolated));
        } finally {
            target.deleteSharedPreferences(testPreferences);
        }
    }
}
