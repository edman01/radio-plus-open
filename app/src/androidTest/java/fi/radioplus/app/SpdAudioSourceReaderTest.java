package fi.radioplus.app;

import android.os.RemoteException;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic source reads only; no vendor classes or audio hardware are invoked. */
public final class SpdAudioSourceReaderTest {
    @Test public void exactPackageAndOptionalDeviceSuffixRemainRecognizable() {
        assertEquals("com.spd.radio", reader("com.spd.radio").currentSource());
        assertEquals("com.spd.radio", reader("com.spd.radio+9").currentSource());
        assertEquals("com.spd.radio", reader("  com.spd.radio  ").currentSource());
        assertEquals("com.spd.media", reader("com.spd.media+1").currentSource());
    }

    @Test public void similarPackagesAndActivityIdentitiesAreNotRadioOwner() {
        for (String value : new String[]{"com.spd.radio.fake", "com.spd.spdradio",
                "com.spd.radio/RadioService", "com.spd.radio|com.spd.radio.START",
                "COM.SPD.RADIO", "prefix.com.spd.radio", "com.spd.radio+9+11"}) {
            assertNotEquals(value, "com.spd.radio", reader(value).currentSource());
        }
    }

    @Test public void missingAndMalformedValuesAreUnknown() {
        for (String value : new String[]{null, "", " \t\n", "+9", "com.spd.radio+",
                "com.spd. radio", "com.spd.radio\u0000", "com.spd.radio +9"}) {
            assertNull(value, reader(value).currentSource());
        }
        assertNull(reader(new String(new char[513]).replace('\0', 'a')).currentSource());
    }

    @Test public void readFailureDoesNotReusePreviousOwnerAndLaterReadsCanRecover() {
        int[] calls = {0};
        SpdAudioSourceReader reader = new SpdAudioSourceReader(() -> {
            switch (++calls[0]) {
                case 1: return "com.spd.radio";
                case 2: throw new RemoteException("Service died");
                case 3: return null;
                default: return "com.spd.media";
            }
        });
        assertEquals("com.spd.radio", reader.currentSource());
        assertNull(reader.currentSource());
        assertNull(reader.currentSource());
        assertEquals("com.spd.media", reader.currentSource());
        assertEquals(4, calls[0]);
    }

    @Test public void reflectionSecurityAndLinkageFailuresFailClosed() {
        assertNull(new SpdAudioSourceReader(() -> {
            throw new ReflectiveOperationException("No getter");
        }).currentSource());
        assertNull(new SpdAudioSourceReader(() -> {
            throw new SecurityException("Getter denied");
        }).currentSource());
        assertNull(new SpdAudioSourceReader(() -> {
            throw new NoClassDefFoundError("Framework unavailable");
        }).currentSource());
    }

    private static SpdAudioSourceReader reader(String value) {
        return new SpdAudioSourceReader(() -> value);
    }
}
