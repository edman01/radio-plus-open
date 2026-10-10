package fi.radioplus.app;

import android.os.Binder;
import android.os.Bundle;
import android.os.Parcel;
import android.os.RemoteException;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Synthetic Binder/source tests only; they cannot establish real RF or audio operation. */
public final class SpdRadioInteropTest {
    private interface Attempt { void run() throws Exception; }

    private static final class Command {
        final int code, argument, origin;
        final String band;
        final Bundle extras;
        boolean applied;

        Command(int code, int argument, int origin, Bundle extras) {
            this.code = code;
            this.argument = argument;
            this.origin = origin;
            this.extras = extras;
            this.band = extras == null ? null : extras.getString("string");
        }
    }

    private static final class Endpoint extends Binder {
        final List<Integer> calls = new ArrayList<>();
        final List<Command> commands = new ArrayList<>();
        String source = SpdRadioApi.RADIO_SOURCE;
        String band = "FM";
        String[] bands = {"FM", "AM", "FM_FAVORITES", "AM_FAVORITES"};
        int fmFrequency = 98_100, amFrequency = 900;
        int fmMin = 87_500, fmMax = 108_000, fmStep = 50;
        int amMin = 522, amMax = 1620, amStep = 9;
        int playState = 1;
        boolean seeking, preview, autoSearch, local;
        boolean applyCommands = true, rejectCommand, denyCommand, extraAcknowledgement;
        boolean releaseToAnotherSource;
        Runnable afterStatus, afterCommand;

        Endpoint() { attachInterface(null, SpdRadioProbe.DESCRIPTOR); }

        SpdRadioApi adapter() throws RemoteException {
            return new SpdRadioApi(this, new SpdAudioSourceReader(() -> source), 1L);
        }

        @Override protected boolean onTransact(int code, Parcel input, Parcel reply, int flags) {
            assertTrue("No callbacks, source impersonation, reset or settings transactions",
                    code == 5 || code == 6 || code == 13 || code == 16 || code == 20);
            calls.add(code);
            input.enforceInterface(SpdRadioProbe.DESCRIPTOR);
            assertEquals(0, flags);
            assertNotNull(reply);
            if (code == 20) {
                int command = input.readInt(), arg = input.readInt(), origin = input.readInt();
                int present = input.readInt();
                assertTrue(present == 0 || present == 1);
                Bundle extras = present == 0 ? null : Bundle.CREATOR.createFromParcel(input);
                assertEquals(0, input.dataAvail());
                Command item = new Command(command, arg, origin, extras);
                commands.add(item);
                assertEquals(0, origin);
                assertTrue("Only exact SET_BAND or PLAY_STATE are allowed",
                        command == 0x2000 || command == 0x2007);
                if (rejectCommand) return false;
                if (denyCommand) {
                    reply.writeException(new SecurityException("private vendor exception"));
                    return true;
                }
                if (applyCommands) applyQueuedCommands();
                if (afterCommand != null) afterCommand.run();
                reply.writeNoException();
                if (extraAcknowledgement) reply.writeInt(999);
                return true;
            }
            String requestedBand = code == 6 ? input.readString() : null;
            assertEquals(0, input.dataAvail());
            reply.writeNoException();
            switch (code) {
                case 5: writeFrequency(reply, band); break;
                case 6: writeFrequency(reply, requestedBand); break;
                case 13:
                    reply.writeInt(1);
                    for (int value : new int[]{40, 1, 0, 0, seeking ? 1 : 0,
                            preview ? 1 : 0, autoSearch ? 1 : 0, local ? 1 : 0, 0, playState}) {
                        reply.writeInt(value);
                    }
                    if (afterStatus != null) afterStatus.run();
                    break;
                case 16: reply.writeStringArray(bands); break;
                default: fail("Unexpected transaction");
            }
            return true;
        }

        private void writeFrequency(Parcel reply, String requested) {
            boolean am = requested.startsWith("AM");
            reply.writeInt(1);
            reply.writeString(requested);
            for (int value : new int[]{am ? amFrequency : fmFrequency,
                    am ? amMin : fmMin, am ? amMax : fmMax, am ? amStep : fmStep, 0, 40}) {
                reply.writeInt(value);
            }
            reply.writeString("Synthetic");
        }

        void applyQueuedCommands() {
            for (Command command : commands) {
                if (command.applied) continue;
                command.applied = true;
                if (command.code == 0x2000) {
                    assertEquals(1, command.extras.size());
                    assertTrue("FM".equals(command.band) || "AM".equals(command.band));
                    band = command.band;
                    if ("FM".equals(band)) fmFrequency = command.argument;
                    else amFrequency = command.argument;
                    source = SpdRadioApi.RADIO_SOURCE;
                } else {
                    assertNull(command.extras);
                    assertTrue(command.argument == 0 || command.argument == 1);
                    playState = command.argument;
                    if (playState == 1) source = SpdRadioApi.RADIO_SOURCE;
                    else if (releaseToAnotherSource) source = "com.example.music";
                }
            }
        }
    }

    private static void rejected(Attempt attempt) throws Exception {
        try {
            attempt.run();
            fail("Expected a non-replayable command rejection");
        } catch (SpdRadioApi.CommandRejectedException expected) {
            assertFalse(String.valueOf(expected.getMessage()).contains("private vendor"));
        }
    }

    @Test public void constructionAndPollingNeverActivateRegisterOrWrite() throws Exception {
        Endpoint endpoint = new Endpoint();
        SpdRadioApi api = endpoint.adapter();
        assertTrue(endpoint.calls.isEmpty());
        assertSame(endpoint, api.asBinder());
        assertEquals(98_100, api.frequency().grid.frequency);
        assertEquals(0, api.getCurrentBand());
        assertEquals(98_100, api.getCurrentFreq());
        assertEquals("Synthetic", api.getCurrentFreqRdsPs());
        assertTrue(api.IsStereo());
        assertFalse(api.IsAS());
        assertFalse(api.IsPS());
        assertFalse(api.IsSeek());
        assertFalse(api.IsScan());
        assertFalse(api.IsDxLocal());
        assertTrue(api.readHealth().radioOwnsSource());
        assertFalse(api.readHealth().muted);
        assertTrue(endpoint.commands.isEmpty());
        assertFalse(api.hasPendingCommand());
    }

    @Test public void fmAndAmTuneUseOneAtomicBandFrequencyCommandEach() throws Exception {
        Endpoint endpoint = new Endpoint();
        SpdRadioApi api = endpoint.adapter();
        assertTrue(api.tuneToBand(0, 99_500, () -> true));
        assertEquals(1, endpoint.commands.size());
        assertCommand(endpoint.commands.get(0), 0x2000, 99_500, "FM");
        assertTrue(api.tuneToBand(3, 900, () -> true));
        assertEquals(2, endpoint.commands.size());
        assertCommand(endpoint.commands.get(1), 0x2000, 900, "AM");
        assertEquals(3, api.getCurrentBand());
        assertEquals(900, api.getCurrentFreq());
        assertFalse(api.hasPendingCommand());
    }

    @Test public void favoritesBandNormalizesOnlyToAnExplicitFmOrAmTarget() throws Exception {
        Endpoint endpoint = new Endpoint();
        endpoint.band = "FM_FAVORITES";
        SpdRadioApi api = endpoint.adapter();
        api.onManualDownEvent();
        assertCommand(endpoint.commands.get(0), 0x2000, 98_200, "FM");
        api.onManualUpEvent();
        assertCommand(endpoint.commands.get(1), 0x2000, 98_100, "FM");
        api.onBandEvent();
        assertCommand(endpoint.commands.get(2), 0x2000, 900, "AM");
        assertEquals(3, endpoint.commands.size());
    }

    @Test public void sameFrequencyTuneDoesNotReacquireOrRetune() throws Exception {
        Endpoint endpoint = new Endpoint();
        SpdRadioApi api = endpoint.adapter();
        assertTrue(api.tuneToBand(0, 98_100));
        endpoint.band = "AM";
        assertTrue(api.tuneToBand(3, 900));
        assertTrue(endpoint.commands.isEmpty());
        assertFalse(api.hasPendingCommand());
    }

    @Test public void invalidTargetsMissingBandsAndMalformedGridNeverWrite() throws Exception {
        Endpoint endpoint = new Endpoint();
        SpdRadioApi api = endpoint.adapter();
        for (int band : new int[]{-1, 1, 2, 4}) rejected(() -> api.tuneToBand(band, 98_100));
        rejected(() -> api.tuneToBand(0, 98_150));
        rejected(() -> api.tuneToBand(3, 901));
        rejected(() -> api.tuneToBand(0, 900));
        endpoint.bands = new String[]{"FM"};
        rejected(() -> api.tuneToBand(3, 900));
        endpoint.bands = new String[]{"FM", "AM"};
        endpoint.fmStep = 200;
        endpoint.fmMin = 87_500;
        rejected(() -> api.tuneToBand(0, 98_200));
        endpoint.fmStep = 0;
        rejected(api::requestPlayAudio);
        assertTrue(endpoint.commands.isEmpty());
    }

    @Test public void unknownSourceAndInactiveRadioCannotTuneOrImplicitlyPlay() throws Exception {
        Endpoint endpoint = new Endpoint();
        SpdRadioApi api = endpoint.adapter();
        endpoint.source = null;
        rejected(api::requestPlayAudio);
        rejected(() -> api.tuneToBand(0, 99_500));
        assertFalse(api.pauseRadioSource());
        endpoint.source = "com.example.music";
        rejected(() -> api.tuneToBand(0, 99_500));
        assertFalse(api.pauseRadioSource());
        endpoint.source = SpdRadioApi.RADIO_SOURCE;
        endpoint.playState = 0;
        rejected(() -> api.tuneToBand(0, 99_500));
        assertTrue(endpoint.commands.isEmpty());
    }

    @Test public void everyActiveSearchStateBlocksPlayTuneAndPause() throws Exception {
        for (int active = 0; active < 3; active++) {
            Endpoint endpoint = new Endpoint();
            endpoint.seeking = active == 0;
            endpoint.preview = active == 1;
            endpoint.autoSearch = active == 2;
            SpdRadioApi api = endpoint.adapter();
            rejected(api::requestPlayAudio);
            rejected(() -> api.tuneToBand(0, 99_500));
            rejected(api::pauseRadioSource);
            assertTrue(endpoint.commands.isEmpty());
        }
    }

    @Test public void staleGenerationBeforeAndAfterReadsNeverWrites() throws Exception {
        Endpoint endpoint = new Endpoint();
        SpdRadioApi api = endpoint.adapter();
        assertFalse(api.tuneToBand(0, 99_500, () -> false));
        assertFalse(api.requestPlayAudio(() -> false));
        assertFalse(api.pauseRadioSource(() -> false));
        assertTrue(endpoint.calls.isEmpty());
        AtomicBoolean current = new AtomicBoolean(true);
        endpoint.afterStatus = () -> current.set(false);
        assertFalse(api.tuneToBand(0, 99_500, current::get));
        assertTrue(endpoint.commands.isEmpty());
        assertFalse(api.hasPendingCommand());
    }

    @Test public void sourceChangeDuringPreflightPreventsAnyCommand() throws Exception {
        Endpoint endpoint = new Endpoint();
        endpoint.afterStatus = () -> endpoint.source = "com.example.music";
        SpdRadioApi api = endpoint.adapter();
        rejected(() -> api.tuneToBand(0, 99_500));
        endpoint.source = SpdRadioApi.RADIO_SOURCE;
        rejected(api::requestPlayAudio);
        assertTrue(endpoint.commands.isEmpty());
    }

    @Test public void healthyPlayIsIdempotentAndExplicitActivationUsesOnlyPlayOneZero() throws Exception {
        Endpoint endpoint = new Endpoint();
        SpdRadioApi api = endpoint.adapter();
        assertTrue(api.requestPlayAudio());
        assertTrue(endpoint.commands.isEmpty());
        endpoint.source = "com.example.music";
        endpoint.playState = 0;
        assertTrue(api.requestPlayAudio(() -> true, true));
        assertEquals(1, endpoint.commands.size());
        assertCommand(endpoint.commands.get(0), 0x2007, 1, null);
        assertTrue(api.requestPlayAudio());
        assertEquals(1, endpoint.commands.size());
    }

    @Test public void implicitActivationNeverAcquiresAnExternalUnknownOrPausedRadioSource() throws Exception {
        for (String owner : new String[]{"com.example.music", null, SpdRadioApi.RADIO_SOURCE}) {
            Endpoint endpoint = new Endpoint();
            endpoint.source = owner;
            endpoint.playState = SpdRadioApi.RADIO_SOURCE.equals(owner) ? 0 : 1;
            SpdRadioApi api = endpoint.adapter();
            rejected(() -> api.requestPlayAudio(() -> true, false));
            rejected(() -> api.requestPlayAudio(() -> true));
            rejected(api::requestPlayAudio);
            assertTrue(endpoint.commands.isEmpty());
            assertFalse(api.hasPendingCommand());
        }
    }

    @Test public void implicitActivationRechecksOwnershipAfterEarlierHealthySnapshot() throws Exception {
        Endpoint endpoint = new Endpoint();
        SpdRadioApi api = endpoint.adapter();
        RadioPlaybackHealthReader.Snapshot earlier = api.readHealth();
        assertTrue(earlier.sourceKnown);
        assertTrue(earlier.radioOwnsSource());
        assertTrue(earlier.muteKnown);
        assertFalse(earlier.muted);
        endpoint.source = "com.example.music";
        rejected(() -> api.requestPlayAudio(() -> true, false));
        assertTrue(endpoint.commands.isEmpty());
        assertEquals("com.example.music", endpoint.source);
        endpoint.source = SpdRadioApi.RADIO_SOURCE;
        assertTrue(api.requestPlayAudio(() -> true, false));
        assertTrue(endpoint.commands.isEmpty());
    }

    @Test public void pauseReleasesOnlyOwnedRadioWithoutGlobalMuteOrSourceSpoof() throws Exception {
        Endpoint endpoint = new Endpoint();
        endpoint.releaseToAnotherSource = true;
        SpdRadioApi api = endpoint.adapter();
        assertTrue(api.pauseRadioSource());
        assertCommand(endpoint.commands.get(0), 0x2007, 0, null);
        assertEquals("com.example.music", endpoint.source);
        assertFalse(api.pauseRadioSource());
        assertEquals(1, endpoint.commands.size());
        assertTrue(api.readHealth().muted);
        assertFalse(api.readHealth().radioOwnsSource());
    }

    @Test public void unconfirmedTuneIsNotReplayedAndCanResolveFromLaterFreshState() throws Exception {
        Endpoint endpoint = new Endpoint();
        endpoint.applyCommands = false;
        SpdRadioApi api = endpoint.adapter();
        assertFalse(api.tuneToBand(0, 99_500));
        assertTrue(api.hasPendingCommand());
        rejected(() -> api.tuneToBand(0, 99_500));
        rejected(api::requestPlayAudio);
        assertEquals(1, endpoint.commands.size());
        endpoint.applyQueuedCommands();
        assertTrue(api.requestPlayAudio());
        assertFalse(api.hasPendingCommand());
        assertEquals(1, endpoint.commands.size());
    }

    @Test public void acceptedPlayWithoutOwnedSourceCannotClaimSuccessOrReplay() throws Exception {
        Endpoint endpoint = new Endpoint();
        endpoint.applyCommands = false;
        endpoint.source = "com.example.music";
        endpoint.playState = 0;
        SpdRadioApi api = endpoint.adapter();
        assertFalse(api.requestPlayAudio(() -> true, true));
        assertTrue(api.hasPendingAudioConfirmation());
        rejected(api::requestPlayAudio);
        assertEquals(1, endpoint.commands.size());
    }

    @Test public void sourceLossAfterTuneDoesNotTriggerTrailingPlayOrRetune() throws Exception {
        Endpoint endpoint = new Endpoint();
        endpoint.afterCommand = () -> endpoint.source = "com.example.music";
        SpdRadioApi api = endpoint.adapter();
        assertFalse(api.tuneToBand(0, 99_500));
        rejected(api::requestPlayAudio);
        assertFalse(api.pauseRadioSource());
        assertEquals(1, endpoint.commands.size());
        endpoint.source = SpdRadioApi.RADIO_SOURCE;
        assertTrue(api.requestPlayAudio());
        assertEquals(1, endpoint.commands.size());
    }

    @Test public void canceledTuneCanBeExplicitlyPausedAndResumedWithoutReplay() throws Exception {
        Endpoint endpoint = new Endpoint();
        AtomicBoolean current = new AtomicBoolean(true);
        endpoint.afterCommand = () -> current.set(false);
        SpdRadioApi api = endpoint.adapter();
        assertFalse(api.tuneToBand(0, 99_500, current::get));
        assertTrue(api.hasPendingCommand());
        endpoint.afterCommand = null;
        assertTrue(api.pauseRadioSource());
        assertFalse(api.hasPendingCommand());
        assertTrue(api.requestPlayAudio(() -> true, true));
        assertEquals(3, endpoint.commands.size());
        assertCommand(endpoint.commands.get(0), 0x2000, 99_500, "FM");
        assertCommand(endpoint.commands.get(1), 0x2007, 0, null);
        assertCommand(endpoint.commands.get(2), 0x2007, 1, null);
    }

    @Test public void mainThreadCancellationDoesNotWaitForCommandLockOrCancelNativeQueue() throws Exception {
        Endpoint endpoint = new Endpoint();
        endpoint.playState = 0;
        endpoint.applyCommands = false;
        CountDownLatch submitted = new CountDownLatch(1);
        endpoint.afterCommand = submitted::countDown;
        SpdRadioApi api = new SpdRadioApi(endpoint,
                new SpdAudioSourceReader(() -> endpoint.source), 1500L);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean result = new AtomicBoolean(true);
        Thread request = new Thread(() -> {
            try { result.set(api.requestPlayAudio(() -> true, true)); }
            catch (Throwable error) { failure.set(error); }
        });
        CountDownLatch canceled = new CountDownLatch(1);
        Thread cancel = new Thread(() -> {
            api.cancelPendingAudioStart();
            canceled.countDown();
        });
        request.start();
        try {
            assertTrue(submitted.await(2, TimeUnit.SECONDS));
            cancel.start();
            assertTrue("Cancellation must not acquire the confirmation lock",
                    canceled.await(500, TimeUnit.MILLISECONDS));
            request.join(1000L);
            assertFalse(request.isAlive());
            assertNull(failure.get());
            assertFalse(result.get());
            assertTrue(api.hasPendingCommand());
            assertEquals(1, endpoint.commands.size());
            // Cancellation only ends our wait: the accepted OEM queue remains.
            endpoint.applyQueuedCommands();
            assertTrue(api.requestPlayAudio());
            assertEquals(1, endpoint.commands.size());
        } finally {
            api.cancelPendingAudioStart();
            request.join(2000L);
            if (cancel.getState() != Thread.State.NEW) cancel.join(2000L);
        }
    }

    @Test public void pauseQueuesAfterPendingPlayEvenWhenServiceStillReportsPaused() throws Exception {
        Endpoint endpoint = new Endpoint();
        endpoint.playState = 0;
        endpoint.applyCommands = false;
        SpdRadioApi api = endpoint.adapter();
        assertFalse(api.requestPlayAudio(() -> true, true));
        assertEquals(0, endpoint.playState);
        endpoint.applyCommands = true;
        assertFalse(api.pauseRadioSource());
        assertEquals(0, endpoint.playState);
        assertTrue(api.hasPendingCommand());
        rejected(api::requestPlayAudio);
        assertTrue(api.requestPlayAudio(() -> true, true));
        assertEquals(1, endpoint.playState);
        assertEquals(3, endpoint.commands.size());
        assertCommand(endpoint.commands.get(0), 0x2007, 1, null);
        assertCommand(endpoint.commands.get(1), 0x2007, 0, null);
        assertCommand(endpoint.commands.get(2), 0x2007, 1, null);
    }

    @Test public void delayedExternalPlayCanBePausedOnItsCapturedOwnerWithoutLatePlayback() throws Exception {
        Endpoint endpoint = new Endpoint();
        endpoint.source = "com.example.music";
        endpoint.playState = 0;
        endpoint.applyCommands = false;
        endpoint.releaseToAnotherSource = true;
        SpdRadioApi api = endpoint.adapter();
        assertFalse(api.requestPlayAudio(() -> true, true));
        assertTrue(api.hasPendingAudioStart());
        api.cancelPendingAudioStart();
        assertFalse(api.pauseRadioSource());
        assertFalse(api.hasPendingAudioStart());
        assertTrue(api.hasPendingCommand());
        assertEquals(2, endpoint.commands.size());
        assertCommand(endpoint.commands.get(0), 0x2007, 1, null);
        assertCommand(endpoint.commands.get(1), 0x2007, 0, null);
        assertFalse(api.pauseRadioSource());
        assertEquals(2, endpoint.commands.size());
        endpoint.applyQueuedCommands();
        assertEquals(0, endpoint.playState);
        assertEquals("com.example.music", endpoint.source);
    }

    @Test public void queuedPlayPauseRejectsAnotherUnknownOrChangingOwner() throws Exception {
        Endpoint endpoint = new Endpoint();
        endpoint.source = "com.example.music";
        endpoint.playState = 0;
        endpoint.applyCommands = false;
        SpdRadioApi api = endpoint.adapter();
        assertFalse(api.requestPlayAudio(() -> true, true));
        endpoint.source = "com.example.other";
        assertFalse(api.pauseRadioSource());
        endpoint.source = null;
        assertFalse(api.pauseRadioSource());
        endpoint.source = "com.example.music";
        endpoint.afterStatus = () -> endpoint.source = "com.example.other";
        assertFalse(api.pauseRadioSource());
        assertEquals(1, endpoint.commands.size());
    }

    @Test public void manualActivationTimeoutQueuesOnlyItsOwnAbsolutePause() throws Exception {
        Endpoint endpoint = new Endpoint();
        endpoint.source = "com.example.music";
        endpoint.playState = 0;
        endpoint.applyCommands = false;
        endpoint.releaseToAnotherSource = true;
        SpdRadioApi api = endpoint.adapter();
        assertFalse(api.requestPlayAudioForTuning(() -> true));
        assertEquals(2, endpoint.commands.size());
        assertCommand(endpoint.commands.get(0), 0x2007, 1, null);
        assertCommand(endpoint.commands.get(1), 0x2007, 0, null);
        assertFalse(api.hasPendingAudioStart());
        assertTrue(api.hasPendingCommand());
        endpoint.applyQueuedCommands();
        assertEquals(0, endpoint.playState);
        assertEquals("com.example.music", endpoint.source);
    }

    @Test public void manualActivationPreflightFailureNeverPausesAnOlderPendingPlay() throws Exception {
        Endpoint endpoint = new Endpoint();
        endpoint.source = "com.example.music";
        endpoint.playState = 0;
        endpoint.applyCommands = false;
        SpdRadioApi api = endpoint.adapter();
        assertFalse(api.requestPlayAudio(() -> true, true));
        endpoint.autoSearch = true;
        rejected(() -> api.requestPlayAudioForTuning(() -> true));
        assertEquals(1, endpoint.commands.size());
        assertTrue(api.hasPendingAudioStart());
    }

    @Test public void obsoleteManualActivationDoesNotIssueCleanupOnTheNewGeneration() throws Exception {
        Endpoint endpoint = new Endpoint();
        endpoint.source = "com.example.music";
        endpoint.playState = 0;
        endpoint.applyCommands = false;
        AtomicBoolean current = new AtomicBoolean(true);
        endpoint.afterCommand = () -> current.set(false);
        SpdRadioApi api = endpoint.adapter();
        assertFalse(api.requestPlayAudioForTuning(current::get));
        assertEquals(1, endpoint.commands.size());
        assertTrue(api.hasPendingAudioStart());
    }

    @Test public void onlyNewExplicitPlayCanSupersedePendingAudioAfterSourceChange() throws Exception {
        for (boolean pendingPause : new boolean[]{false, true}) {
            Endpoint endpoint = new Endpoint();
            endpoint.applyCommands = false;
            endpoint.source = pendingPause ? SpdRadioApi.RADIO_SOURCE : "com.example.music";
            endpoint.playState = pendingPause ? 1 : 0;
            SpdRadioApi api = endpoint.adapter();
            assertFalse(pendingPause ? api.pauseRadioSource() : api.requestPlayAudio(() -> true, true));
            endpoint.source = "com.example.other";
            rejected(api::requestPlayAudio);
            assertEquals(1, endpoint.commands.size());
            assertFalse(api.requestPlayAudio(() -> true, true));
            assertEquals(2, endpoint.commands.size());
            assertCommand(endpoint.commands.get(1), 0x2007, 1, null);
            endpoint.applyQueuedCommands();
            assertEquals(1, endpoint.playState);
            assertEquals(SpdRadioApi.RADIO_SOURCE, endpoint.source);
            assertTrue(api.requestPlayAudio());
            assertEquals(2, endpoint.commands.size());
        }
    }

    @Test public void explicitAudioSupersessionStillRejectsUnknownFlappingAndTuningPending() throws Exception {
        Endpoint endpoint = new Endpoint();
        endpoint.applyCommands = false;
        endpoint.playState = 0;
        endpoint.source = "com.example.music";
        SpdRadioApi api = endpoint.adapter();
        assertFalse(api.requestPlayAudio(() -> true, true));
        endpoint.source = null;
        rejected(() -> api.requestPlayAudio(() -> true, true));
        endpoint.source = "com.example.other";
        endpoint.afterStatus = () -> endpoint.source = endpoint.source.equals("com.example.other")
                ? "com.example.third" : "com.example.other";
        rejected(() -> api.requestPlayAudio(() -> true, true));
        assertEquals(1, endpoint.commands.size());
        Endpoint tuning = new Endpoint();
        tuning.applyCommands = false;
        SpdRadioApi tuneApi = tuning.adapter();
        assertFalse(tuneApi.tuneToBand(0, 99_500));
        tuning.source = "com.example.music";
        rejected(() -> tuneApi.requestPlayAudio(() -> true, true));
        assertEquals(1, tuning.commands.size());
    }

    @Test public void rejectedDeniedAndMalformedCommandAcknowledgementsAreNotSuccess() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            Endpoint endpoint = new Endpoint();
            endpoint.rejectCommand = mode == 0;
            endpoint.denyCommand = mode == 1;
            endpoint.extraAcknowledgement = mode == 2;
            endpoint.applyCommands = false;
            SpdRadioApi api = endpoint.adapter();
            rejected(() -> api.tuneToBand(0, 99_500));
            rejected(() -> api.tuneToBand(0, 99_500));
            assertEquals(1, endpoint.commands.size());
            assertTrue(api.hasPendingCommand());
        }
    }

    @Test public void unsupportedOperationsCannotRegisterCallbacksOrWriteSettings() throws Exception {
        Endpoint endpoint = new Endpoint();
        SpdRadioApi api = endpoint.adapter();
        for (Attempt attempt : Arrays.<Attempt>asList(
                () -> api.registerRadioClientBinder(new Binder()), api::unRegisterRadioClientBinder,
                () -> api.registerRadioCallback(null), () -> api.unRegisterRadioCallback(null),
                api::onASEvent, api::onPSEvent, api::onScanEvent, api::onSeekDownEvent,
                api::onSeekUpEvent, api::onLocDxEvent, () -> api.gotoFreqIndex(0),
                api::favoriteCurrentFreq, () -> api.gotoFreq2("98.1"),
                api::requestAudioFocus, api::releaseAudioFocus)) rejected(attempt);
        assertTrue(endpoint.calls.isEmpty());
    }

    private static void assertCommand(Command command, int code, int argument, String band) {
        assertEquals(code, command.code);
        assertEquals(argument, command.argument);
        assertEquals(0, command.origin);
        assertEquals(band, command.band);
        if (band == null) assertNull(command.extras);
        else assertEquals(1, command.extras.size());
    }
}
