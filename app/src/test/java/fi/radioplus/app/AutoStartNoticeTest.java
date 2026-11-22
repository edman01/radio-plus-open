package fi.radioplus.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AutoStartNoticeTest {
    @Test public void explicitManufacturerBrandOrRetailModelGetsTheJunsunHint() {
        int hint = R.string.settings_auto_start_enabled_junsun;
        assertEquals(hint, AutoStartNotice.messageId(true, " Junsun ", "alps", "tb8768p1_64_bsp"));
        assertEquals(hint, AutoStartNotice.messageId(true, "MediaTek", "JUNSUN", "V7"));
        assertEquals(hint, AutoStartNotice.messageId(true, "alps", "alps", "Junsun V1 Pro C"));
        assertEquals(hint, AutoStartNotice.messageId(true, null, null, "junsun-v9_plus"));
    }

    @Test public void noRetailIdentityOrJustASharedPlatformGetsTheGenericNotice() {
        int generic = R.string.settings_auto_start_enabled;
        assertEquals(generic, AutoStartNotice.messageId(true, null, null, null));
        assertEquals(generic, AutoStartNotice.messageId(true, "alps", "alps", "tb8768p1_64_bsp"));
        assertEquals(generic, AutoStartNotice.messageId(true, "Reglink", "Reglink", "V7"));
        assertEquals(generic, AutoStartNotice.messageId(true, "Hizpo", "Hizpo", "MQ001"));
        assertEquals(generic, AutoStartNotice.messageId(true, "NonJunsun", "Junsunish", "Compatible with Junsun V7"));
        assertEquals(generic, AutoStartNotice.messageId(true, null, "", "Junsun-compatible"));
    }

    @Test public void disablingIsAlwaysBrandNeutral() {
        assertEquals(R.string.settings_auto_start_disabled, AutoStartNotice.messageId(false, "Junsun", "Junsun", "Junsun V7"));
        assertEquals(R.string.settings_auto_start_disabled, AutoStartNotice.messageId(false, null, null, null));
    }
}
