package fi.radioplus.app;

import android.content.ComponentName;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Handler;
import android.os.SystemClock;
import android.util.SparseBooleanArray;
import android.view.KeyEvent;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Raw-input isolation only: no service lifecycle, vendor code or device settings are used. */
public final class NwdMediaIsolationTest {
    private RadioPlaybackService service;
    private NwdRadioInteropTest.Endpoint endpoint;
    private Object previousDetection;
    private Object previousRunning;
    private boolean previousVisible;

    @Before public void setUp() throws Exception {
        previousDetection = read(RadioApiFactory.class, "latest", null);
        previousRunning = read(RadioPlaybackService.class, "runningInstance", null);
        previousVisible = (Boolean) read(SteeringKeyService.class, "radioVisible", null);
        assertNull("Stop Radio+ before instrumentation", previousRunning);
        endpoint = new NwdRadioInteropTest.Endpoint();
        NwdRadioApi api = endpoint.api();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            service = new RadioPlaybackService();
            write(RadioPlaybackService.class, "runningInstance", null, service);
            write(RadioPlaybackService.class, "radio", service, api);
            write(RadioPlaybackService.class, "playbackRequested", service, true);
            write(RadioApiFactory.class, "latest", null,
                    new RadioApiFactory.Detection(RadioBackendProfile.NWD_222, "2.2.2", "", ""));
        });
        endpoint.calls.clear();
    }

    @After public void tearDown() throws Exception {
        if (service != null) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    ((Handler) read(RadioPlaybackService.class, "mainHandler", service))
                            .removeCallbacksAndMessages(null));
            ((ExecutorService) read(RadioPlaybackService.class, "executor", service)).shutdownNow();
        }
        write(RadioPlaybackService.class, "runningInstance", null, previousRunning);
        write(RadioApiFactory.class, "latest", null, previousDetection);
        SteeringKeyService.setRadioVisible(previousVisible);
    }

    @Test public void kernelSourceSelectionStopDoesNotPauseStartup() {
        deliverServicePress(KeyEvent.KEYCODE_MEDIA_STOP);
        assertEquals(true, read(RadioPlaybackService.class, "playbackRequested", service));
        assertEquals(0L, read(RadioPlaybackService.class, "playbackEpoch", service));
        assertTrue(endpoint.calls.isEmpty());
        assertTrue(endpoint.audio.sent.isEmpty());
    }

    @Test public void rawSkipAliasesAndMuteDoNotControlNwd() {
        for (int key : new int[]{KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_VOLUME_MUTE,
                274, 275, 272, 273 /* OEM tuner/skip aliases */}) {
            deliverServicePress(key);
        }
        assertEquals(true, read(RadioPlaybackService.class, "playbackRequested", service));
        assertNull(read(RadioPlaybackService.class, "pendingRadioCommand", service));
        assertTrue(endpoint.calls.isEmpty());
        assertTrue(endpoint.audio.sent.isEmpty());
    }

    @Test public void nwdDetectionClearsAnyPendingShortPressDecision() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            MediaKeyPressTracker tracker = (MediaKeyPressTracker)
                    read(RadioPlaybackService.class, "mediaKeyPressTracker", service);
            long now = SystemClock.elapsedRealtime();
            tracker.onDown(KeyEvent.KEYCODE_MEDIA_NEXT, false, 0, now, now);
            assertTrue(tracker.nextFallbackDelay(now, 600) >= 0);
        });
        deliverServicePress(KeyEvent.KEYCODE_MEDIA_STOP);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            MediaKeyPressTracker tracker = (MediaKeyPressTracker)
                    read(RadioPlaybackService.class, "mediaKeyPressTracker", service);
            assertEquals(-1L, tracker.nextFallbackDelay(SystemClock.elapsedRealtime(), 600));
            ((Runnable) read(RadioPlaybackService.class, "pendingShortMediaTask", service)).run();
        });
        assertTrue(endpoint.calls.isEmpty());
    }

    @Test public void receiverAndAccessibilityDoNotForwardNwdRawKeys() {
        ContextWrapper noServiceStart = new ContextWrapper(null) {
            @Override public ComponentName startForegroundService(Intent intent) {
                throw new AssertionError("NWD raw input must not start a playback service");
            }
        };
        KeyEvent event = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT);
        assertFalse(RadioMediaButtonReceiver.dispatch(noServiceStart, event));
        new RadioMediaButtonReceiver().onReceive(noServiceStart,
                new Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, event));
        SteeringKeyService.setRadioVisible(true);
        SteeringKeyService accessibility = new SteeringKeyService();
        SparseBooleanArray consumed = (SparseBooleanArray)
                read(SteeringKeyService.class, "consumed", accessibility);
        consumed.put(KeyEvent.KEYCODE_MEDIA_NEXT, true);
        assertFalse(accessibility.onKeyEvent(event));
        assertEquals(0, consumed.size());
        assertTrue(endpoint.calls.isEmpty());
    }

    @Test public void unverifiedNwdPackageIsAlsoIsolatedBeforeBinding() {
        write(RadioPlaybackService.class, "radio", service, null);
        write(RadioApiFactory.class, "latest", null, new RadioApiFactory.Detection(
                RadioBackendProfile.UNKNOWN, "com.nwd.radio.service", "unknown", "", "Unverified APK"));
        assertTrue(RadioPlaybackService.shouldIgnoreRawMediaKeys());
        deliverServicePress(KeyEvent.KEYCODE_MEDIA_STOP);
        assertEquals(true, read(RadioPlaybackService.class, "playbackRequested", service));
        assertTrue(endpoint.calls.isEmpty());
    }

    @Test public void busyRejectionKeepsExistingAudioAndDoesNotRebindOrReplay() throws Exception {
        endpoint.audio.source = 4;
        write(RadioPlaybackService.class, "pendingWidgetStation", service,
                new FavoriteStation(0, 101700, "Pending"));
        Object api = read(RadioPlaybackService.class, "radio", service);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                enqueueRejectedCommand("NWD tuner is busy"));
        drainCommandsAndMain();
        assertEquals(true, read(RadioPlaybackService.class, "playbackRequested", service));
        assertRejectedWithoutRebindOrReplay(api);
    }

    @Test public void sourceAwayRejectionOnlyClearsOurPlayingState() throws Exception {
        endpoint.audio.source = 7;
        write(RadioPlaybackService.class, "pendingWidgetStation", service,
                new FavoriteStation(0, 101700, "Pending"));
        Object api = read(RadioPlaybackService.class, "radio", service);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                enqueueRejectedCommand("NWD source is no longer available"));
        drainCommandsAndMain();
        assertEquals(false, read(RadioPlaybackService.class, "playbackRequested", service));
        assertEquals(false, read(RadioPlaybackService.class, "oemRouteActive", service));
        assertRejectedWithoutRebindOrReplay(api);
    }

    @Test public void deniedMcuAudioRouteDoesNotClaimPlaybackOrStartRecovery() throws Exception {
        endpoint.type = 0;
        endpoint.audio.source = 7;
        NwdAudioRouting routing = new NwdAudioRouting(endpoint.audio, () -> 100L, false);
        NwdRadioApi mcu = new NwdRadioApi(RadioBackendProfile.NWD_230, endpoint, routing);
        write(RadioPlaybackService.class, "radio", service, mcu);
        endpoint.calls.clear();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                invoke("requestPlayback", new Class<?>[]{boolean.class}, true));
        drainCommandsAndMain();
        assertEquals(false, read(RadioPlaybackService.class, "playbackRequested", service));
        assertEquals(false, read(RadioPlaybackService.class, "oemRouteActive", service));
        assertEquals(-1L, read(RadioPlaybackService.class, "activatedPlaybackEpoch", service));
        assertRejectedWithoutRebindOrReplay(mcu);
    }

    @Test public void mcuRejectedPendingStartIsCanceledBeforeAdvertisingPause() throws Exception {
        NwdRadioApi mcu = installMcuApi(new NwdAudioRouting(endpoint.audio, () -> endpoint.audio.now, false));
        assertTrue(mcu.requestPlayAudio());
        endpoint.audio.now += 10000L;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                enqueueRejectedCommand("Source acknowledgement did not arrive"));
        drainCommandsAndMain();
        assertMcuPendingStartCanceled(mcu);
    }

    @Test public void mcuOwnershipYieldCancelsPendingStartBeforeAdvertisingPause() throws Exception {
        NwdRadioApi mcu = installMcuApi(new NwdAudioRouting(endpoint.audio, () -> endpoint.audio.now, false));
        assertTrue(mcu.requestPlayAudio());
        endpoint.audio.now += 10000L;
        observeExternalOwnershipTwice();
        assertMcuPendingStartCanceled(mcu);
    }

    @Test public void mcuExternalSourceHandoffIsNotSwitchedDuringRejection() throws Exception {
        NwdRadioApi mcu = installMcuApi(new NwdAudioRouting(endpoint.audio, () -> endpoint.audio.now, false));
        assertTrue(mcu.requestPlayAudio());
        endpoint.audio.source = 7;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                enqueueRejectedCommand("Source handed off"));
        drainCommandsAndMain();
        assertEquals(false, read(RadioPlaybackService.class, "playbackRequested", service));
        assertEquals("No source=0 must follow a handoff to source=7", 1, endpoint.audio.sent.size());
        assertFalse(mcu.hasPendingAudioStart());
        assertTrue(endpoint.calls.isEmpty());
    }

    @Test public void mcuOwnershipYieldWithoutPendingStartDoesNotSwitchSource() throws Exception {
        installMcuApi(new NwdAudioRouting(endpoint.audio, () -> endpoint.audio.now, false));
        observeExternalOwnershipTwice();
        assertEquals(false, read(RadioPlaybackService.class, "playbackRequested", service));
        assertTrue(endpoint.audio.sent.isEmpty());
        assertTrue(endpoint.calls.isEmpty());
    }

    @Test public void mcuUnknownPendingStartDoesNotAdvertisePause() throws Exception {
        NwdRadioApi mcu = installMcuApi(new NwdAudioRouting(endpoint.audio, () -> endpoint.audio.now, false));
        assertTrue(mcu.requestPlayAudio());
        endpoint.audio.source = -1;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                enqueueRejectedCommand("Source is temporarily unknown"));
        drainCommandsAndMain();
        assertEquals(true, read(RadioPlaybackService.class, "playbackRequested", service));
        assertTrue(mcu.hasPendingAudioStart());
        assertEquals("Unknown ownership must not trigger a blind source switch", 1, endpoint.audio.sent.size());
        assertTrue(endpoint.calls.isEmpty());
    }

    @Test public void staleMcuRejectionDoesNotCancelANewerStart() throws Exception {
        NwdRadioApi mcu = installMcuApi(new NwdAudioRouting(endpoint.audio, () -> endpoint.audio.now, false));
        assertTrue(mcu.requestPlayAudio());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            invoke("setPlaybackRequested", new Class<?>[]{boolean.class}, true);
            invoke("settleRejectedNwdCommand", new Class<?>[]{com.hcn.autoradio.IRadioServiceAPI.class,
                    long.class, long.class}, mcu, 0L, 0L);
        });
        drainCommandsAndMain();
        assertEquals(true, read(RadioPlaybackService.class, "playbackRequested", service));
        assertTrue(mcu.hasPendingAudioStart());
        assertEquals(1, endpoint.audio.sent.size());
        assertTrue(endpoint.calls.isEmpty());
    }

    @Test public void staleSourceSnapshotDoesNotPauseAcknowledgedMcuPlayback() throws Exception {
        AtomicInteger observations = new AtomicInteger();
        NwdAudioRouting.Transport changingSource = new NwdAudioRouting.Transport() {
            @Override public int source() { return observations.getAndIncrement() == 0 ? 0 : 4; }
            @Override public void send(Intent intent) { endpoint.audio.sent.add(intent); }
        };
        installMcuApi(new NwdAudioRouting(changingSource, () -> 100L, false));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                enqueueRejectedCommand("Stale external-source snapshot"));
        drainCommandsAndMain();
        assertEquals(true, read(RadioPlaybackService.class, "playbackRequested", service));
        assertTrue("Refresh source ownership when no owned cancellation was sent", observations.get() >= 2);
        assertTrue(endpoint.audio.sent.isEmpty());
        assertTrue(endpoint.calls.isEmpty());
    }

    @Test public void explicitMcuPauseWaitsForOwnedCancellationAtKnownZeroOrLateFour() throws Exception {
        for (int resolvedSource : new int[]{0, 4}) {
            NwdRadioApi mcu = beginUnknownMcuPause();
            endpoint.audio.source = resolvedSource;
            // Another client may observe late source=4 before the service tick.
            // That observation must not erase an already requested cancellation.
            mcu.readHealth();
            assertTrue(mcu.hasPendingAudioStart());
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    invoke("resolvePendingNwdPause", new Class<?>[]{}));
            drainCommandsAndMain();
            assertNull(read(RadioPlaybackService.class, "pendingNwdPause", service));
            assertEquals(android.media.session.PlaybackState.STATE_PAUSED,
                    invoke("advertisedPlaybackState", new Class<?>[]{}));
            assertMcuPendingStartCanceled(mcu);
        }
    }

    @Test public void explicitMcuPauseMarksOwnershipBeforeItsFirstWorkerRuns() throws Exception {
        NwdRadioApi mcu = installMcuApi(new NwdAudioRouting(endpoint.audio, () -> endpoint.audio.now, false));
        assertTrue(mcu.requestPlayAudio());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ((ExecutorService) read(RadioPlaybackService.class, "executor", service)).submit(() -> {
            entered.countDown();
            awaitLatch(release);
        });
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    invoke("pausePlayback", new Class<?>[]{}));
            endpoint.audio.source = 4;
            mcu.readHealth();
            assertTrue("A health poll before the cancellation worker must retain its owned request",
                    mcu.hasPendingAudioStart());
            assertEquals("Marking pause intent is local and performs no source IPC", 1, endpoint.audio.sent.size());
        } finally { release.countDown(); }
        drainCommandsAndMain();
        assertMcuPendingStartCanceled(mcu);
        assertNull(read(RadioPlaybackService.class, "pendingNwdPause", service));
        assertEquals(android.media.session.PlaybackState.STATE_PAUSED,
                invoke("advertisedPlaybackState", new Class<?>[]{}));
    }

    @Test public void explicitMcuPauseAfterHandoffDoesNotSwitchSourceSeven() throws Exception {
        NwdRadioApi mcu = beginUnknownMcuPause();
        endpoint.audio.source = 7;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                invoke("resolvePendingNwdPause", new Class<?>[]{}));
        drainCommandsAndMain();
        assertFalse(mcu.hasPendingAudioStart());
        assertNull(read(RadioPlaybackService.class, "pendingNwdPause", service));
        assertEquals(android.media.session.PlaybackState.STATE_PAUSED,
                invoke("advertisedPlaybackState", new Class<?>[]{}));
        assertEquals(1, endpoint.audio.sent.size());
        assertTrue(endpoint.calls.isEmpty());
    }

    @Test public void explicitMcuPauseWithoutOwnedStartDoesNotCreateDeferredCancellation() throws Exception {
        installMcuApi(new NwdAudioRouting(endpoint.audio, () -> endpoint.audio.now, false));
        endpoint.audio.source = -1;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                invoke("pausePlayback", new Class<?>[]{}));
        drainCommandsAndMain();
        assertNull(read(RadioPlaybackService.class, "pendingNwdPause", service));
        assertEquals(android.media.session.PlaybackState.STATE_PAUSED,
                invoke("advertisedPlaybackState", new Class<?>[]{}));
        assertTrue(endpoint.audio.sent.isEmpty());
        assertTrue(endpoint.calls.isEmpty());
    }

    @Test public void explicitPlaySupersedesQueuedMcuPauseCancellation() throws Exception {
        beginUnknownMcuPause();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ((ExecutorService) read(RadioPlaybackService.class, "executor", service)).submit(() -> {
            entered.countDown();
            awaitLatch(release);
        });
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        endpoint.audio.source = 0;
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                invoke("resolvePendingNwdPause", new Class<?>[]{});
                invoke("requestPlayback", new Class<?>[]{boolean.class}, true);
            });
        } finally { release.countDown(); }
        drainCommandsAndMain();
        assertNull(read(RadioPlaybackService.class, "pendingNwdPause", service));
        assertEquals(true, read(RadioPlaybackService.class, "playbackRequested", service));
        assertEquals(android.media.session.PlaybackState.STATE_PLAYING,
                invoke("advertisedPlaybackState", new Class<?>[]{}));
        for (Intent sent : endpoint.audio.sent) {
            assertEquals("A superseded Pause must not send source=0", 4,
                    sent.getByteExtra("extra_source_id", (byte) -1));
        }
        assertTrue(endpoint.calls.isEmpty());
    }

    @Test public void deferredMcuPauseDoesNotActAcrossConnectionGeneration() throws Exception {
        beginUnknownMcuPause();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(this::simulateLossAndSameApiRebind);
        endpoint.audio.source = 4;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                invoke("resolvePendingNwdPause", new Class<?>[]{}));
        drainCommandsAndMain();
        assertEquals("The old pause generation must not send a late source switch", 1, endpoint.audio.sent.size());
        assertEquals(android.media.session.PlaybackState.STATE_BUFFERING,
                invoke("advertisedPlaybackState", new Class<?>[]{}));
        assertTrue(endpoint.calls.isEmpty());
    }

    private NwdRadioApi beginUnknownMcuPause() throws Exception {
        endpoint.audio.source = 0;
        endpoint.audio.sent.clear();
        NwdRadioApi mcu = installMcuApi(new NwdAudioRouting(endpoint.audio, () -> endpoint.audio.now, false));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                invoke("setPlaybackRequested", new Class<?>[]{boolean.class}, true));
        assertTrue(mcu.requestPlayAudio());
        endpoint.audio.source = -1;
        endpoint.audio.now += 30000L;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                invoke("pausePlayback", new Class<?>[]{}));
        drainCommandsAndMain();
        assertEquals(false, read(RadioPlaybackService.class, "playbackRequested", service));
        assertNotNull(read(RadioPlaybackService.class, "pendingNwdPause", service));
        assertTrue(mcu.hasPendingAudioStart());
        assertEquals(android.media.session.PlaybackState.STATE_BUFFERING,
                invoke("advertisedPlaybackState", new Class<?>[]{}));
        assertEquals("Unknown source cannot safely be counterqueued", 1, endpoint.audio.sent.size());
        return mcu;
    }

    private NwdRadioApi installMcuApi(NwdAudioRouting routing) throws Exception {
        endpoint.type = 0;
        NwdRadioApi mcu = new NwdRadioApi(RadioBackendProfile.NWD_230, endpoint, routing);
        write(RadioPlaybackService.class, "radio", service, mcu);
        endpoint.calls.clear();
        return mcu;
    }

    private void observeExternalOwnershipTwice() throws Exception {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                ((PlaybackOwnershipPolicy) read(RadioPlaybackService.class, "playbackOwnership", service))
                        .reset(SystemClock.elapsedRealtime() - 4000L));
        for (int observation = 0; observation < 2; observation++) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    invoke("reconcilePlaybackOwnership", new Class<?>[]{}));
            drainCommandsAndMain();
        }
    }

    private void assertMcuPendingStartCanceled(NwdRadioApi mcu) {
        assertEquals(false, read(RadioPlaybackService.class, "playbackRequested", service));
        assertFalse(mcu.hasPendingAudioStart());
        assertEquals(2, endpoint.audio.sent.size());
        assertEquals(4, endpoint.audio.sent.get(0).getByteExtra("extra_source_id", (byte) -1));
        assertEquals("Counterqueue source=0 after our unresolved source=4", 0,
                endpoint.audio.sent.get(1).getByteExtra("extra_source_id", (byte) -1));
        assertEquals(NwdAudioRouting.KERNEL_PACKAGE, endpoint.audio.sent.get(1).getPackage());
        assertEquals(NwdAudioRouting.CHANGE_SOURCE, endpoint.audio.sent.get(1).getAction());
        assertEquals(0, read(RadioPlaybackService.class, "backendGeneration", service));
        assertTrue(endpoint.calls.isEmpty());
    }

    @Test public void postAcknowledgementSourceHandoffIsNotReclaimedByTuneCompletion() throws Exception {
        endpoint.type = 0;
        NwdAudioRouting routing = new NwdAudioRouting(endpoint.audio, () -> 100L, false);
        NwdRadioApi mcu = new NwdRadioApi(RadioBackendProfile.NWD_230, endpoint, routing);
        endpoint.calls.clear();
        for (int source : new int[]{4, 0, 7, -1}) {
            // The adapter has returned from frequency acknowledgement. Another
            // player can own the source before the service finishes that tune.
            endpoint.audio.source = source;
            assertEquals(source == 4, invoke("finishConfirmedTuneRoute",
                    new Class<?>[]{com.hcn.autoradio.IRadioServiceAPI.class}, mcu));
            assertTrue("Completing a tune may only observe NWD source ownership", endpoint.audio.sent.isEmpty());
            assertTrue(endpoint.calls.isEmpty());
        }
    }

    @Test public void staleRejectionDoesNotEraseANewerRequestedStation() throws Exception {
        endpoint.audio.source = 7;
        FavoriteStation newer = new FavoriteStation(0, 102100, "Newer request");
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            enqueueRejectedCommand("Previous operation unresolved");
            invoke("setPlaybackRequested", new Class<?>[]{boolean.class}, true);
            write(RadioPlaybackService.class, "pendingWidgetStation", service, newer);
        });
        drainCommandsAndMain();
        assertEquals(true, read(RadioPlaybackService.class, "playbackRequested", service));
        assertSame(newer, read(RadioPlaybackService.class, "pendingWidgetStation", service));
        assertEquals(0, read(RadioPlaybackService.class, "backendGeneration", service));
        assertTrue(endpoint.audio.sent.isEmpty());
        assertTrue(endpoint.calls.isEmpty());
    }

    @Test public void invalidTargetDoesNotRebindOrAcquireAudio() throws Exception {
        endpoint.gridCount = 1;
        endpoint.audio.source = 7;
        FavoriteStation invalid = new FavoriteStation(3, 999, "Unsupported AM");
        Object api = read(RadioPlaybackService.class, "radio", service);
        write(RadioPlaybackService.class, "pendingWidgetStation", service, invalid);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> assertEquals(false,
                invoke("validateStationTarget", new Class<?>[]{com.hcn.autoradio.IRadioServiceAPI.class,
                        FavoriteStation.class, long.class, long.class, boolean.class},
                        api, invalid, 0L, 0L, false)));
        drainCommandsAndMain();
        assertEquals(false, read(RadioPlaybackService.class, "playbackRequested", service));
        assertEquals(0, read(RadioPlaybackService.class, "backendGeneration", service));
        assertSame(api, read(RadioPlaybackService.class, "radio", service));
        assertNull(read(RadioPlaybackService.class, "pendingWidgetStation", service));
        assertTrue(endpoint.audio.sent.isEmpty());
        assertFalse(endpoint.calls.contains(1));
        assertFalse(endpoint.calls.contains(5));
    }

    @Test public void sameApiRebindDoesNotReviveQueuedPreLossCommand() throws Exception {
        CountDownLatch workerBlocked = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        ((ExecutorService) read(RadioPlaybackService.class, "executor", service)).submit(() -> {
            workerBlocked.countDown();
            awaitLatch(releaseWorker);
        });
        assertTrue(workerBlocked.await(3, TimeUnit.SECONDS));
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                enqueueCommand(executions::incrementAndGet);
                seedPendingReconnectCommand();
                simulateLossAndSameApiRebind();
            });
        } finally { releaseWorker.countDown(); }
        drainCommandsAndMain();
        assertEquals("A queued pre-loss command must not execute against the reused adapter", 0, executions.get());
        assertEquals(1L, read(RadioPlaybackService.class, "playbackEpoch", service));
        assertNull(read(RadioPlaybackService.class, "pendingWidgetStation", service));
        assertNull(read(RadioPlaybackService.class, "pendingRadioCommand", service));
        assertEquals("", read(RadioPlaybackService.class, "pendingRadioCommandName", service));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> enqueueCommand(executions::incrementAndGet));
        drainCommandsAndMain();
        assertEquals("A genuinely new command remains usable after reconnect", 1, executions.get());
        assertTrue(endpoint.audio.sent.isEmpty());
    }

    @Test public void sameApiRebindInvalidatesInFlightTuneContinuation() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean continued = new AtomicBoolean();
        AtomicBoolean checkedActivation = new AtomicBoolean();
        AtomicLong capturedEpoch = new AtomicLong(-1L);
        Object api = read(RadioPlaybackService.class, "radio", service);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> enqueueEpochCommand(epoch -> {
            capturedEpoch.set(epoch);
            entered.countDown();
            awaitLatch(release);
            continued.set((Boolean) invoke("isCurrentPlayback",
                    new Class<?>[]{long.class, com.hcn.autoradio.IRadioServiceAPI.class}, epoch, api));
            // The action must retain its worker's original epoch, not adopt
            // the replacement connection's epoch just before source takeover.
            invoke("activateOemPlayback", new Class<?>[]{com.hcn.autoradio.IRadioServiceAPI.class,
                    boolean.class, long.class}, api, true, epoch);
            checkedActivation.set(true);
            throw new android.os.RemoteException("Old command failed after reconnect");
        }));
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(this::simulateLossAndSameApiRebind);
        } finally { release.countDown(); }
        drainCommandsAndMain();
        assertEquals("The worker passes the original scheduling epoch to its action", 0L, capturedEpoch.get());
        assertTrue("The stale continuation reached the guarded activation path", checkedActivation.get());
        assertFalse("An in-flight NWD tune must not continue after the connection generation changes", continued.get());
        assertEquals("A delayed old failure must not tear down the new connection", 1,
                read(RadioPlaybackService.class, "backendGeneration", service));
        assertSame(api, read(RadioPlaybackService.class, "radio", service));
        assertTrue(endpoint.calls.isEmpty());
        assertTrue(endpoint.audio.sent.isEmpty());
    }

    private void seedPendingReconnectCommand() {
        write(RadioPlaybackService.class, "pendingWidgetStation", service,
                new FavoriteStation(0, 101700, "Pre-loss target"));
        try {
            Class<?> commandType = Class.forName("fi.radioplus.app.RadioPlaybackService$RadioCommand");
            Object command = Proxy.newProxyInstance(commandType.getClassLoader(), new Class<?>[]{commandType},
                    (proxy, method, arguments) -> { throw new AssertionError("Pre-loss command replayed"); });
            write(RadioPlaybackService.class, "pendingRadioCommand", service, command);
            write(RadioPlaybackService.class, "pendingRadioCommandName", service, "Pre-loss command");
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private void simulateLossAndSameApiRebind() {
        Object api = read(RadioPlaybackService.class, "radio", service);
        invoke("handleOemConnectionLoss", new Class<?>[]{String.class}, "synthetic disconnect");
        // Restore the same cache object before the deferred recovery callback;
        // no service lifecycle or actual OEM binding is performed in this test.
        write(RadioPlaybackService.class, "radio", service, api);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try { assertTrue("Timed out waiting for synthetic worker barrier", latch.await(3, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }

    private void assertRejectedWithoutRebindOrReplay(Object api) throws Exception {
        assertSame(api, read(RadioPlaybackService.class, "radio", service));
        assertEquals(0, read(RadioPlaybackService.class, "backendGeneration", service));
        assertNull(read(RadioPlaybackService.class, "pendingWidgetStation", service));
        assertNull(read(RadioPlaybackService.class, "pendingRadioCommand", service));
        assertEquals("", read(RadioPlaybackService.class, "pendingRadioCommandName", service));
        drainCommandsAndMain();
        assertTrue(endpoint.calls.isEmpty());
        assertTrue(endpoint.audio.sent.isEmpty());
    }

    private void enqueueRejectedCommand(String reason) {
        enqueueCommand(() -> { throw new NwdRadioApi.CommandRejectedException(reason); });
    }

    private interface TestCommand { void run() throws Exception; }
    private interface EpochTestCommand { void run(long epoch) throws Exception; }

    private void enqueueCommand(TestCommand action) {
        enqueueEpochCommand(epoch -> action.run());
    }

    private void enqueueEpochCommand(EpochTestCommand action) {
        try {
            Class<?> commandType = Class.forName("fi.radioplus.app.RadioPlaybackService$RadioCommand");
            Object command = Proxy.newProxyInstance(commandType.getClassLoader(), new Class<?>[]{commandType},
                    (proxy, method, arguments) -> {
                        if ("run".equals(method.getName())) action.run((Long) arguments[1]);
                        return null;
                    });
            invoke("executeRadioCommand", new Class<?>[]{String.class, commandType}, "synthetic rejection", command);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private void drainCommandsAndMain() throws Exception {
        ((ExecutorService) read(RadioPlaybackService.class, "executor", service))
                .submit(() -> {}).get(3, TimeUnit.SECONDS);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private Object invoke(String name, Class<?>[] parameters, Object... arguments) {
        try {
            Method method = RadioPlaybackService.class.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method.invoke(service, arguments);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private void deliverServicePress(int key) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            long downTime = SystemClock.uptimeMillis();
            for (int action : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
                KeyEvent event = new KeyEvent(downTime, downTime + action, action, key, 0);
                service.onStartCommand(new Intent(Intent.ACTION_MEDIA_BUTTON)
                        .putExtra(Intent.EXTRA_KEY_EVENT, event), 0, 1);
            }
        });
    }

    private static Object read(Class<?> type, String name, Object target) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void write(Class<?> type, String name, Object target, Object value) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
}
