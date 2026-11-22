package fi.radioplus.app;

import static fi.radioplus.app.RadioBackendProfile.*;
import static org.junit.Assert.*;
import org.junit.Test;

public final class ReglinkS602ProfileTest {
    @Test public void exactInspectedSetOnlyInOptInBuilds() {
        String core = "251e5f5936fd47e4d88dd64e38da9f98b8b09901e08ffd7361a391e367faa557";
        String ui = "900a3d20ef9b8bc2674b745b8cc76a6b5f8847762b79131c6011054746dc8a46";
        String tuner = "f9bba4d7785a5197dc8097a581ba09423d18af0db7801d3ffc5762e7cf020cb8";
        assertEquals(REGLINK_S602_ASSISTED, forApkSha256(core));
        assertEquals(BuildConfig.REGLINK_STOCK_ASSISTED, forApkSha256(core).isEnabledForDeviceControl());
        assertTrue(verifiedReglinkTriplet(core, ui, tuner));
        assertFalse(verifiedReglinkTriplet(core, REGLINK_S574_RADIO_SHA256, tuner));
        assertFalse(verifiedReglinkTriplet(REGLINK_S574_SERVICE_SHA256, ui, tuner));
        assertFalse(verifiedReglinkTriplet(core, REGLINK_S540_RADIO_SHA256, tuner));
        assertFalse(verifiedReglinkTriplet(core, ui, core));
        assertFalse(verifiedReglinkTriplet(core, ui, null));
        assertFalse(REGLINK_S540.isEnabledForDeviceControl());
        assertEquals(UNKNOWN, forApkSha256(ui));
    }
}
