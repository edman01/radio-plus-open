package fi.radioplus.app;

import android.content.ComponentName;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Binder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.view.KeyEvent;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic G5 MCU contracts, not execution of OEM binaries or physical audio/wheel tests. */
public final class G5InteropTest {
    private static NwdRadioApi api(K4811InteropTest.Endpoint endpoint) throws RemoteException {
        return new NwdRadioApi(RadioBackendProfile.NWD_G5_242, endpoint, endpoint.audio.routing);
    }

    private static boolean tune(NwdRadioApi api, K4811InteropTest.Endpoint endpoint, int band, int khz) {
        try { return api.tuneToBand(band, khz, endpoint.current::get); }
        catch (RemoteException rejected) { return false; }
    }

    private static void assertNoTunerWrites(K4811InteropTest.Endpoint endpoint) {
        for (int transaction : new int[]{1, 3, 4, 5, 6, 7, 8, 27}) {
            assertEquals("Unexpected tuner transaction " + transaction, 0L, endpoint.count(transaction));
        }
    }

    private static void assertSources(K4811InteropTest.Endpoint endpoint, int... sources) {
        assertEquals(sources.length, endpoint.audio.sent.size());
        for (int i = 0; i < sources.length; i++) {
            Intent request = endpoint.audio.sent.get(i);
            assertEquals(NwdAudioRouting.CHANGE_SOURCE, request.getAction());
            assertEquals(NwdAudioRouting.KERNEL_PACKAGE, request.getPackage());
            assertEquals("No global mute or decoder lifecycle payload", 1, request.getExtras().size());
            assertTrue(request.getExtras().get("extra_source_id") instanceof Byte);
            assertEquals(sources[i], request.getByteExtra("extra_source_id", (byte) -1));
        }
    }

    @Test public void constructorOnlyAcceptsRuntimeMcuWithReadOnlyProbe() throws Exception {
        K4811InteropTest.Endpoint supported = new K4811InteropTest.Endpoint();
        assertSame(supported, api(supported).asBinder());
        assertEquals(1, supported.calls.size());
        assertEquals(Integer.valueOf(29), supported.calls.get(0));
        assertSources(supported);
        for (int type : new int[]{-1, 1, 2, 3, 4, Integer.MAX_VALUE}) {
            K4811InteropTest.Endpoint rejected = new K4811InteropTest.Endpoint();
            rejected.type = type;
            NwdRadioApi.UnsupportedTunerException error = assertThrows(
                    NwdRadioApi.UnsupportedTunerException.class, () -> api(rejected));
            assertEquals(type, error.tunerType);
            assertTrue(RadioApiFactory.endpointProblem(error).contains("type " + type + " "));
            assertEquals(1, rejected.calls.size());
            assertEquals(Integer.valueOf(29), rejected.calls.get(0));
            assertSources(rejected);
        }
    }

    @Test public void genericEndpointFailuresDoNotExposeVendorErrorText() {
        assertEquals("The stock radio endpoint or tuner implementation is not supported",
                RadioApiFactory.endpointProblem(new RemoteException("private vendor failure details")));
    }

    @Test public void wrongDescriptorAndDecoderLifecycleFailBeforeAnyCommand() throws Exception {
        K4811InteropTest.Endpoint endpoint = new K4811InteropTest.Endpoint();
        Binder wrong = new Binder();
        wrong.attachInterface(null, "another.radio.service");
        assertThrows(RemoteException.class, () -> new NwdRadioApi(
                RadioBackendProfile.NWD_G5_242, wrong, endpoint.audio.routing));
        NwdAudioRouting decoderLifecycle = new NwdAudioRouting(endpoint.audio, () -> 100L, true);
        assertThrows(RemoteException.class, () -> new NwdRadioApi(
                RadioBackendProfile.NWD_G5_242, endpoint, decoderLifecycle));
        assertTrue(endpoint.calls.isEmpty());
        assertSources(endpoint);
    }

    @Test public void fmOneWayAcknowledgementDoesNotReplayTuneOrRestartAudio() throws Exception {
        K4811InteropTest.Endpoint endpoint = new K4811InteropTest.Endpoint();
        endpoint.readsBeforeAcknowledgement = 4;
        NwdRadioApi radio = api(endpoint);
        assertTrue(radio.tuneToBand(0, 101700, endpoint.current::get));
        assertEquals(10170, endpoint.frequency);
        assertEquals(4, endpoint.readsAfterTune);
        assertEquals(1L, endpoint.count(1));
        assertEquals(0, endpoint.lastPreset);
        assertTrue(radio.tuneToBand(0, 101700, endpoint.current::get));
        assertEquals("An already selected station must not be written twice", 1L, endpoint.count(1));
        assertSources(endpoint);
    }

    @Test public void missingAcknowledgementBlocksLaterWritesUntilObservedWithoutReplay() throws Exception {
        K4811InteropTest.Endpoint endpoint = new K4811InteropTest.Endpoint();
        endpoint.neverAcknowledge = true;
        NwdRadioApi radio = api(endpoint);
        long start = SystemClock.elapsedRealtime();
        assertFalse(tune(radio, endpoint, 0, 101700));
        assertTrue(SystemClock.elapsedRealtime() - start < 6000L);
        assertFalse(tune(radio, endpoint, 0, 102100));
        assertEquals(1L, endpoint.count(1));
        assertEquals(9810, endpoint.frequency);
        endpoint.acknowledgePending();
        endpoint.neverAcknowledge = false;
        assertTrue(radio.tuneToBand(0, 102100, endpoint.current::get));
        assertEquals(10210, endpoint.frequency);
        assertEquals(2L, endpoint.count(1));
        assertSources(endpoint);
    }

    @Test public void rejectedBinderWriteCannotClaimSuccessOrRetry() throws Exception {
        K4811InteropTest.Endpoint endpoint = new K4811InteropTest.Endpoint();
        endpoint.rejectTune = true;
        assertFalse(tune(api(endpoint), endpoint, 0, 101700));
        assertEquals(9810, endpoint.frequency);
        assertEquals(1L, endpoint.count(1));
        assertSources(endpoint);
    }

    @Test public void fmAmSwitchingObservesRawBandAndUsesCorrectFrequencyUnits() throws Exception {
        K4811InteropTest.Endpoint endpoint = new K4811InteropTest.Endpoint();
        NwdRadioApi radio = api(endpoint);
        assertTrue(radio.tuneToBand(3, 900, endpoint.current::get));
        assertEquals(3, endpoint.lastTuneBand);
        assertEquals(900, endpoint.lastTuneFrequency);
        assertEquals(3, radio.getCurrentBand());
        assertEquals(900, radio.getCurrentFreq());
        endpoint.band = 5;
        assertTrue(radio.tuneToBand(3, 999, endpoint.current::get));
        assertEquals("Selecting AM preserves the already selected AM bank", 5, endpoint.lastTuneBand);
        assertTrue(radio.tuneToBand(0, 101700, endpoint.current::get));
        assertEquals(0, endpoint.lastTuneBand);
        assertEquals(10170, endpoint.lastTuneFrequency);
        assertEquals(101700, radio.getCurrentFreq());
        assertEquals(3L, endpoint.count(1));
        assertSources(endpoint);
    }

    @Test public void bandChangeThatAlreadySelectedTargetDoesNotWriteAgain() throws Exception {
        K4811InteropTest.Endpoint endpoint = new K4811InteropTest.Endpoint();
        assertTrue(api(endpoint).tuneToBand(3, 999, endpoint.current::get));
        assertEquals(3, endpoint.band);
        assertEquals(999, endpoint.frequency);
        assertEquals(0L, endpoint.count(1));
        assertSources(endpoint);
    }

    @Test public void missingOrInvalidBandGridCannotAcquireAudioOrTune() throws Exception {
        for (int scenario = 0; scenario < 4; scenario++) {
            K4811InteropTest.Endpoint endpoint = new K4811InteropTest.Endpoint();
            endpoint.audio.source = 0;
            if (scenario == 0) endpoint.fmAvailable = false;
            if (scenario == 1) endpoint.fmStep = 0;
            if (scenario == 2) endpoint.amAvailable = false;
            if (scenario == 3) endpoint.amStep = 0;
            int targetBand = scenario < 2 ? 0 : 3;
            int targetFrequency = scenario < 2 ? 101700 : 999;
            NwdRadioApi radio = api(endpoint);
            assertThrows(RemoteException.class, () -> {
                radio.validateTuningTarget(targetBand, targetFrequency);
                radio.requestPlayAudio();
            });
            assertNoTunerWrites(endpoint);
            assertSources(endpoint);
        }
    }

    @Test public void manualStepsHonorObservedFmAndAmGrids() throws Exception {
        K4811InteropTest.Endpoint fm = new K4811InteropTest.Endpoint();
        fm.fmStep = 20;
        fm.frequency = 10790;
        NwdRadioApi fmRadio = api(fm);
        fmRadio.onManualDownEvent();
        assertEquals(8750, fm.frequency);
        fmRadio.onManualUpEvent();
        assertEquals(10790, fm.frequency);
        assertEquals(2L, fm.count(1));
        assertSources(fm);

        K4811InteropTest.Endpoint am = new K4811InteropTest.Endpoint();
        am.band = 3;
        am.amStep = 18;
        am.frequency = 1008;
        NwdRadioApi amRadio = api(am);
        amRadio.onManualDownEvent();
        assertEquals(1026, am.frequency);
        amRadio.onManualUpEvent();
        assertEquals(1008, am.frequency);
        assertEquals(2L, am.count(1));
        assertSources(am);
    }

    @Test public void unverifiedScanSeekAndIntroNeverIssueFirmwareCommands() throws Exception {
        K4811InteropTest.Endpoint endpoint = new K4811InteropTest.Endpoint();
        NwdRadioApi radio = api(endpoint);
        assertFalse(RadioApiFactory.supportsScanning(radio));
        assertThrows(RemoteException.class, radio::onASEvent);
        assertThrows(RemoteException.class, radio::onPSEvent);
        assertThrows(RemoteException.class, radio::onScanEvent);
        assertThrows(RemoteException.class, radio::onSeekDownEvent);
        assertThrows(RemoteException.class, radio::onSeekUpEvent);
        assertNoTunerWrites(endpoint);
        assertSources(endpoint);
    }

    @Test public void existingFirmwareScanOrIntroCannotBeInterruptedByTouchTune() throws Exception {
        for (int busy : new int[]{2, 3}) {
            K4811InteropTest.Endpoint endpoint = new K4811InteropTest.Endpoint();
            endpoint.state = busy;
            assertFalse(tune(api(endpoint), endpoint, 0, 101700));
            assertNoTunerWrites(endpoint);
            assertSources(endpoint);
        }
    }

    @Test public void sourceHandoffOrCancellationStopsContinuationWithoutTakingAudioBack() throws Exception {
        K4811InteropTest.Endpoint duringBand = new K4811InteropTest.Endpoint();
        duringBand.handoffOnBand = true;
        assertFalse(tune(api(duringBand), duringBand, 3, 900));
        assertEquals(0L, duringBand.count(1));
        assertSources(duringBand);
        for (boolean handoff : new boolean[]{false, true}) {
            K4811InteropTest.Endpoint duringTune = new K4811InteropTest.Endpoint();
            duringTune.handoffOnTune = handoff;
            duringTune.cancelOnTune = !handoff;
            assertFalse(tune(api(duringTune), duringTune, 0, 101700));
            assertEquals(1L, duringTune.count(1));
            assertSources(duringTune);
        }
        K4811InteropTest.Endpoint cancelled = new K4811InteropTest.Endpoint();
        cancelled.current.set(false);
        assertFalse(tune(api(cancelled), cancelled, 0, 101700));
        assertNoTunerWrites(cancelled);
        assertSources(cancelled);
    }

    @Test public void playPausePlayQueuesOnlyLatestSourceIntentWithoutDecoderReopen() throws Exception {
        K4811InteropTest.Endpoint endpoint = new K4811InteropTest.Endpoint();
        endpoint.audio.source = 0;
        NwdRadioApi radio = api(endpoint);
        assertTrue(radio.requestPlayAudio());
        assertTrue(radio.hasPendingAudioStart());
        assertTrue(radio.pauseRadioSource());
        assertTrue(radio.requestPlayAudio());
        assertTrue(radio.requestPlayAudio());
        assertSources(endpoint, 4, 0, 4);
        assertNoTunerWrites(endpoint);
    }

    @Test public void settledPlayAndPauseAreIdempotentAndNeverPauseExternalSource() throws Exception {
        K4811InteropTest.Endpoint endpoint = new K4811InteropTest.Endpoint();
        NwdRadioApi radio = api(endpoint);
        assertTrue(radio.requestPlayAudio());
        assertSources(endpoint);
        assertTrue(radio.pauseRadioSource());
        endpoint.audio.source = 0;
        assertFalse(radio.pauseRadioSource());
        assertSources(endpoint, 0);
        endpoint.audio.source = 7;
        assertFalse(radio.readHealth().radioOwnsSource());
        assertFalse(radio.pauseRadioSource());
        assertFalse(radio.requestPlayAudio());
        assertSources(endpoint, 0);
        assertNoTunerWrites(endpoint);
    }

    @Test public void unknownSourceKeepsPendingStartCancelableWhenReadbackReturns() throws Exception {
        K4811InteropTest.Endpoint endpoint = new K4811InteropTest.Endpoint();
        endpoint.audio.source = 0;
        NwdRadioApi radio = api(endpoint);
        assertTrue(radio.requestPlayAudio());
        endpoint.audio.source = -1;
        assertFalse(radio.pauseRadioSource());
        assertTrue(radio.hasPendingAudioStart());
        assertSources(endpoint, 4);
        endpoint.audio.source = 4;
        assertTrue(radio.readHealth().radioOwnsSource());
        assertTrue(radio.hasPendingAudioStart());
        assertTrue(radio.cancelPendingAudioStart());
        assertFalse(radio.hasPendingAudioStart());
        assertSources(endpoint, 4, 0);
        assertNoTunerWrites(endpoint);
    }

    @Test public void localDxUsesSingleCommandAndReadbackWithoutRetuning() throws Exception {
        K4811InteropTest.Endpoint endpoint = new K4811InteropTest.Endpoint();
        NwdRadioApi radio = api(endpoint);
        radio.onLocDxEvent();
        assertTrue(radio.IsDxLocal());
        radio.onLocDxEvent();
        assertFalse(radio.IsDxLocal());
        assertEquals(2L, endpoint.count(8));
        assertEquals(0L, endpoint.count(1));
        assertSources(endpoint);
    }

    @Test public void externalStationAndBandChangesArePolledWithoutReactiveTuning() throws Exception {
        K4811InteropTest.Endpoint endpoint = new K4811InteropTest.Endpoint();
        NwdRadioApi radio = api(endpoint);
        List<RadioServiceClient.RadioState> states = new CopyOnWriteArrayList<>();
        List<String> errors = new CopyOnWriteArrayList<>();
        RadioServiceClient client = new RadioServiceClient(
                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                new RadioServiceClient.Listener() {
                    @Override public void onConnectionChanged(boolean connected, String message) { }
                    @Override public void onRadioError(String message) { errors.add(message); }
                    @Override public void onStateChanged(RadioServiceClient.RadioState state) { states.add(state); }
                });
        Field service = RadioServiceClient.class.getDeclaredField("service");
        service.setAccessible(true);
        Method poll = RadioServiceClient.class.getDeclaredMethod("pollNow");
        poll.setAccessible(true);
        try {
            service.set(client, radio);
            endpoint.calls.clear();
            // Simulate readback changes made by the native radio, including an AM bank.
            // This does not claim exclusive ownership of a physical steering button.
            int[][] stations = {{0, 9810, 0, 98100}, {0, 10170, 0, 101700},
                    {5, 999, 3, 999}, {1, 10210, 1, 102100}};
            for (int i = 0; i < stations.length; i++) {
                endpoint.band = stations[i][0];
                endpoint.frequency = stations[i][1];
                poll.invoke(client);
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                assertTrue(errors.isEmpty());
                assertEquals(i + 1, states.size());
                assertEquals(stations[i][2], states.get(i).band);
                assertEquals(stations[i][3], states.get(i).frequency);
                assertNoTunerWrites(endpoint);
                assertSources(endpoint);
            }
            poll.invoke(client);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals("Unchanged readback remains deduplicated", stations.length, states.size());
            assertTrue(errors.isEmpty());
            assertNoTunerWrites(endpoint);
            assertSources(endpoint);
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(client::close);
        }
    }

    @Test public void selectedG5CapabilitiesRequireLiveEndpointAndNeverOfferStationSeek() throws Exception {
        Field latest = RadioApiFactory.class.getDeclaredField("latest");
        Field resolved = RadioApiFactory.class.getDeclaredField("resolvedNwd");
        latest.setAccessible(true);
        resolved.setAccessible(true);
        Object previousLatest = latest.get(null);
        Object previousResolved = resolved.get(null);
        K4811InteropTest.Endpoint endpoint = new K4811InteropTest.Endpoint();
        try {
            latest.set(null, new RadioApiFactory.Detection(RadioBackendProfile.NWD_G5_242,
                    "2.4.2", RadioBackendProfile.NWD_G5_RADIO_SHA256, ""));
            resolved.set(null, null);
            assertFalse(RadioApiFactory.selectedSupportsStationSeek());
            assertFalse(RadioApiFactory.selectedSupportsScanning());
            assertFalse(RadioApiFactory.selectedSupportsLocalMode());
            NwdRadioApi radio = api(endpoint);
            resolved.set(null, radio);
            assertFalse(RadioApiFactory.selectedSupportsStationSeek());
            assertFalse(RadioApiFactory.selectedSupportsScanning());
            assertTrue(RadioApiFactory.selectedSupportsLocalMode());
            assertFalse(RadioApiFactory.supportsScanning(radio));
            assertTrue(RadioApiFactory.supportsLocalMode(radio));
            resolved.set(null, null);
            assertFalse(RadioApiFactory.selectedSupportsLocalMode());
            assertNoTunerWrites(endpoint);
            assertSources(endpoint);
        } finally {
            resolved.set(null, previousResolved);
            latest.set(null, previousLatest);
        }
    }

    @Test public void rawMediaInputRemainsIsolatedForG5BeforeBinding() throws Exception {
        Field latest = RadioApiFactory.class.getDeclaredField("latest");
        latest.setAccessible(true);
        Object previous = latest.get(null);
        try {
            latest.set(null, new RadioApiFactory.Detection(RadioBackendProfile.NWD_G5_242,
                    "2.4.2", RadioBackendProfile.NWD_G5_RADIO_SHA256, ""));
            assertTrue(RadioPlaybackService.shouldIgnoreRawMediaKeys());
            ContextWrapper noServiceStart = new ContextWrapper(null) {
                @Override public ComponentName startForegroundService(Intent intent) {
                    throw new AssertionError("Raw NWD input must not start a competing playback action");
                }
            };
            for (int key : new int[]{KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                    KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE}) {
                KeyEvent event = new KeyEvent(KeyEvent.ACTION_DOWN, key);
                assertFalse(RadioMediaButtonReceiver.dispatch(noServiceStart, event));
            }
        } finally {
            latest.set(null, previous);
        }
    }
}
