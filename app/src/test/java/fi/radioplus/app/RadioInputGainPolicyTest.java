package fi.radioplus.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public final class RadioInputGainPolicyTest {
    @Test public void normalGainDoesNotAttenuate() {
        assertEquals(100, RadioInputGainPolicy.NORMAL_GAIN_PERCENT);
    }
    @Test public void repeatedHealthChecksDoNotRewriteNormalGain() {
        assertFalse(RadioInputGainPolicy.shouldWriteGain(100, 100));
    }
    @Test public void activationRestoresUnknownOrLegacyAttenuatedGain() {
        assertTrue(RadioInputGainPolicy.shouldWriteGain(100, -1));
        assertTrue(RadioInputGainPolicy.shouldWriteGain(100, 30));
        assertTrue(RadioInputGainPolicy.shouldWriteGain(100, 0));
    }
}
