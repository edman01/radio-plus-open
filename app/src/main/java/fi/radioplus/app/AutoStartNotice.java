package fi.radioplus.app;

import java.util.regex.Pattern;

/** Wording only. The tuner profile, chipset and firmware may be shared by different brands. */
final class AutoStartNotice {
    private static final Pattern JUNSUN_MODEL = Pattern.compile(
            "Junsun[ _-]+V[0-9]+(?:[ _-]*(?:Pro|Plus|Max|C))*", Pattern.CASE_INSENSITIVE);

    private AutoStartNotice() {}

    static int messageId(boolean enabled, String manufacturer, String brand, String model) {
        if (!enabled) return R.string.settings_auto_start_disabled;
        if (isJunsun(manufacturer) || isJunsun(brand)
                || (model != null && JUNSUN_MODEL.matcher(model.trim()).matches())) {
            return R.string.settings_auto_start_enabled_junsun;
        }
        return R.string.settings_auto_start_enabled;
    }

    private static boolean isJunsun(String value) {
        return value != null && "Junsun".equalsIgnoreCase(value.trim());
    }
}
