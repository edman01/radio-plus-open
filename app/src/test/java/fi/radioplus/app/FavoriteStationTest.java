package fi.radioplus.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class FavoriteStationTest {

    @Test
    public void renamingPreservesSelectedLogo() {
        FavoriteStation station = new FavoriteStation(
                0,
                98100,
                "Vanha nimi",
                StationLogoResolver.BUILTIN_PREFIX + "radio_suomipop"
        );

        FavoriteStation renamed = station.withName("Uusi nimi");

        assertEquals("Uusi nimi", renamed.name);
        assertEquals(station.logo, renamed.logo);
        assertEquals(station.key(), renamed.key());
    }

    @Test
    public void changingLogoPreservesStationIdentityAndName() {
        FavoriteStation station = new FavoriteStation(0, 98100, "Suomipop");

        FavoriteStation changed = station.withLogo(
                StationLogoResolver.FILE_PREFIX + "station_0_98100.png"
        );

        assertEquals(station.name, changed.name);
        assertEquals(station.key(), changed.key());
        assertEquals("file:station_0_98100.png", changed.logo);
    }

    @Test
    public void detectsLegacyGeneratedFrequencyName() {
        assertTrue(new FavoriteStation(0, 98_200, "98.2 MHz")
                .hasGeneratedFrequencyName());
        assertTrue(new FavoriteStation(3, 999, "999 kHz")
                .hasGeneratedFrequencyName());
        assertFalse(new FavoriteStation(0, 98_200, "Oma asema")
                .hasGeneratedFrequencyName());
    }

    @Test
    public void limitsPersistedNameAndLogoTokens() {
        FavoriteStation station = new FavoriteStation(
                0,
                98_200,
                "A".repeat(100),
                "B".repeat(400)
        );

        assertEquals(64, station.name.codePointCount(0, station.name.length()));
        assertEquals(256, station.logo.codePointCount(0, station.logo.length()));
    }

}
