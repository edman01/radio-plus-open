package fi.radioplus.app;

import org.junit.Test;
import java.util.Locale;
import static org.junit.Assert.*;

public final class RadioBackendProfileTest {
    @Test public void inspectedV7SelectsCurrentContract() {
        assertEquals(RadioBackendProfile.HCN_CURRENT_31,
                RadioBackendProfile.forApkSha256(RadioBackendProfile.V7_APK_SHA256));
        assertFalse(RadioBackendProfile.HCN_CURRENT_31.experimental);
    }
    @Test public void inspectedMt8163SelectsLegacyNotV7() {
        assertEquals(RadioBackendProfile.HCN_LEGACY_25,
                RadioBackendProfile.forApkSha256(RadioBackendProfile.MT8163_APK_SHA256));
        assertTrue(RadioBackendProfile.HCN_LEGACY_25.experimental);
    }
    @Test public void hashComparisonAllowsUppercaseHex() {
        assertEquals(RadioBackendProfile.HCN_CURRENT_31,
                RadioBackendProfile.forApkSha256(RadioBackendProfile.V7_APK_SHA256.toUpperCase(Locale.ROOT)));
    }
    @Test public void unknownApksNeverDefaultToV7() {
        for (String unknown : new String[]{null, "", "com.hcn.autoradio", "Junsun V1",
                "Junsun V7", "MT8768", "V.1.0.2512261203", "0".repeat(64),
                RadioBackendProfile.V7_APK_SHA256 + "0",
                " " + RadioBackendProfile.MT8163_APK_SHA256}) {
            assertEquals(RadioBackendProfile.UNKNOWN, RadioBackendProfile.forApkSha256(unknown));
        }
    }
}
