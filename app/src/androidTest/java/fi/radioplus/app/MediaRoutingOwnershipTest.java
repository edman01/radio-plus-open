package fi.radioplus.app;

import android.Manifest;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;

import androidx.test.platform.app.InstrumentationRegistry;

import com.hcn.autoradio.IRadioServiceAPI;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

/**
 * Android 13+ stock-emulator routing proof. Play/Pause exercise the real service
 * callbacks, but EVERY Next/Previous goes through AudioManager's untargeted
 * system route, never MediaController.dispatchMediaButtonEvent or a receiver.
 * Only the OEM AIDL tuner is faked. Requires an otherwise quiet emulator.
 */
public final class MediaRoutingOwnershipTest {
    private static final long TIMEOUT_MS = 5_000L;
    private static final FavoriteStation ALPHA = new FavoriteStation(0, 98_100, "Routing Alpha");
    private static final FavoriteStation BRAVO = new FavoriteStation(0, 99_900, "Routing Bravo");
    private static final FavoriteStation CHARLIE = new FavoriteStation(0, 104_500, "Routing Charlie");
    private static final List<FavoriteStation> FAVORITES = Arrays.asList(ALPHA, CHARLIE, BRAVO);
    private static final String EXTERNAL_FIXTURE_PACKAGE = "fi.radioplus.routingfixture";
    private static final String[] PREFERENCES = {
            "radio_plus_favorites", "radio_plus_station_catalog", "radio_plus_navigation"
    };

    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Map<String, Map<String, ?>> savedPreferences = new HashMap<>();
    private Context context;
    private AudioManager audio;
    private MediaSessionManager sessions;
    private MediaBrowser browser;
    private MediaController controller;
    private RadioPlaybackService service;
    private FakeTuner tuner;
    private boolean adoptedShellIdentity;
    private boolean ownsService;
    private boolean playbackCallbackRegistered;
    private volatile boolean sawActivePcm;
    private long lastKeyTime;

    private final AudioManager.AudioPlaybackCallback playbackObserver =
            new AudioManager.AudioPlaybackCallback() {
                @Override public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                    if (!configs.isEmpty()) sawActivePcm = true;
                }
            };

    @Before public void setUp() throws Exception {
        assumeTrue("Default media-key token lookup requires Android 13", Build.VERSION.SDK_INT >= 33);
        assumeFalse("Never run the fake-tuner fixture on physical Junsun hardware", hasVendorFramework());
        context = instrumentation.getTargetContext();
        assertNull("Stop the target app before routing instrumentation", field(
                RadioPlaybackService.class, "runningInstance", null));
        instrumentation.getUiAutomation().adoptShellPermissionIdentity(Manifest.permission.MEDIA_CONTENT_CONTROL);
        adoptedShellIdentity = true;
        audio = context.getSystemService(AudioManager.class);
        sessions = context.getSystemService(MediaSessionManager.class);
        assertNotNull(audio);
        assertNotNull(sessions);
        await(() -> audio.getActivePlaybackConfigurations().isEmpty(),
                "Routing tests require an emulator without unrelated PCM playback");
        for (String name : PREFERENCES) {
            SharedPreferences preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE);
            savedPreferences.put(name, new HashMap<>(preferences.getAll()));
            assertTrue(preferences.edit().clear().commit());
        }
        new FavoriteStore(context).replaceOrder(FAVORITES);
        new StationStore(context).replaceOrder(Arrays.asList(ALPHA, BRAVO, CHARLIE));
        new StationNavigationStore(context).setFavoritesSelected(true);
        tuner = new FakeTuner();
        connectBrowser();
        audio.registerAudioPlaybackCallback(playbackObserver, new Handler(Looper.getMainLooper()));
        playbackCallbackRegistered = true;
        assertEquals(0, tuner.tunes().size());
        assertEquals("Passive browsing must not activate the OEM route", 0, tuner.routeRequests);
        assertEquals("not-requested", pulseStatus());
    }

    @After public void tearDown() throws Exception {
        try {
            if (playbackCallbackRegistered) audio.unregisterAudioPlaybackCallback(playbackObserver);
            if (ownsService) {
                instrumentation.runOnMainSync(() -> {
                    if (browser != null) browser.disconnect();
                    context.stopService(new Intent(context, RadioPlaybackService.class));
                });
                await(() -> field(RadioPlaybackService.class, "runningInstance", null) == null,
                        "Routing test service did not stop");
                await(() -> audio.getActivePlaybackConfigurations().isEmpty(),
                        "A PCM player remained active after service destruction");
            }
        } finally {
            try {
                if (context != null) {
                    for (Map.Entry<String, Map<String, ?>> entry : savedPreferences.entrySet()) {
                        restorePreferences(entry.getKey(), entry.getValue());
                    }
                    if (!savedPreferences.isEmpty()) StationWidgetProvider.requestDataRefresh(context);
                }
            } finally {
                if (adoptedShellIdentity) instrumentation.getUiAutomation().dropShellPermissionIdentity();
            }
        }
    }

    @Test public void playClaimsTheActualSystemRouteAndReleasedPulseKeepsFavoriteKeyRouting()
            throws Exception {
        // Reproduce an advertised active/PLAYING session without any PCM or OEM
        // activation. Merely listing it in getActiveSessions is not ownership.
        instrumentation.runOnMainSync(() -> {
            MediaSession session = (MediaSession) field(RadioPlaybackService.class, "mediaSession", service);
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                    .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                    .build());
            session.setActive(true);
        });
        await(this::serviceListedAsActive, "The baseline media session must be active");
        await(() -> controller.getPlaybackState().getState() == PlaybackState.STATE_PLAYING,
                "The no-PCM baseline must actually advertise PLAYING");
        assertTrue(audio.getActivePlaybackConfigurations().isEmpty());
        assertFalse(sawActivePcm);
        reportBaseline();

        playAndAwaitReleasedPulse();
        assertTrue("The finite pulse must register actual PCM activity", sawActivePcm);
        assertEquals(controller.getSessionToken(), sessions.getMediaKeyEventSession());
        assertFavoriteQueue();
        // Verify persistence after release before delivering any new command.
        assertQuietDefaultRouteFor(1_500L);
        expectSystemTune(KeyEvent.KEYCODE_MEDIA_NEXT, CHARLIE);
        expectSystemTune(KeyEvent.KEYCODE_MEDIA_NEXT, BRAVO);
        expectSystemTune(KeyEvent.KEYCODE_MEDIA_NEXT, ALPHA); // wrap in stored order
        expectSystemTune(KeyEvent.KEYCODE_MEDIA_PREVIOUS, BRAVO);
        expectSystemTune(KeyEvent.KEYCODE_MEDIA_PREVIOUS, CHARLIE);
        expectSystemTune(KeyEvent.KEYCODE_MEDIA_PREVIOUS, ALPHA);
        assertEquals("System media keys must never become a frequency seek", 0, tuner.seekCalls);
        assertQuietDefaultRouteFor(1_500L);
    }

    @Test public void pauseBeforeTheSettleDelayCancelsThePulseAndRouteRestore() throws Exception {
        controller.getTransportControls().play();
        await(() -> "scheduled".equals(pulseStatus()), "Explicit play must schedule the bounded routing claim");
        controller.getTransportControls().pause();
        await(() -> controller.getPlaybackState().getState() == PlaybackState.STATE_PAUSED,
                "Pause callback was not applied");
        // Covers the 650 ms claim delay and both 700/1400 ms pause reassertions.
        assertNoPcmFor(1_650L);
        drainServiceCommands();
        assertFalse("Cancellation before the delay must create no PCM player", sawActivePcm);
        assertNotEquals("completed", pulseStatus());
        assertEquals("The canceled claim must not restore OEM audio after pause", 1, tuner.routeRequests);
        assertEquals(0, tuner.tunes().size());
        assertFalse(RadioPlaybackService.isPlaybackRequested());
        assertEquals(Boolean.FALSE, field(RadioPlaybackService.class, "oemRouteActive", service));
    }

    @Test public void quickPauseThenPlayCannotApplyAStaleDelayedOemRelease() throws Exception {
        playAndAwaitReleasedPulse();
        int releasesBefore = tuner.focusReleases;
        int focusBefore = tuner.focusRequests;
        int routesBefore = tuner.routeRequests;
        // Both Binder commands are enqueued back-to-back, before the old
        // 250 ms pause-release runnable can run on the main looper.
        controller.getTransportControls().pause();
        controller.getTransportControls().play();
        await(() -> tuner.focusRequests > focusBefore, "The resumed play did not reach the OEM focus API");
        await(() -> tuner.routeRequests >= routesBefore + 2 && "completed".equals(pulseStatus()),
                "Resumed play must complete its own pulse and one-time route restoration");
        drainServiceCommands();
        assertQuietDefaultRouteFor(1_650L);
        assertEquals("Only the explicit resume takeover may release OEM focus",
                releasesBefore + 1, tuner.focusReleases);
        assertEquals(PlaybackState.STATE_PLAYING, controller.getPlaybackState().getState());
        assertTrue(RadioPlaybackService.isPlaybackRequested());
        assertEquals(Boolean.TRUE, field(RadioPlaybackService.class, "oemRouteActive", service));
        expectSystemTune(KeyEvent.KEYCODE_MEDIA_NEXT, CHARLIE);
    }

    @Test public void anotherAppsReleasedPlaybackKeepsOwnershipUntilExplicitRadioPlay() throws Exception {
        assumeTrue("Install the separate emulator-only routing fixture and pass externalFixture=true",
                Boolean.parseBoolean(InstrumentationRegistry.getArguments().getString("externalFixture")));
        playAndAwaitReleasedPulse();
        assertQuietDefaultRouteFor(200L);
        boolean fixtureStarted = false;
        try {
            sendFixtureCommand("pulse");
            fixtureStarted = true;
            await(() -> EXTERNAL_FIXTURE_PACKAGE.equals(sessions.getMediaKeyEventSessionPackageName()),
                    "The independent UID never became Android's default media-key target");
            MediaSession.Token fixtureToken = sessions.getMediaKeyEventSession();
            assertNotNull(fixtureToken);
            assertNotEquals(controller.getSessionToken(), fixtureToken);
            MediaController fixture = new MediaController(context, fixtureToken);
            // The fixture switches to PAUSED only after its finite PCM track
            // is released. Wait for both pieces of evidence before observing
            // ownership: testing only while the other UID plays is weaker.
            await(() -> fixture.getPlaybackState() != null
                            && fixture.getPlaybackState().getState() == PlaybackState.STATE_PAUSED
                            && audio.getActivePlaybackConfigurations().isEmpty(),
                    "The independent fixture did not finish and release its finite PCM pulse");
            long observationEnds = SystemClock.elapsedRealtime() + 6_500L;
            do {
                assertTrue("Neither app may keep PCM playing to retain key ownership",
                        audio.getActivePlaybackConfigurations().isEmpty());
                assertEquals("Radio+ must not periodically reclaim another app's default route",
                        EXTERNAL_FIXTURE_PACKAGE, sessions.getMediaKeyEventSessionPackageName());
                assertEquals("The independent app's released session must retain the exact default token",
                        fixtureToken, sessions.getMediaKeyEventSession());
                SystemClock.sleep(40L);
            } while (SystemClock.elapsedRealtime() < observationEnds);
            assertEquals("Observing another player must not tune the OEM radio", 0, tuner.tunes().size());

            // An explicit Radio+ Play is a permitted new takeover, unlike the
            // passive health/metadata polling during the interval above.
            playAndAwaitReleasedPulse();
            assertEquals(context.getPackageName(), sessions.getMediaKeyEventSessionPackageName());
            assertQuietDefaultRouteFor(500L);
        } finally {
            if (fixtureStarted) {
                sendFixtureCommand("stop");
                await(() -> !externalFixtureListedAsActive(), "The fixture session was not released by stop");
                await(() -> audio.getActivePlaybackConfigurations().isEmpty(),
                        "The fixture left PCM active after stop");
            }
        }
    }

    private void sendFixtureCommand(String action) {
        context.startActivity(new Intent()
                .setComponent(new ComponentName(EXTERNAL_FIXTURE_PACKAGE,
                        EXTERNAL_FIXTURE_PACKAGE + ".RoutingFixtureActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("action", action));
    }

    private boolean externalFixtureListedAsActive() {
        for (MediaController active : sessions.getActiveSessions(null)) {
            if (EXTERNAL_FIXTURE_PACKAGE.equals(active.getPackageName())) return true;
        }
        return false;
    }

    private void playAndAwaitReleasedPulse() throws Exception {
        int routesBefore = tuner.routeRequests;
        controller.getTransportControls().play();
        await(() -> "completed".equals(pulseStatus()) && tuner.routeRequests >= routesBefore + 2,
                "Explicit play did not complete its finite routing pulse and OEM route restore");
        drainServiceCommands();
        await(() -> controller.getSessionToken().equals(sessions.getMediaKeyEventSession()),
                "The real Android default media-key token does not belong to Radio+");
        await(() -> audio.getActivePlaybackConfigurations().isEmpty(),
                "The routing pulse left PCM playback active");
    }

    private void expectSystemTune(int keyCode, FavoriteStation expected) throws Exception {
        assertEquals("Check default ownership before every untargeted key",
                controller.getSessionToken(), sessions.getMediaKeyEventSession());
        drainServiceCommands();
        instrumentation.runOnMainSync(() -> sawActivePcm = false);
        int before = tuner.tunes().size();
        int routesBefore = tuner.routeRequests;
        int focusBefore = tuner.focusRequests;
        int releasesBefore = tuner.focusReleases;
        long claimBefore = (Long) field(RadioPlaybackService.class, "routingClaimId", service);
        long down = Math.max(SystemClock.uptimeMillis(), lastKeyTime + 10L);
        lastKeyTime = down;
        audio.dispatchMediaKeyEvent(new KeyEvent(down, down, KeyEvent.ACTION_DOWN, keyCode, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(down, down + 1L, KeyEvent.ACTION_UP, keyCode, 0));
        await(() -> tuner.tunes().size() > before, "The system-routed media key never reached gotoFreq");
        drainServiceCommands();
        await(() -> metadataMatches(expected), "Confirmed tuner state did not reach media metadata");
        // Observe from the already-owned route through the former 650 ms
        // claim delay and bounded pulse. Checking only after another pulse
        // completed hid the hardware's new-station -> gap -> resume defect.
        assertQuietDefaultRouteFor(1_650L);
        drainServiceCommands();
        assertFalse("Steady station navigation must not start a new PCM routing pulse", sawActivePcm);
        assertEquals("Steady station navigation must not schedule another ownership claim",
                claimBefore, (long) (Long) field(RadioPlaybackService.class, "routingClaimId", service));
        assertEquals("Steady navigation must not cycle OEM audio focus", focusBefore, tuner.focusRequests);
        assertEquals("Steady navigation must not release OEM audio focus", releasesBefore, tuner.focusReleases);
        assertEquals("Only the immediate post-confirmation route request is allowed; no delayed restore",
                routesBefore + 1, tuner.routeRequests);
        assertEquals("Exactly one station per system media key press", before + 1, tuner.tunes().size());
        assertEquals("Actual OEM command must follow the favorites' stored order", expected, tuner.tunes().get(before));
        assertEquals(expected.band, tuner.current().band);
        assertEquals(expected.frequency, tuner.current().frequency);
        assertEquals(0, tuner.seekCalls);
    }

    private void assertFavoriteQueue() {
        List<MediaSession.QueueItem> queue = controller.getQueue();
        assertNotNull(queue);
        assertEquals(FAVORITES.size(), queue.size());
        for (int index = 0; index < FAVORITES.size(); index++) {
            assertEquals(MediaStationId.encode(FAVORITES.get(index)), queue.get(index).getDescription().getMediaId());
        }
    }

    private void assertQuietDefaultRouteFor(long durationMillis) {
        long end = SystemClock.elapsedRealtime() + durationMillis;
        do {
            assertTrue("A bounded claim must not become continuous PCM playback",
                    audio.getActivePlaybackConfigurations().isEmpty());
            assertEquals("Default media routing must persist after AudioTrack release",
                    controller.getSessionToken(), sessions.getMediaKeyEventSession());
            SystemClock.sleep(40L);
        } while (SystemClock.elapsedRealtime() < end);
    }

    private void assertNoPcmFor(long durationMillis) {
        long end = SystemClock.elapsedRealtime() + durationMillis;
        do {
            assertTrue("A canceled claim must not play PCM", audio.getActivePlaybackConfigurations().isEmpty());
            SystemClock.sleep(40L);
        } while (SystemClock.elapsedRealtime() < end);
    }

    private boolean serviceListedAsActive() {
        for (MediaController active : sessions.getActiveSessions(null)) {
            if (controller.getSessionToken().equals(active.getSessionToken())) return true;
        }
        return false;
    }

    private void reportBaseline() {
        MediaSession.Token before = sessions.getMediaKeyEventSession();
        boolean alreadyDefault = controller.getSessionToken().equals(before);
        Bundle evidence = new Bundle();
        evidence.putString("stream", "\nMedia routing baseline: active/PLAYING=true, PCM=false, default_is_radio="
                + alreadyDefault + ". " + (alreadyDefault
                ? "Existing UID playback history prevents a clean negative-baseline claim."
                : "Active session alone was not Android's default target.") + "\n");
        evidence.putBoolean("radio_was_default_before_pulse", alreadyDefault);
        instrumentation.sendStatus(0, evidence);
        if (Boolean.parseBoolean(InstrumentationRegistry.getArguments().getString("strictCleanBaseline"))) {
            assertFalse("A strict fresh-UID baseline must not own the system media-key route before PCM playback",
                    alreadyDefault);
        }
    }

    private String pulseStatus() {
        return (String) field(RadioPlaybackService.class, "routingPulseStatus", service);
    }

    private boolean metadataMatches(FavoriteStation expected) {
        MediaMetadata metadata = controller.getMetadata();
        return metadata != null && MediaStationId.encode(expected).equals(
                metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID));
    }

    private void connectBrowser() throws Exception {
        CountDownLatch connected = new CountDownLatch(1);
        List<String> errors = new CopyOnWriteArrayList<>();
        instrumentation.runOnMainSync(() -> {
            browser = new MediaBrowser(context, new ComponentName(context, RadioPlaybackService.class),
                    new MediaBrowser.ConnectionCallback() {
                        @Override public void onConnected() {
                            service = (RadioPlaybackService) field(RadioPlaybackService.class, "runningInstance", null);
                            if (service == null) errors.add("No service instance after MediaBrowser connection");
                            else {
                                setField(service, "radio", tuner.api);
                                controller = new MediaController(context, browser.getSessionToken());
                            }
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
        await(() -> metadataMatches(ALPHA), "The service did not read the injected tuner's actual station");
        drainServiceCommands();
    }

    private void drainServiceCommands() throws Exception {
        for (int pass = 0; pass < 3; pass++) {
            instrumentation.waitForIdleSync();
            ((ExecutorService) field(RadioPlaybackService.class, "executor", service))
                    .submit(() -> {}).get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
        instrumentation.waitForIdleSync();
    }

    private static void await(BooleanSupplier condition, String message) {
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20L);
        assertTrue(message, condition.getAsBoolean());
    }

    private static Object field(Class<?> type, String name, Object target) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException error) { throw new AssertionError(name, error); }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException error) { throw new AssertionError(name, error); }
    }

    private static boolean hasVendorFramework() {
        for (String name : new String[]{"android.radio.RadioPlayer", "android.sourceservice.SourceInfo"}) {
            try { Class.forName(name); return true; }
            catch (ClassNotFoundException ignored) { /* Stock emulator only. */ }
        }
        return false;
    }

    private void restorePreferences(String name, Map<String, ?> values) {
        SharedPreferences.Editor editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof Set) {
                @SuppressWarnings("unchecked") Set<String> strings = (Set<String>) value;
                editor.putStringSet(key, new HashSet<>(strings));
            } else throw new AssertionError("Unsupported preference type: " + key);
        }
        assertTrue(editor.commit());
    }

    private static final class FakeTuner implements InvocationHandler {
        private final Binder binder = new Binder();
        private final List<FavoriteStation> requestedTunes = new ArrayList<>();
        private int band = ALPHA.band;
        private int frequency = ALPHA.frequency;
        volatile int routeRequests;
        volatile int focusRequests;
        volatile int focusReleases;
        volatile int seekCalls;
        final IRadioServiceAPI api = (IRadioServiceAPI) Proxy.newProxyInstance(
                IRadioServiceAPI.class.getClassLoader(), new Class<?>[]{IRadioServiceAPI.class}, this);

        synchronized FavoriteStation current() { return new FavoriteStation(band, frequency, ""); }
        synchronized List<FavoriteStation> tunes() { return new ArrayList<>(requestedTunes); }

        @Override public synchronized Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "asBinder": return binder;
                case "getCurrentBand": return band;
                case "getCurrentFreq": return frequency;
                case "getCurrentFreqRdsPs": return "";
                case "gotoFreq":
                    frequency = (Integer) args[0];
                    requestedTunes.add(new FavoriteStation(band, frequency, ""));
                    return null;
                case "onBandEvent": band = (band + 1) % 4; return null;
                case "requestPlayAudio": routeRequests++; return true;
                case "requestAudioFocus": focusRequests++; return null;
                case "releaseAudioFocus": focusReleases++; return null;
                case "onSeekDownEvent":
                case "onSeekUpEvent": seekCalls++; return null;
                case "toString": return "MediaRoutingOwnershipTest.FakeTuner";
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == args[0];
                default:
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
            }
        }
    }
}
