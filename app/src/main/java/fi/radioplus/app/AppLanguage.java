package fi.radioplus.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.LocaleList;

import java.util.Locale;

final class AppLanguage {
    static final String ENGLISH = "en";
    static final String FINNISH = "fi";

    private static final String PREFERENCES = "radio_plus_ui";
    private static final String KEY_LANGUAGE = "app_language";

    private AppLanguage() {
    }

    static String get(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(
                PREFERENCES,
                Context.MODE_PRIVATE
        );
        return normalize(preferences.getString(KEY_LANGUAGE, ENGLISH));
    }

    static boolean isFinnish(Context context) {
        return FINNISH.equals(get(context));
    }

    static boolean set(Context context, String language) {
        String normalized = normalize(language);
        if (normalized.equals(get(context))) {
            return false;
        }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LANGUAGE, normalized)
                .apply();
        return true;
    }

    static Context wrap(Context context) {
        Locale locale = Locale.forLanguageTag(get(context));
        Configuration configuration = new Configuration(
                context.getResources().getConfiguration()
        );
        configuration.setLocale(locale);
        configuration.setLocales(new LocaleList(locale));
        return context.createConfigurationContext(configuration);
    }

    static String text(Context context, String finnish, String english) {
        return isFinnish(context) ? finnish : english;
    }

    static String stationName(Context context, String name) {
        if ("Oma asema".equals(name) || "My station".equals(name)) {
            return text(context, "Oma asema", "My station");
        }
        return name;
    }

    private static String normalize(String language) {
        return FINNISH.equalsIgnoreCase(language) ? FINNISH : ENGLISH;
    }
}
