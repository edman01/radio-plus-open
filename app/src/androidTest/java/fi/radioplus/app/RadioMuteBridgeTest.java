package fi.radioplus.app;

import org.junit.Test;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

/** Independent framework doubles, without adding classes in the OEM namespace. */
public final class RadioMuteBridgeTest {
    public static final class LegacyPlayer {
        int writes;
        boolean muted;
        public void setMute(boolean value) { writes++; muted = value; }
    }
    public static final class CurrentPlayer {
        int normalWrites;
        int hardwareWrites;
        boolean muted;
        public boolean getRadioMute() { return muted; }
        public void setMute(boolean value) { normalWrites++; muted = value; }
        public void setRadioMute(boolean value) { hardwareWrites++; muted = value; }
    }
    public static final class FailingPlayer {
        int hardwareWrites;
        public boolean getRadioMute() { throw new IllegalStateException("getter unavailable"); }
        public void setMute(boolean value) { throw new IllegalStateException("setter unavailable"); }
        public void setRadioMute(boolean value) { hardwareWrites++; }
    }
    private RadioPlaybackHealthReader reader(Object player) throws Exception {
        RadioPlaybackHealthReader reader = new RadioPlaybackHealthReader();
        Method configure = RadioPlaybackHealthReader.class.getDeclaredMethod("configurePlayer", Object.class);
        configure.setAccessible(true); configure.invoke(reader, player);
        return reader;
    }
    @Test public void legacySetterWorksWithoutAMuteGetter() throws Exception {
        LegacyPlayer player = new LegacyPlayer();
        RadioPlaybackHealthReader reader = reader(player);
        assertFalse(reader.read().muteKnown);
        assertEquals(0, player.writes);
        assertTrue(reader.setMuted(true)); assertTrue(player.muted);
        assertTrue(reader.setMuted(false)); assertFalse(player.muted);
        assertEquals(2, player.writes);
    }
    @Test public void currentContractRetainsBothIdempotentSettersAndReadOnlyPolling() throws Exception {
        CurrentPlayer player = new CurrentPlayer();
        RadioPlaybackHealthReader reader = reader(player);
        assertTrue(reader.read().muteKnown);
        assertEquals(0, player.normalWrites); assertEquals(0, player.hardwareWrites);
        assertTrue(reader.setMuted(true)); assertTrue(reader.read().muted);
        assertTrue(reader.setMuted(false)); assertFalse(reader.read().muted);
        assertEquals(2, player.normalWrites); assertEquals(2, player.hardwareWrites);
    }
    @Test public void brokenGetterAndSetterDoNotBlockIndependentHardwareSetter() throws Exception {
        FailingPlayer player = new FailingPlayer();
        RadioPlaybackHealthReader reader = reader(player);
        assertFalse(reader.read().muteKnown);
        assertTrue(reader.setMuted(true));
        assertEquals(1, player.hardwareWrites);
    }
}
