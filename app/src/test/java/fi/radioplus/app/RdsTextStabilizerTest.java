package fi.radioplus.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class RdsTextStabilizerTest {
    private static final long SUOMIPOP = (1L << 32) | 98_100L;
    private static final long NOVA = (1L << 32) | 106_200L;

    @Test
    public void requiresTwoMatchingReadsBeforePublishingInitialTitle() {
        RdsTextStabilizer stabilizer = new RdsTextStabilizer();

        assertEquals("", stabilizer.observe(SUOMIPOP, "BEHM - Frida"));
        assertEquals("BEHM - Frida", stabilizer.observe(SUOMIPOP, "BEHM - Frida"));
    }

    @Test
    public void incompleteRepeatsNeverReplaceCompleteTitle() {
        RdsTextStabilizer stabilizer = new RdsTextStabilizer();
        stabilizer.observe(SUOMIPOP, "BEHM - Frida");
        stabilizer.observe(SUOMIPOP, "BEHM - Frida");

        assertEquals("BEHM - Frida", stabilizer.observe(SUOMIPOP, "BEH - Frida"));
        assertEquals("BEHM - Frida", stabilizer.observe(SUOMIPOP, "BEH - Frida"));
        assertEquals("BEHM - Frida", stabilizer.observe(SUOMIPOP, "BEH - Frida"));
    }

    @Test
    public void newTrackRequiresThreeMatchingReads() {
        RdsTextStabilizer stabilizer = new RdsTextStabilizer();
        stabilizer.observe(SUOMIPOP, "BEHM - Frida");
        stabilizer.observe(SUOMIPOP, "BEHM - Frida");

        assertEquals("BEHM - Frida", stabilizer.observe(SUOMIPOP, "KUUMAA - Ylivoimainen"));
        assertEquals("BEHM - Frida", stabilizer.observe(SUOMIPOP, "KUUMAA - Ylivoimainen"));
        assertEquals(
                "KUUMAA - Ylivoimainen",
                stabilizer.observe(SUOMIPOP, "KUUMAA - Ylivoimainen")
        );
    }

    @Test
    public void changingStationClearsPreviousTitle() {
        RdsTextStabilizer stabilizer = new RdsTextStabilizer();
        stabilizer.observe(SUOMIPOP, "BEHM - Frida");
        stabilizer.observe(SUOMIPOP, "BEHM - Frida");

        assertEquals("", stabilizer.observe(NOVA, "JVG - Tarkenee"));
        assertEquals("JVG - Tarkenee", stabilizer.observe(NOVA, "JVG - Tarkenee"));
    }
}
