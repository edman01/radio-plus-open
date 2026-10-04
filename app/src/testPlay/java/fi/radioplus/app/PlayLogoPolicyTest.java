package fi.radioplus.app;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PlayLogoPolicyTest {
    @Test
    public void playHasSeparateIdentityAndNoBundledCatalogue() {
        assertFalse(BuildConfig.BUNDLED_STATION_LOGOS);
        assertTrue(BuildConfig.APPLICATION_ID.contains(".play"));
        assertEquals(0, StationLogoResolver.BUILTIN_NAMES.length);
        assertEquals(0, StationLogoResolver.BUILTIN_TOKENS.length);
    }

    @Test
    public void namesAndRestoredTokensNeverEnableBuiltInArtwork() {
        for (String name : new String[]{null, "", "NRJ", "SUOMIPOP", "Yle Radio Suomi"}) {
            assertEquals(0, StationLogoResolver.resolve(name));
            for (String token : new String[]{null, "", "none", "builtin:nrj",
                    "builtin:radio_suomipop", "file:missing.png"}) {
                assertEquals(0, StationLogoResolver.resolveExplicit(token));
                assertEquals(0, StationLogoResolver.resolveForStation(
                        token, false, false, name));
                assertEquals(0, StationLogoResolver.resolveForStation(
                        token, true, true, name));
            }
        }
    }
}
