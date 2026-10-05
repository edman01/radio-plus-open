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
    @Test public void inspectedAc8259SelectsTsNotHcn() {
        assertEquals(RadioBackendProfile.TS_AC8259_V115,
                RadioBackendProfile.forApkSha256(RadioBackendProfile.AC8259_APK_SHA256));
        assertEquals("com.ts.MainUI", RadioBackendProfile.TS_AC8259_V115.stockPackage());
        assertTrue(RadioBackendProfile.TS_AC8259_V115.experimental);
    }
    @Test public void inspected825xAnd8667SelectOnlyTheirExactTsProfiles() {
        assertEquals(RadioBackendProfile.TS_825X_V27,
                RadioBackendProfile.forApkSha256(RadioBackendProfile.TS_825X_APK_SHA256));
        assertEquals(RadioBackendProfile.TS_8667Q_V23,
                RadioBackendProfile.forApkSha256(RadioBackendProfile.TS_8667Q_APK_SHA256));
        for (RadioBackendProfile profile : new RadioBackendProfile[]{RadioBackendProfile.TS_AC8259_V115,
                RadioBackendProfile.TS_825X_V27, RadioBackendProfile.TS_8667Q_V23}) {
            assertTrue(profile.isTs()); assertTrue(profile.experimental);
            assertEquals("com.ts.MainUI", profile.stockPackage());
        }
        assertFalse(RadioBackendProfile.HCN_CURRENT_31.isTs());
        assertFalse(RadioBackendProfile.UNKNOWN.isTs());
        assertEquals(RadioBackendProfile.UNKNOWN, RadioBackendProfile.forApkSha256("com.ts.MainUI"));
        assertEquals(RadioBackendProfile.UNKNOWN, RadioBackendProfile.forApkSha256("Xtrons IAP12CTS"));
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
