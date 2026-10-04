package fi.radioplus.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.LocaleList;

import java.util.Locale;

final class AppLanguage {
    static final String ENGLISH = "en";
    static final String FINNISH = "fi";
    private static final String[] CODES = {"en", "fi", "de", "fr", "es", "pt", "it", "sv", "pl", "nl", "tr", "cs"};
    private static final String[] NAMES = {"English", "Suomi", "Deutsch", "Français", "Español", "Português", "Italiano",
            "Svenska", "Polski", "Nederlands", "Türkçe", "Čeština"};

    static String[] codes() { return CODES.clone(); }
    static String[] names() { return NAMES.clone(); }
    static int selectedIndex(Context context) {
        String current = get(context);
        for (int i = 0; i < CODES.length; i++) if (CODES[i].equals(current)) return i;
        return 0;
    }
    static String displayName(Context context) { return NAMES[selectedIndex(context)]; }

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
        String language = get(context);
        if (FINNISH.equals(language)) return finnish;
        if (ENGLISH.equals(language)) return english;
        int id = TranslationCatalog.resourceFor(english);
        return id == 0 ? english : wrap(context).getString(id);
    }

    static String stationName(Context context, String name) {
        if ("Oma asema".equals(name) || "My station".equals(name)) {
            return text(context, "Oma asema", "My station");
        }
        return name;
    }

    private static String normalize(String language) {
        if (language == null) return ENGLISH;
        String base = Locale.forLanguageTag(language.replace('_', '-')).getLanguage();
        for (String code : CODES) if (code.equalsIgnoreCase(base)) return code;
        return ENGLISH;
    }
}
