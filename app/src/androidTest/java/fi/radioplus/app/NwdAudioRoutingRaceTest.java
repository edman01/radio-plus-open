package fi.radioplus.app;

import android.content.Intent;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic request/ack races; no OEM APK or hardware is executed by these tests. */
public final class NwdAudioRoutingRaceTest {
    private static final class Audio implements NwdAudioRouting.Transport {
        int source = 4;
        long now = 100;
        final List<Intent> sent = new ArrayList<>();
        final NwdAudioRouting routing = new NwdAudioRouting(this, () -> now);
        @Override public int source() { return source; }
        @Override public void send(Intent intent) { sent.add(intent); }
    }

    private static void assertSource(Intent intent, int value) {
        assertEquals(NwdAudioRouting.CHANGE_SOURCE, intent.getAction());
        assertEquals(NwdAudioRouting.KERNEL_PACKAGE, intent.getPackage());
        assertTrue(intent.getExtras().get("extra_source_id") instanceof Byte);
        assertEquals(value, intent.getByteExtra("extra_source_id", (byte) -1));
    }

    private static void assertRestore(Audio audio, int offset) {
        assertSource(audio.sent.get(offset), 4);
        Intent init = audio.sent.get(offset + 1);
        assertEquals(NwdAudioRouting.APP_IN, init.getAction());
        assertEquals(NwdAudioRouting.RADIO_PACKAGE, init.getPackage());
        assertEquals(8, init.getIntExtra("extra_app_id", -1));
        assertEquals(1, init.getIntExtra("extra_app_operation", -1));
        assertEquals(0, init.getIntExtra("extra_app_event", -1));
    }

    @Test public void immediatePlaySupersedesPauseDespiteStaleRadioSource() {
        Audio audio = new Audio();
        assertTrue(audio.routing.pause());
        assertEquals(2, audio.sent.size());
        assertSource(audio.sent.get(0), 0);
        assertEquals(NwdAudioRouting.EXIT_RADIO, audio.sent.get(1).getAction());
        assertEquals(NwdAudioRouting.RADIO_PACKAGE, audio.sent.get(1).getPackage());
        assertTrue(audio.routing.play());
        assertEquals(4, audio.sent.size());
        assertRestore(audio, 2);
        assertTrue(audio.routing.play());
        assertEquals("A duplicate Play must not initialize the decoder again", 4, audio.sent.size());
    }

    @Test public void staleRadioHealthPollDoesNotErasePendingPause() {
        Audio audio = new Audio();
        audio.routing.pause();
        assertTrue(audio.routing.readHealth().radioOwnsSource());
        assertEquals("Health polling must not send a recovery command", 2, audio.sent.size());
        audio.routing.play();
        assertEquals(4, audio.sent.size());
        assertRestore(audio, 2);
    }

    @Test public void acknowledgedPauseThenPlayRequestsRadioNormally() {
        Audio audio = new Audio();
        audio.routing.pause();
        audio.source = 0;
        assertFalse(audio.routing.readHealth().radioOwnsSource());
        assertEquals(2, audio.sent.size());
        audio.routing.play();
        assertEquals(4, audio.sent.size());
        assertRestore(audio, 2);
        audio.routing.play();
        assertEquals(4, audio.sent.size());
        audio.source = 4;
        audio.routing.readHealth();
        audio.routing.play();
        assertEquals("A settled radio source needs no reinitialization", 4, audio.sent.size());
    }

    @Test public void handoffIsNeverReclaimedByPollingButExplicitPlayCanResume() {
        Audio audio = new Audio();
        audio.routing.pause();
        audio.source = 7;
        audio.routing.readHealth();
        assertFalse(audio.routing.pause());
        assertEquals(2, audio.sent.size());
        audio.routing.play();
        assertEquals(4, audio.sent.size());
        assertRestore(audio, 2);
        audio.source = 4;
        audio.routing.readHealth();
        audio.source = 0;
        audio.routing.readHealth();
        assertEquals(4, audio.sent.size());
        audio.routing.play();
        assertEquals("Explicit Play after an acknowledged handoff must not be lost", 6, audio.sent.size());
        assertRestore(audio, 4);
    }

    @Test public void delayedPauseStillNeedsOneExplicitRestoreAndPlayRetriesStayBounded() {
        Audio audio = new Audio();
        audio.routing.pause();
        audio.now += 10 * NwdAudioRouting.PENDING_ROUTE_MS;
        audio.routing.play();
        assertEquals("Elapsed time is not acknowledgement of another process's queued EXIT", 4, audio.sent.size());
        assertRestore(audio, 2);
        audio.routing.play();
        assertEquals("Repeated explicit Play on settled source4 must remain idempotent", 4, audio.sent.size());
        audio.source = 0;
        audio.routing.play();
        assertEquals(6, audio.sent.size());
        assertRestore(audio, 4);
        audio.routing.play();
        assertEquals(6, audio.sent.size());
        audio.now += NwdAudioRouting.PENDING_ROUTE_MS;
        audio.routing.play();
        assertEquals("An unacknowledged play can retry only on another explicit request", 8, audio.sent.size());
    }

    @Test public void unknownSourcePollingKeepsRecentPauseWithoutReclaimingAnything() {
        Audio audio = new Audio();
        audio.routing.pause();
        audio.source = -1;
        assertFalse(audio.routing.readHealth().sourceKnown);
        assertEquals(2, audio.sent.size());
        audio.source = 4;
        audio.routing.play();
        assertEquals(4, audio.sent.size());
        assertRestore(audio, 2);
    }

    @Test public void sourceAcknowledgementAloneCannotConfirmDecoderExitFinished() {
        Audio audio = new Audio();
        audio.routing.pause();
        audio.source = 0;
        audio.routing.readHealth();
        audio.source = 4;
        audio.routing.readHealth();
        assertEquals("Source polling must never recover by itself", 2, audio.sent.size());
        audio.routing.play();
        assertEquals(4, audio.sent.size());
        assertRestore(audio, 2);
    }
}
