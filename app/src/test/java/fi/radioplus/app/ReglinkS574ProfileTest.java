package fi.radioplus.app;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ReglinkS574ProfileTest {
    @Test public void previewRequiresBuildOptInAndDoesNotEnableOlderProfile() {
        RadioBackendProfile profile = RadioBackendProfile.forApkSha256(RadioBackendProfile.REGLINK_S574_SERVICE_SHA256);
        assertEquals(RadioBackendProfile.REGLINK_S574_ASSISTED, profile);
        assertTrue(profile.isReglink()); assertTrue(profile.experimental);
        assertEquals(BuildConfig.REGLINK_STOCK_ASSISTED, profile.isEnabledForDeviceControl());
        assertFalse(RadioBackendProfile.REGLINK_S540.isEnabledForDeviceControl());
    }
    @Test public void sameTunerDoesNotAuthorizeMixingCoreAndUiVersions() {
        String tuner = RadioBackendProfile.REGLINK_S540_TUNER_SHA256;
        assertTrue(RadioBackendProfile.verifiedReglinkTriplet(RadioBackendProfile.REGLINK_S574_SERVICE_SHA256,
                RadioBackendProfile.REGLINK_S574_RADIO_SHA256, tuner));
        assertFalse(RadioBackendProfile.verifiedReglinkTriplet(RadioBackendProfile.REGLINK_S574_SERVICE_SHA256,
                RadioBackendProfile.REGLINK_S540_RADIO_SHA256, tuner));
        assertFalse(RadioBackendProfile.verifiedReglinkTriplet(RadioBackendProfile.REGLINK_S540_SERVICE_SHA256,
                RadioBackendProfile.REGLINK_S574_RADIO_SHA256, tuner));
        for (String bad : new String[]{null, "", "S5.74", "475X", "com.reglink.apps.mtkradio"}) {
            assertFalse(RadioBackendProfile.verifiedReglinkTriplet(RadioBackendProfile.REGLINK_S574_SERVICE_SHA256,
                    RadioBackendProfile.REGLINK_S574_RADIO_SHA256, bad));
        }
    }
}
