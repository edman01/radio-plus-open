package fi.radioplus.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RdsEventCacheTest {
    @Test
    public void capturesPsAndRadioTextStringEvents() {
        RdsEventCache cache = new RdsEventCache();

        cache.record(RdsEventCache.EVENT_RDS_STATE, "4", 97_100);
        cache.record(RdsEventCache.EVENT_PS_MESSAGE, "  NOVA\u0000 ", 97_100);
        cache.record(RdsEventCache.EVENT_RT_MESSAGE, "Artist  -  Song", 97_100);

        assertEquals("NOVA", cache.stationName(97_100));
        assertEquals("Artist - Song", cache.radioText(97_100));
        assertTrue(cache.rdsAvailable(97_100));
    }

    @Test
    public void neverLeaksMetadataToAnotherFrequency() {
        RdsEventCache cache = new RdsEventCache();
        cache.record(RdsEventCache.EVENT_PS_MESSAGE, "YleX", 95_500);

        cache.onFrequencyObserved(97_100);

        assertEquals("", cache.stationName(95_500));
        assertEquals("", cache.stationName(97_100));
        assertFalse(cache.rdsAvailable(97_100));
    }

    @Test
    public void rdsOffEventClearsCurrentMetadata() {
        RdsEventCache cache = new RdsEventCache();
        cache.record(RdsEventCache.EVENT_PS_MESSAGE, "Suomipop", 98_100);

        cache.record(RdsEventCache.EVENT_RDS_STATE, "0", 98_100);

        assertEquals("", cache.stationName(98_100));
        assertEquals("", cache.radioText(98_100));
        assertFalse(cache.rdsAvailable(98_100));
    }

    @Test
    public void incompleteRadioTextDoesNotReplaceCompleteMessage() {
        RdsEventCache cache = new RdsEventCache();
        cache.record(
                RdsEventCache.EVENT_RT_MESSAGE,
                "Radio Nova paras sekoitus klassikoita",
                106_200
        );

        cache.record(
                RdsEventCache.EVENT_RT_MESSAGE,
                "Radio Nova paras sek assikoita",
                106_200
        );

        assertEquals(
                "Radio Nova paras sekoitus klassikoita",
                cache.radioText(106_200)
        );
    }

    @Test
    public void completeRadioTextReplacesEarlierIncompleteMessage() {
        RdsEventCache cache = new RdsEventCache();
        cache.record(
                RdsEventCache.EVENT_RT_MESSAGE,
                "Radio Nova paras sek assikoita",
                106_200
        );

        cache.record(
                RdsEventCache.EVENT_RT_MESSAGE,
                "Radio Nova paras sekoitus klassikoita",
                106_200
        );

        assertEquals(
                "Radio Nova paras sekoitus klassikoita",
                cache.radioText(106_200)
        );
    }

    @Test
    public void capturesPsAndRadioTextFromRadioInfoSnapshot() {
        RdsEventCache cache = new RdsEventCache();

        cache.recordSnapshot(
                98_100,
                "  SUOMIPOP\u0000 ",
                "BEHM  -  Frida",
                "4"
        );

        assertEquals("SUOMIPOP", cache.stationName(98_100));
        assertEquals("BEHM - Frida", cache.radioText(98_100));
        assertTrue(cache.rdsAvailable(98_100));
    }

    @Test
    public void emptySnapshotFieldsDoNotEraseEarlierEventMetadata() {
        RdsEventCache cache = new RdsEventCache();
        cache.record(RdsEventCache.EVENT_PS_MESSAGE, "YleX", 95_500);
        cache.record(RdsEventCache.EVENT_RT_MESSAGE, "Artist - Song", 95_500);

        cache.recordSnapshot(95_500, "", "", "4");

        assertEquals("YleX", cache.stationName(95_500));
        assertEquals("Artist - Song", cache.radioText(95_500));
        assertTrue(cache.rdsAvailable(95_500));
    }

    @Test
    public void validSnapshotMetadataWinsOverStaleRdsOffState() {
        RdsEventCache cache = new RdsEventCache();

        cache.recordSnapshot(104_400, "NOVA", "", "0");

        assertEquals("NOVA", cache.stationName(104_400));
        assertTrue(cache.rdsAvailable(104_400));
    }
}
