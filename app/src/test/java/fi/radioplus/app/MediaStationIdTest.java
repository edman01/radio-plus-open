package fi.radioplus.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class MediaStationIdTest {

    @Test
    public void roundTripPreservesBandAndFrequency() {
        FavoriteStation source = new FavoriteStation(0, 98_100, "SUOMIPOP");

        FavoriteStation decoded = MediaStationId.decode(MediaStationId.encode(source));

        assertEquals(source.band, decoded.band);
        assertEquals(source.frequency, decoded.frequency);
    }

    @Test
    public void rejectsMalformedOrOutOfBandIds() {
        assertNull(MediaStationId.decode(null));
        assertNull(MediaStationId.decode("favorites"));
        assertNull(MediaStationId.decode("station:FM:98100"));
        assertNull(MediaStationId.decode("station:0:200000"));
        assertNull(MediaStationId.decode("station:0:98100:extra"));
    }
}
