package fi.radioplus.app;

import android.content.Intent;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic MCU source routing only; no OEM code or physical radio is executed. */
public final class NwdMcuAudioRoutingTest {
    private static final class Audio implements NwdAudioRouting.Transport {
        int source;
        long now = 100;
        final List<Intent> sent = new ArrayList<>();
        final NwdAudioRouting routing = new NwdAudioRouting(this, () -> now, false);
        @Override public int source() { return source; }
        @Override public void send(Intent intent) { sent.add(intent); }
    }

    private static void assertSources(Audio audio, int... sources) {
        assertEquals(sources.length, audio.sent.size());
        for (int i = 0; i < sources.length; i++) {
            Intent intent = audio.sent.get(i);
            assertEquals("MCU mode must not send APP_IN or decoder EXIT",
                    NwdAudioRouting.CHANGE_SOURCE, intent.getAction());
            assertEquals(NwdAudioRouting.KERNEL_PACKAGE, intent.getPackage());
            assertEquals("Source requests carry no decoder or global mute extras",
                    1, intent.getExtras().size());
            assertTrue(intent.getExtras().get("extra_source_id") instanceof Byte);
            assertEquals(sources[i], intent.getByteExtra("extra_source_id", (byte) -1));
        }
    }

    @Test public void modeIsExplicitAndLegacyConstructorsKeepDecoderLifecycle() {
        Audio audio = new Audio();
        assertFalse(audio.routing.usesDecoderLifecycle());
        assertTrue(new NwdAudioRouting(audio).usesDecoderLifecycle());
        assertTrue(new NwdAudioRouting(audio, () -> audio.now).usesDecoderLifecycle());
        assertTrue(new NwdAudioRouting(audio, () -> audio.now, true).usesDecoderLifecycle());
        assertSources(audio);
    }

    @Test public void settledPlayAndPauseUseOnlyKernelSourceChanges() {
        Audio audio = new Audio();
        assertTrue(audio.routing.play());
        assertSources(audio, 4);
        assertTrue(audio.routing.play());
        assertSources(audio, 4);
        audio.source = 4;
        assertTrue(audio.routing.readHealth().radioOwnsSource());
        assertTrue(audio.routing.play());
        assertSources(audio, 4);
        assertTrue(audio.routing.pause());
        assertSources(audio, 4, 0);
        audio.source = 0;
        assertFalse(audio.routing.readHealth().radioOwnsSource());
        assertFalse(audio.routing.pause());
        assertSources(audio, 4, 0);
    }

    @Test public void immediatePlaySupersedesPauseDespiteStaleSourceAndPoll() {
        Audio audio = new Audio();
        audio.source = 4;
        assertTrue(audio.routing.pause());
        assertTrue(audio.routing.readHealth().radioOwnsSource());
        assertSources(audio, 0);
        assertTrue(audio.routing.play());
        assertSources(audio, 0, 4);
        assertTrue(audio.routing.play());
        assertSources(audio, 0, 4);
    }

    @Test public void pauseCancelsUnacknowledgedPlayThenLatestPlayRestoresOnce() {
        Audio audio = new Audio();
        assertTrue(audio.routing.play());
        assertTrue(audio.routing.pause());
        assertSources(audio, 4, 0);
        assertTrue(audio.routing.play());
        assertSources(audio, 4, 0, 4);
        assertTrue(audio.routing.play());
        assertSources(audio, 4, 0, 4);
    }

    @Test public void finalPauseCancelsCompensatingPlayBehindEarlierPause() {
        Audio audio = new Audio();
        audio.source = 4;
        assertTrue(audio.routing.pause());
        assertTrue(audio.routing.play());
        assertSources(audio, 0, 4);
        audio.routing.readHealth();
        assertTrue(audio.routing.play());
        assertTrue(audio.routing.hasPendingStart());
        // The first Pause reaches the kernel before our compensating Play.
        audio.source = 0;
        audio.now += 30000L;
        assertTrue(audio.routing.pause());
        assertSources(audio, 0, 4, 0);
        assertFalse(audio.routing.hasPendingStart());
    }

    @Test public void compensatingPlaySettlesOnlyAfterEarlierPauseWasObserved() {
        Audio audio = new Audio();
        audio.source = 4;
        audio.routing.pause();
        audio.routing.play();
        audio.routing.readHealth();
        assertTrue(audio.routing.hasPendingStart());
        audio.source = 0;
        audio.routing.readHealth();
        assertTrue(audio.routing.hasPendingStart());
        audio.source = 4;
        audio.routing.readHealth();
        assertFalse(audio.routing.hasPendingStart());
        assertFalse(audio.routing.cancelPendingStart());
        assertSources(audio, 0, 4);
    }

    @Test public void compensatingPlayCanBeCanceledAfterUnknownAndLateRadioReadback() {
        Audio audio = new Audio();
        audio.source = 4;
        assertTrue(audio.routing.pause());
        assertTrue(audio.routing.play());
        assertTrue(audio.routing.markPendingCancellation());
        audio.source = -1;
        assertFalse(audio.routing.cancelPendingStart());
        assertTrue(audio.routing.hasPendingStart());
        audio.source = 4;
        audio.routing.readHealth();
        assertTrue(audio.routing.hasPendingStart());
        assertTrue(audio.routing.cancelPendingStart());
        assertSources(audio, 0, 4, 0);
        assertFalse(audio.routing.hasPendingStart());
    }

    @Test public void pendingRequestsRetryOnlyOnLaterExplicitPlay() {
        Audio audio = new Audio();
        audio.routing.play();
        audio.now += NwdAudioRouting.PENDING_ROUTE_MS - 1;
        audio.routing.play();
        assertSources(audio, 4);
        audio.now++;
        audio.routing.readHealth();
        assertSources(audio, 4);
        audio.routing.play();
        assertSources(audio, 4, 4);
        audio.now += NwdAudioRouting.PENDING_ROUTE_MS;
        assertTrue("Retry grace expiry cannot discard an unacknowledged MCU source request",
                audio.routing.pause());
        assertSources(audio, 4, 4, 0);
    }

    @Test public void latePauseStillCounterqueuesUnacknowledgedSourceRequest() {
        Audio audio = new Audio();
        assertTrue(audio.routing.play());
        audio.now += 10 * NwdAudioRouting.PENDING_ROUTE_MS;
        assertFalse(audio.routing.readHealth().radioOwnsSource());
        assertSources(audio, 4);
        assertTrue("An old queued source=4 request must still be canceled", audio.routing.pause());
        assertSources(audio, 4, 0);
        assertFalse("Counterqueued request must not be canceled repeatedly", audio.routing.pause());
        assertSources(audio, 4, 0);
        assertTrue("A later explicit Play supersedes the pending pause", audio.routing.play());
        assertSources(audio, 4, 0, 4);
    }

    @Test public void handoffIsNotReclaimedAndPlayRequiresReturningToAndroid() {
        Audio audio = new Audio();
        audio.routing.play();
        audio.source = 7;
        assertFalse(audio.routing.readHealth().radioOwnsSource());
        assertFalse(audio.routing.pause());
        assertSources(audio, 4);
        assertFalse(audio.routing.play());
        assertSources(audio, 4);
        audio.source = 4;
        audio.routing.readHealth();
        audio.source = 0;
        audio.routing.readHealth();
        assertFalse(audio.routing.pause());
        assertSources(audio, 4);
        assertTrue(audio.routing.play());
        assertSources(audio, 4, 4);
    }

    @Test public void pendingStartCancellationSurvivesTimeoutAndCanBeExplicitlyResumed() {
        Audio audio = new Audio();
        assertFalse(audio.routing.cancelPendingStart());
        assertFalse(audio.routing.hasPendingStart());
        assertTrue(audio.routing.play());
        assertTrue(audio.routing.hasPendingStart());
        audio.now += 10 * NwdAudioRouting.PENDING_ROUTE_MS;
        assertFalse(audio.routing.readHealth().radioOwnsSource());
        assertTrue(audio.routing.cancelPendingStart());
        assertFalse(audio.routing.hasPendingStart());
        assertSources(audio, 4, 0);
        assertFalse(audio.routing.cancelPendingStart());
        assertSources(audio, 4, 0);
        assertTrue(audio.routing.play());
        assertSources(audio, 4, 0, 4);
    }

    @Test public void cancellationHandlesLateOwnSourceButNeverPausesSettledRadio() {
        Audio late = new Audio();
        assertTrue(late.routing.play());
        late.source = 4;
        assertTrue("Our own source may arrive after the caller's snapshot", late.routing.cancelPendingStart());
        assertSources(late, 4, 0);

        Audio settled = new Audio();
        assertTrue(settled.routing.play());
        settled.source = 4;
        assertTrue(settled.routing.readHealth().radioOwnsSource());
        assertFalse("Acknowledged radio is not an unresolved start", settled.routing.cancelPendingStart());
        assertSources(settled, 4);

        Audio alreadyRadio = new Audio();
        alreadyRadio.source = 4;
        assertTrue(alreadyRadio.routing.play());
        assertFalse(alreadyRadio.routing.cancelPendingStart());
        assertSources(alreadyRadio);
    }

    @Test public void cancellationDoesNotTouchExternalOrUnknownSources() {
        Audio external = new Audio();
        external.routing.play();
        external.source = 7;
        assertFalse(external.routing.cancelPendingStart());
        assertFalse(external.routing.hasPendingStart());
        assertSources(external, 4);
        external.source = 4;
        assertFalse("An external handoff retired our pending ownership", external.routing.cancelPendingStart());
        assertSources(external, 4);

        Audio unknown = new Audio();
        unknown.routing.play();
        unknown.source = -1;
        assertFalse(unknown.routing.cancelPendingStart());
        assertTrue(unknown.routing.hasPendingStart());
        assertSources(unknown, 4);
        unknown.source = 0;
        assertTrue("Unavailable read must not discard our unresolved request", unknown.routing.cancelPendingStart());
        assertSources(unknown, 4, 0);
    }

    @Test public void pendingStartCancellationLeavesLegacyDecoderBehaviorUnchanged() {
        Audio audio = new Audio();
        NwdAudioRouting legacy = new NwdAudioRouting(audio, () -> audio.now, true);
        assertTrue(legacy.play());
        assertFalse(legacy.hasPendingStart());
        int requests = audio.sent.size();
        assertEquals(2, requests);
        audio.now += 10 * NwdAudioRouting.PENDING_ROUTE_MS;
        assertFalse(legacy.cancelPendingStart());
        assertEquals(requests, audio.sent.size());
        assertFalse(legacy.pause());
        assertEquals(requests, audio.sent.size());
    }

    @Test public void otherSourcesCannotQueueRadioThatQuickPauseCannotCancel() {
        for (int source : new int[]{-1, 1, 2, 3, 5, 7, 12}) {
            Audio audio = new Audio();
            audio.source = source;
            assertFalse("MCU Play must not take source " + source, audio.routing.play());
            assertFalse(audio.routing.pause());
            assertSources(audio);
            audio.now += NwdAudioRouting.PENDING_ROUTE_MS;
            assertFalse(audio.routing.play());
            assertSources(audio);
        }
    }

    @Test public void unknownSourceDoesNotMuteOrErasePendingPause() {
        Audio audio = new Audio();
        audio.source = 4;
        audio.routing.pause();
        audio.source = -1;
        assertFalse(audio.routing.readHealth().sourceKnown);
        assertFalse(audio.routing.pause());
        assertFalse("Unknown source must not supersede the pending pause", audio.routing.play());
        assertSources(audio, 0);
        audio.now += 10 * NwdAudioRouting.PENDING_ROUTE_MS;
        audio.source = 4;
        audio.routing.play();
        assertSources(audio, 0, 4);
    }

    @Test public void explicitPauseKeepsUnknownPendingStartCancelableWhenSourceReturns() {
        for (int restoredSource : new int[]{0, 4}) {
            Audio audio = new Audio();
            assertTrue(audio.routing.play());
            audio.now += 10 * NwdAudioRouting.PENDING_ROUTE_MS;
            audio.source = -1;
            assertFalse(audio.routing.pause());
            assertTrue("Unknown source must retain our queued MCU start", audio.routing.hasPendingStart());
            assertFalse(audio.routing.readHealth().sourceKnown);
            assertFalse(audio.routing.pause());
            assertSources(audio, 4);

            audio.source = restoredSource;
            assertTrue("Pause must remain possible when ownership becomes known", audio.routing.pause());
            assertFalse(audio.routing.hasPendingStart());
            assertSources(audio, 4, 0);
        }
    }

    @Test public void explicitPauseRetiresUnknownPendingStartOnKnownExternalHandoff() {
        Audio audio = new Audio();
        assertTrue(audio.routing.play());
        audio.source = -1;
        assertFalse(audio.routing.pause());
        assertTrue(audio.routing.hasPendingStart());
        audio.source = 7;
        assertFalse(audio.routing.pause());
        assertFalse("External handoff ends our authority to cancel startup", audio.routing.hasPendingStart());
        assertSources(audio, 4);
        audio.source = 0;
        assertFalse(audio.routing.cancelPendingStart());
        assertSources(audio, 4);
    }

    @Test public void deferredCancellationSurvivesAnotherClientsLateRadioReadback() {
        for (boolean explicitPause : new boolean[]{false, true}) {
            Audio audio = new Audio();
            assertTrue(audio.routing.play());
            audio.source = -1;
            assertFalse(explicitPause ? audio.routing.pause() : audio.routing.cancelPendingStart());
            assertTrue(audio.routing.hasPendingStart());
            audio.source = 4;
            assertTrue(audio.routing.readHealth().radioOwnsSource());
            assertTrue("A late acknowledgement cannot erase a pending cancellation", audio.routing.hasPendingStart());
            assertSources(audio, 4);
            assertTrue(audio.routing.cancelPendingStart());
            assertFalse(audio.routing.hasPendingStart());
            assertSources(audio, 4, 0);
        }
    }

    @Test public void explicitPlaySupersedesUnknownPauseAndAcknowledgesNormally() {
        Audio audio = new Audio();
        assertTrue(audio.routing.play());
        audio.source = -1;
        assertFalse(audio.routing.pause());
        audio.source = 0;
        assertTrue(audio.routing.play());
        assertSources(audio, 4, 4);
        audio.source = 4;
        assertTrue(audio.routing.readHealth().radioOwnsSource());
        assertFalse("Latest Play must not retain the superseded cancellation", audio.routing.hasPendingStart());
        assertFalse(audio.routing.cancelPendingStart());
        assertSources(audio, 4, 4);
    }

    @Test public void externalHealthReadbackRetiresDeferredCancellationAuthority() {
        Audio audio = new Audio();
        assertTrue(audio.routing.play());
        audio.source = -1;
        assertFalse(audio.routing.pause());
        audio.source = 7;
        assertFalse(audio.routing.readHealth().radioOwnsSource());
        assertFalse(audio.routing.hasPendingStart());
        audio.source = 4;
        assertTrue(audio.routing.readHealth().radioOwnsSource());
        assertFalse("Later external radio playback must not be canceled", audio.routing.cancelPendingStart());
        assertSources(audio, 4);
    }

    @Test public void markingCancellationBeforeWorkerPreservesLateAcknowledgement() {
        Audio audio = new Audio();
        assertFalse(audio.routing.markPendingCancellation());
        assertSources(audio);
        assertTrue(audio.routing.play());
        assertTrue(audio.routing.markPendingCancellation());
        assertSources(audio, 4);
        audio.source = 4;
        assertTrue(audio.routing.readHealth().radioOwnsSource());
        assertTrue("Queued cancellation retains ownership across another client's poll", audio.routing.hasPendingStart());
        assertTrue(audio.routing.cancelPendingStart());
        assertSources(audio, 4, 0);
        assertFalse(audio.routing.markPendingCancellation());
    }

    @Test public void explicitPlaySupersedesMarkedCancellationWithoutPausing() {
        Audio audio = new Audio();
        assertTrue(audio.routing.play());
        assertTrue(audio.routing.markPendingCancellation());
        assertTrue(audio.routing.play());
        assertSources(audio, 4, 4);
        audio.source = 4;
        assertTrue(audio.routing.readHealth().radioOwnsSource());
        assertFalse(audio.routing.hasPendingStart());
        assertFalse(audio.routing.cancelPendingStart());
        assertSources(audio, 4, 4);

        Audio legacyAudio = new Audio();
        NwdAudioRouting legacy = new NwdAudioRouting(legacyAudio, () -> legacyAudio.now, true);
        assertTrue(legacy.play());
        assertFalse("Legacy decoder requests cannot be marked", legacy.markPendingCancellation());
        assertEquals(2, legacyAudio.sent.size());
    }
}
