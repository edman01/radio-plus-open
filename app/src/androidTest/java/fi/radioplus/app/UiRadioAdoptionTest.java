package fi.radioplus.app;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.test.platform.app.InstrumentationRegistry;

import com.hcn.autoradio.IRadioServiceAPI;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeFalse;

/**
 * Real UI-open service intents, with synthetic HCN AIDL/framework dependencies.
 * No prior Play primes the adoption tests: the OEM radio is already audible,
 * while Radio+'s new service has never activated its route or focus.
 * This fixture is emulator-only and neither changes preferences nor runs OEM code.
 */
public final class UiRadioAdoptionTest {
    private static final String OPEN_UI = "fi.radioplus.app.action.OPEN_UI";
    private static final String HCN_PACKAGE = "com.hcn.autoradio";
    private static final long TIMEOUT_MS = 5_000L;
    private static final long OBSERVE_MS = 1_650L;
    private static final FavoriteStation STATION = new FavoriteStation(0, 98_100, "");

    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Source source = new Source();
    private final Player player = new Player();
    private final FakeTuner tuner = new FakeTuner();
    private Context context;
    private AudioManager audio;
    private MediaBrowser browser;
    private MediaController controller;
    private RadioPlaybackService service;
    private boolean ownsService;
    private boolean observerRegistered;
    private volatile boolean sawPcm;

    private final AudioManager.AudioPlaybackCallback playbackObserver =
            new AudioManager.AudioPlaybackCallback() {
                @Override public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                    if (!configs.isEmpty()) sawPcm = true;
                }
            };

    @Before public void setUp() throws Exception {
        assumeFalse("Never inject synthetic tuner state on a Junsun head unit", hasVendorFramework());
        context = instrumentation.getTargetContext();
        assertNull("Stop the test app before UI-adoption instrumentation",
                field(RadioPlaybackService.class, "runningInstance", null));
        audio = context.getSystemService(AudioManager.class);
        assertNotNull(audio);
        await(() -> audio.getActivePlaybackConfigurations().isEmpty(),
                "The emulator must have no unrelated PCM playback");
        connectPassiveBrowser();
        audio.registerAudioPlaybackCallback(playbackObserver, new Handler(Looper.getMainLooper()));
        observerRegistered = true;
        assertFalse(RadioPlaybackService.isPlaybackRequested());
        assertEquals(Boolean.FALSE, field(RadioPlaybackService.class, "oemRouteActive", service));
        assertEquals(-1L, (long) (Long) field(RadioPlaybackService.class, "activatedPlaybackEpoch", service));
        assertNoAudioWrites();
    }

    @After public void tearDown() throws Exception {
        try {
            if (ownsService) {
                instrumentation.runOnMainSync(() -> {
                    if (browser != null) browser.disconnect();
                    context.stopService(new Intent(context, RadioPlaybackService.class));
                });
                await(() -> field(RadioPlaybackService.class, "runningInstance", null) == null,
                        "UI-adoption test service did not stop");
                await(() -> audio.getActivePlaybackConfigurations().isEmpty(),
                        "A test routing pulse remained active after service destruction");
            }
        } finally {
            if (observerRegistered) audio.unregisterAudioPlaybackCallback(playbackObserver);
        }
    }

    @Test public void coldUiHelperStartsAnOpenIntentWithoutAnExplicitTakeover() throws Exception {
        List<Intent> starts = new CopyOnWriteArrayList<>();
        Context capture = new ContextWrapper(context) {
            @Override public ComponentName startForegroundService(Intent intent) {
                starts.add(new Intent(intent));
                return intent.getComponent();
            }
        };
        Field running = RadioPlaybackService.class.getDeclaredField("runningInstance");
        running.setAccessible(true);
        instrumentation.runOnMainSync(() -> {
            try {
                Object original = running.get(null);
                assertSame(service, original);
                try {
                    // Simulate only the helper's cold-service lookup. The
                    // capturing context must never launch a second service.
                    running.set(null, null);
                    RadioPlaybackService.ensureRunningForUi(capture);
                } finally {
                    running.set(null, original);
                }
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        });
        assertEquals(1, starts.size());
        assertEquals("Cold UI entry must not become an explicit TAKE_OVER", OPEN_UI, starts.get(0).getAction());
        assertEquals(new ComponentName(context, RadioPlaybackService.class), starts.get(0).getComponent());
        assertSame(service, field(RadioPlaybackService.class, "runningInstance", null));
        assertFalse(RadioPlaybackService.isPlaybackRequested());
        assertNoAudioWrites();
        assertFalse(sawPcm);
    }

    @Test public void userPauseCancelsQueuedUiAdoptionBeforeItCanResumePlayback() throws Exception {
        ExecutorService executor = (ExecutorService) field(RadioPlaybackService.class, "executor", service);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> blocker = executor.submit(() -> {
            entered.countDown();
            try {
                assertTrue("Test did not release the blocked radio executor",
                        release.await(TIMEOUT_MS * 3, TimeUnit.MILLISECONDS));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
        });
        long pausedClaim;
        try {
            assertTrue(entered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            openUi();
            await(RadioPlaybackService::isPlaybackRequested, "UI intent was not delivered before Pause");
            controller.getTransportControls().pause();
            await(() -> !RadioPlaybackService.isPlaybackRequested()
                            && controller.getPlaybackState().getState() == PlaybackState.STATE_PAUSED,
                    "User Pause was not delivered while UI activation was queued");
            pausedClaim = claimId();
            assertEquals(0, tuner.routes);
            assertEquals(0, tuner.focusRequests);
            assertEquals(0, tuner.tunes);
        } finally {
            // Also release on assertion failure, so teardown never waits on a
            // test-owned executor blocker.
            release.countDown();
        }
        blocker.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        drain();
        long until = SystemClock.elapsedRealtime() + OBSERVE_MS;
        do {
            assertFalse("An older UI command must never override the newer Pause",
                    RadioPlaybackService.isPlaybackRequested());
            assertEquals(0, tuner.routes);
            assertEquals(0, tuner.focusRequests);
            assertEquals(0, tuner.tunes);
            assertEquals(0, tuner.seeks);
            assertEquals(0, source.gainWrites);
            assertTrue("A cancelled UI command must not start a delayed PCM claim",
                    audio.getActivePlaybackConfigurations().isEmpty());
            SystemClock.sleep(30L);
        } while (SystemClock.elapsedRealtime() < until);
        drain();
        assertEquals("Only Pause's claim cancellation may change the generation", pausedClaim, claimId());
        assertNull(field(RadioPlaybackService.class, "pendingRoutingClaim", service));
        assertEquals(Boolean.FALSE, field(RadioPlaybackService.class, "oemRouteActive", service));
        assertEquals(-1L, (long) (Long) field(RadioPlaybackService.class, "activatedPlaybackEpoch", service));
        assertEquals(PlaybackState.STATE_PAUSED, controller.getPlaybackState().getState());
        assertTrue("Pause must still mute the OEM radio", player.muted);
        assertTrue("Pause's normal mute writes are intentional", player.normalWrites > 0);
        assertTrue("Pause's hardware mute writes are intentional", player.hardwareWrites > 0);
        assertEquals(MediaStationId.encode(STATION),
                controller.getMetadata().getString(MediaMetadata.METADATA_KEY_MEDIA_ID));
        assertFalse("No transient PCM claim may escape the polling assertions", sawPcm);
    }

    @Test public void coldUiOpenAdoptsAudibleHcnWithoutAnyAudioWrite() throws Exception {
        RadioPlaybackService.noteUiBackgrounded();
        long claimBefore = claimId();
        openUi();
        assertAdoptedWithoutAudioWrites(claimBefore);
        assertEquals(Boolean.FALSE, field(RadioPlaybackService.class, "uiTakeoverRequired", service));
    }

    @Test public void componentQualifiedHcnSourceIsAlsoAdoptedWithoutWrites() throws Exception {
        source.packageName = HCN_PACKAGE + "/.RadioActivity";
        long claimBefore = claimId();
        openUi();
        assertAdoptedWithoutAudioWrites(claimBefore);
    }

    @Test public void explicitPlayAfterAdoptionStillPerformsTheOwnershipHandoff() throws Exception {
        long claimBefore = claimId();
        openUi();
        assertAdoptedWithoutAudioWrites(claimBefore);
        controller.getTransportControls().play();
        assertExplicitFallback(claimBefore, 1);
    }

    @Test public void mutedOemRadioUsesNormalUiStartupInsteadOfAudibleAdoption() throws Exception {
        player.muted = true;
        long claimBefore = claimId();
        openUi();
        assertExplicitFallback(claimBefore, 1);
    }

    @Test public void anotherSourceUsesNormalUiTakeover() throws Exception {
        source.packageName = "fi.radioplus.synthetic.otherplayer";
        long claimBefore = claimId();
        openUi();
        assertExplicitFallback(claimBefore, 1);
    }

    @Test public void unknownSourceDoesNotPretendTheRadioIsAlreadyAudible() throws Exception {
        source.packageName = "";
        long claimBefore = claimId();
        openUi();
        assertExplicitFallback(claimBefore, 1);
    }

    @Test public void unknownMuteDoesNotPretendTheRadioIsAlreadyAudible() throws Exception {
        player.muteGetterAvailable = false;
        long claimBefore = claimId();
        openUi();
        assertExplicitFallback(claimBefore, 1);
    }

    @Test public void previouslyReleasedOemFocusMustBeReacquiredEvenIfHealthLooksAudible()
            throws Exception {
        instrumentation.runOnMainSync(() -> ((OemFocusSessionState) field(
                RadioPlaybackService.class, "oemFocusState", service)).markFocusReleasedByRadioPlus());
        long claimBefore = claimId();
        openUi();
        assertExplicitFallback(claimBefore, 0);
    }

    private void openUi() {
        // Exercise Android's actual onStartCommand delivery, not the private
        // request/activation method. The browser merely made injection safe.
        context.startForegroundService(new Intent(context, RadioPlaybackService.class).setAction(OPEN_UI));
    }

    private void assertAdoptedWithoutAudioWrites(long claimBefore) throws Exception {
        await(() -> RadioPlaybackService.isPlaybackRequested()
                        && Boolean.TRUE.equals(field(RadioPlaybackService.class, "oemRouteActive", service))
                        && field(RadioPlaybackService.class, "playbackEpoch", service).equals(
                                field(RadioPlaybackService.class, "activatedPlaybackEpoch", service)),
                "UI-open did not adopt the observed already-audible radio");
        long until = SystemClock.elapsedRealtime() + OBSERVE_MS;
        do {
            assertTrue("Adoption must not create even a temporary PCM routing player",
                    audio.getActivePlaybackConfigurations().isEmpty());
            assertNoAudioWrites();
            SystemClock.sleep(30L);
        } while (SystemClock.elapsedRealtime() < until);
        drain();
        assertNoAudioWrites();
        assertFalse("A PCM callback must not be hidden by checking only after release", sawPcm);
        assertEquals("Adoption must not schedule a delayed routing claim", claimBefore, claimId());
        assertNull(field(RadioPlaybackService.class, "pendingRoutingClaim", service));
        assertPlayingAtObservedStation();
    }

    private void assertNoAudioWrites() {
        assertEquals("Do not request the already-active analog route again", 0, tuner.routes);
        assertEquals("Do not reacquire the already-owned OEM focus", 0, tuner.focusRequests);
        assertEquals("Do not release the already-owned OEM focus", 0, tuner.focusReleases);
        assertEquals("UI adoption only observes the current station", 0, tuner.tunes);
        assertEquals("UI adoption never starts seeking", 0, tuner.seeks);
        assertEquals("Do not rewrite framework mute while adopting", 0, player.normalWrites);
        assertEquals("Do not rewrite hardware mute while adopting", 0, player.hardwareWrites);
        assertEquals("Do not rewrite shared FM input gain while adopting", 0, source.gainWrites);
    }

    private void assertExplicitFallback(long previousClaim, int expectedReleases) throws Exception {
        await(() -> tuner.routes >= 2 && claimId() > previousClaim
                        && "completed".equals(field(RadioPlaybackService.class, "routingPulseStatus", service)),
                "Explicit startup fallback did not finish its original focus/route/PCM handoff");
        drain();
        assertEquals("Initial route plus one post-pulse restore", 2, tuner.routes);
        assertEquals(1, tuner.focusRequests);
        assertEquals(expectedReleases, tuner.focusReleases);
        assertEquals("Opening the screen must not retune even when activation is needed", 0, tuner.tunes);
        assertEquals(0, tuner.seeks);
        assertTrue("Explicit activation must retain the framework unmute", player.normalWrites > 0);
        assertTrue("Explicit activation must retain the hardware unmute", player.hardwareWrites > 0);
        assertFalse(player.muted);
        assertTrue("The HCN activation gain policy must still execute", source.gainWrites > 0);
        assertTrue("Explicit fallback must still perform the finite PCM handoff", sawPcm);
        await(() -> audio.getActivePlaybackConfigurations().isEmpty(), "PCM pulse was not released");
        assertNull(field(RadioPlaybackService.class, "pendingRoutingClaim", service));
        assertPlayingAtObservedStation();
    }

    private void assertPlayingAtObservedStation() {
        assertTrue(RadioPlaybackService.isPlaybackRequested());
        assertEquals(PlaybackState.STATE_PLAYING, controller.getPlaybackState().getState());
        MediaMetadata metadata = controller.getMetadata();
        assertNotNull(metadata);
        assertEquals(MediaStationId.encode(STATION), metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID));
    }

    private void connectPassiveBrowser() throws Exception {
        CountDownLatch connected = new CountDownLatch(1);
        List<String> errors = new CopyOnWriteArrayList<>();
        instrumentation.runOnMainSync(() -> {
            browser = new MediaBrowser(context, new ComponentName(context, RadioPlaybackService.class),
                    new MediaBrowser.ConnectionCallback() {
                        @Override public void onConnected() {
                            try {
                                service = (RadioPlaybackService) field(RadioPlaybackService.class, "runningInstance", null);
                                assertNotNull(service);
                                setField(service, "radio", tuner.api);
                                configureFrameworkDoubles();
                                controller = new MediaController(context, browser.getSessionToken());
                            } catch (Exception | AssertionError failure) { errors.add(failure.toString()); }
                            connected.countDown();
                        }
                        @Override public void onConnectionFailed() {
                            errors.add("MediaBrowser connection failed");
                            connected.countDown();
                        }
                    }, null);
            ownsService = true;
            browser.connect();
        });
        assertTrue(connected.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertTrue(errors.toString(), errors.isEmpty());
        assertNotNull(controller);
        await(() -> controller.getMetadata() != null && MediaStationId.encode(STATION).equals(
                        controller.getMetadata().getString(MediaMetadata.METADATA_KEY_MEDIA_ID)),
                "Passive metadata did not reflect the synthetic tuner");
        drain();
    }

    private void configureFrameworkDoubles() throws Exception {
        Object reader = field(RadioPlaybackService.class, "playbackHealthReader", service);
        Method configure = RadioPlaybackHealthReader.class.getDeclaredMethod("configurePlayer", Object.class);
        configure.setAccessible(true);
        configure.invoke(reader, player);
        setField(reader, "sourceInfo", source);
        setField(reader, "getSourcePackage", Source.class.getMethod("getSourcePackage"));
        setField(reader, "sourceAvailable", true);
        setField(reader, "sourceInitialized", true);
        Object guard = field(RadioPlaybackService.class, "radioVolumeGuard", service);
        assertNotNull(guard);
        setField(guard, "sourceInfo", source);
        setField(guard, "setInputPathVolume", Source.class.getMethod("setInputPathVolume", int.class, int.class));
    }

    private long claimId() { return (Long) field(RadioPlaybackService.class, "routingClaimId", service); }

    private void drain() throws Exception {
        for (int pass = 0; pass < 3; pass++) {
            instrumentation.waitForIdleSync();
            ((ExecutorService) field(RadioPlaybackService.class, "executor", service))
                    .submit(() -> {}).get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
        instrumentation.waitForIdleSync();
    }

    private static void await(BooleanSupplier condition, String failure) {
        long end = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(20L);
        assertTrue(failure, condition.getAsBoolean());
    }

    private static Object field(Class<?> owner, String name, Object target) {
        try {
            Field value = owner.getDeclaredField(name);
            value.setAccessible(true);
            return value.get(target);
        } catch (ReflectiveOperationException error) { throw new AssertionError(name, error); }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field property = target.getClass().getDeclaredField(name);
            property.setAccessible(true);
            property.set(target, value);
        } catch (ReflectiveOperationException error) { throw new AssertionError(name, error); }
    }

    private static boolean hasVendorFramework() {
        for (String name : new String[]{"android.radio.RadioPlayer", "android.sourceservice.SourceInfo"}) {
            try { Class.forName(name); return true; }
            catch (ClassNotFoundException absent) { /* Synthetic dependencies only. */ }
        }
        return false;
    }

    public static final class Source {
        volatile String packageName = HCN_PACKAGE;
        volatile int gainWrites;
        public String getSourcePackage() { return packageName; }
        public synchronized void setInputPathVolume(int path, int gain) {
            assertEquals(0, path);
            assertEquals(RadioInputGainPolicy.NORMAL_GAIN_PERCENT, gain);
            gainWrites++;
        }
    }

    public static final class Player {
        volatile boolean muted;
        volatile boolean muteGetterAvailable = true;
        volatile int normalWrites;
        volatile int hardwareWrites;
        public boolean getRadioMute() {
            if (!muteGetterAvailable) throw new IllegalStateException("Synthetic unavailable mute getter");
            return muted;
        }
        public synchronized void setMute(boolean value) { normalWrites++; muted = value; }
        public synchronized void setRadioMute(boolean value) { hardwareWrites++; muted = value; }
    }

    private final class FakeTuner implements InvocationHandler {
        private final Binder binder = new Binder();
        volatile int routes, focusRequests, focusReleases, tunes, seeks;
        final IRadioServiceAPI api = (IRadioServiceAPI) Proxy.newProxyInstance(
                IRadioServiceAPI.class.getClassLoader(), new Class<?>[]{IRadioServiceAPI.class}, this);
        @Override public synchronized Object invoke(Object proxy, Method method, Object[] arguments) {
            switch (method.getName()) {
                case "asBinder": return binder;
                case "getCurrentBand": return STATION.band;
                case "getCurrentFreq": return STATION.frequency;
                case "getCurrentFreqRdsPs": return "";
                case "gotoFreq": tunes++; return null;
                case "requestPlayAudio": routes++; source.packageName = HCN_PACKAGE; return true;
                case "requestAudioFocus": focusRequests++; return null;
                case "releaseAudioFocus": focusReleases++; return null;
                case "onSeekDownEvent":
                case "onSeekUpEvent": seeks++; return null;
                case "toString": return "UiRadioAdoptionTest.FakeTuner";
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == arguments[0];
                default:
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
            }
        }
    }
}
