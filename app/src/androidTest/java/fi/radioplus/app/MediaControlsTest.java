package fi.radioplus.app;

import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.ViewConfiguration;

import androidx.test.platform.app.InstrumentationRegistry;

import com.hcn.autoradio.IRadioServiceAPI;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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

/**
 * Emulator-only integration coverage: real MediaBrowser/MediaController Binder
 * dispatch and the manifest receiver, with only the OEM tuner replaced.
 * No production test hooks, preview activity, or physical radio are required.
 */
public final class MediaControlsTest {
    private static final long TIMEOUT_MS = 5_000L;
    private static final FavoriteStation ALPHA = new FavoriteStation(0, 98_100, "QA Alpha");
    private static final FavoriteStation BRAVO = new FavoriteStation(1, 101_700, "QA Bravo");
    private static final FavoriteStation CHARLIE = new FavoriteStation(3, 999, "QA Charlie");
    private static final FavoriteStation DELTA = new FavoriteStation(0, 104_500, "QA Delta");
    private static final String[] PREFERENCES = {
            "radio_plus_favorites", "radio_plus_station_catalog", "radio_plus_navigation"
    };

    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Map<String, Map<String, ?>> savedPreferences = new HashMap<>();
    private final List<String> observedMediaIds = new CopyOnWriteArrayList<>();
    private final List<Integer> observedPlaybackStates = new CopyOnWriteArrayList<>();
    private Context context;
    private FavoriteStore favorites;
    private StationStore catalog;
    private StationNavigationStore navigation;
    private MediaBrowser browser;
    private MediaController controller;
    private RadioPlaybackService service;
    private FakeTuner tuner;
    private boolean ownsService;
    private long lastKeyTime;

    private final MediaController.Callback observer = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata metadata) {
            if (metadata != null) {
                observedMediaIds.add(metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID));
            }
        }

        @Override public void onPlaybackStateChanged(PlaybackState state) {
            if (state != null) observedPlaybackStates.add(state.getState());
        }
    };

    @Before public void setUp() throws Exception {
        // Vendor reflection is intentionally outside the fake AIDL boundary.
        // Never let this emulator fixture alter a real head unit's audio route.
        assumeFalse("Use an emulator without the Junsun framework", hasVendorFramework());
        context = instrumentation.getTargetContext();
        instrumentation.runOnMainSync(() -> assertNull(
                "Stop the test app before running media-control instrumentation",
                field(RadioPlaybackService.class, "runningInstance", null)));
        for (String name : PREFERENCES) {
            SharedPreferences preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE);
            savedPreferences.put(name, new HashMap<>(preferences.getAll()));
            assertTrue(preferences.edit().clear().commit());
        }
        favorites = new FavoriteStore(context);
        catalog = new StationStore(context);
        navigation = new StationNavigationStore(context);
        favorites.replaceOrder(Arrays.asList(ALPHA, BRAVO, CHARLIE));
        catalog.replaceOrder(Arrays.asList(ALPHA, CHARLIE, BRAVO));
        tuner = new FakeTuner(ALPHA);
        connectBrowser();
        assertEquals("Passive browsing must not tune", 0, tuner.tunes().size());
        assertEquals("Passive browsing must not activate the OEM route", 0, tuner.routeRequests);
    }

    @After public void tearDown() throws Exception {
        SteeringDiagnosticTrace.get().clear();
        try {
            disconnectBrowserAndStop();
        } finally {
            if (context != null) {
                for (Map.Entry<String, Map<String, ?>> entry : savedPreferences.entrySet()) {
                    restorePreferences(entry.getKey(), entry.getValue());
                }
                if (!savedPreferences.isEmpty()) StationWidgetProvider.requestDataRefresh(context);
            }
        }
    }

    @Test public void transportCallbacksUseTheSelectedListAndActualTunerBand() throws Exception {
        assertTrue("Fresh installs retain favorites as the default", navigation.favoritesSelected());
        expectTune(() -> controls().skipToNext(), BRAVO);
        expectTune(() -> controls().skipToPrevious(), ALPHA);
        expectTune(() -> controls().fastForward(), BRAVO);
        expectTune(() -> controls().rewind(), ALPHA);

        navigation.setFavoritesSelected(false);
        expectTune(() -> controls().skipToNext(), CHARLIE);
        expectTune(() -> controls().skipToPrevious(), ALPHA);
        expectTune(() -> controls().fastForward(), CHARLIE);
        expectTune(() -> controls().rewind(), ALPHA);
        assertEquals("Next/previous never call the hardware search API", 0, tuner.seekCalls);
    }

    @Test public void coldNextClaimsRoutingButSteadyAdjacentNavigationDoesNotReclaim() throws Exception {
        long claimBefore = routingClaimId();
        expectTune(() -> controls().skipToNext(), BRAVO);
        awaitRoutingClaimCompleted(claimBefore, 3);
        assertEquals("A cold Next must acquire OEM focus", 1, tuner.focusRequests);
        assertEquals("A cold Next replaces stale OEM focus once", 1, tuner.focusReleases);

        expectSteadyAdjacentTune(() -> controls().skipToNext(), CHARLIE);
        expectSteadyAdjacentTune(() -> controls().skipToPrevious(), BRAVO);
        expectSteadyAdjacentTune(() -> controls().fastForward(), CHARLIE);
        expectSteadyAdjacentTune(() -> controls().rewind(), BRAVO);
    }

    @Test public void pauseThenNextStartsANewPlaybackOwnershipClaim() throws Exception {
        long firstClaim = routingClaimId();
        expectTune(() -> controls().skipToNext(), BRAVO);
        awaitRoutingClaimCompleted(firstClaim, 3);
        int focusBefore = tuner.focusRequests;
        int releasesBefore = tuner.focusReleases;
        int routesBefore = tuner.routeRequests;
        long claimBefore = routingClaimId();
        controls().pause();
        await(() -> controller.getPlaybackState().getState() == PlaybackState.STATE_PAUSED,
                "Pause must complete before Next resumes playback");
        expectTune(() -> controls().skipToNext(), CHARLIE);
        awaitRoutingClaimCompleted(claimBefore, routesBefore + 3);
        assertEquals("Next after pause must reacquire OEM focus", focusBefore + 1, tuner.focusRequests);
        assertEquals("Pause/resume must release OEM focus once, not via a stale delayed callback",
                releasesBefore + 1, tuner.focusReleases);
        assertTrue(RadioPlaybackService.isPlaybackRequested());
        assertEquals(PlaybackState.STATE_PLAYING, controller.getPlaybackState().getState());
        assertEquals(0, tuner.seekCalls);
    }

    @Test public void reopeningRadioScreenPreservesActivePlaybackWithoutAFreshHandoff() throws Exception {
        long firstClaim = routingClaimId();
        expectTune(() -> RadioPlaybackService.tuneStation(context, BRAVO), BRAVO);
        awaitRoutingClaimCompleted(firstClaim, 3);
        RadioPlaybackService.noteUiBackgrounded();
        long claimBefore = routingClaimId();
        int routesBefore = tuner.routeRequests;
        int focusBefore = tuner.focusRequests;
        int releasesBefore = tuner.focusReleases;
        RadioPlaybackService.ensureRunningForUi(context);
        SystemClock.sleep(1_650L);
        drainServiceCommands();
        assertEquals("Reopening the screen must not schedule another PCM handoff",
                claimBefore, routingClaimId());
        assertEquals(routesBefore, tuner.routeRequests);
        assertEquals(focusBefore, tuner.focusRequests);
        assertEquals(releasesBefore, tuner.focusReleases);
        assertEquals(PlaybackState.STATE_PLAYING, controller.getPlaybackState().getState());
        assertTrue(RadioPlaybackService.isPlaybackRequested());
        assertActualStation(BRAVO);
        assertMetadataStation(BRAVO);
    }

    @Test public void reopeningRadioScreenPreservesPauseUntilExplicitPlay() throws Exception {
        long firstClaim = routingClaimId();
        expectTune(() -> RadioPlaybackService.tuneStation(context, BRAVO), BRAVO);
        awaitRoutingClaimCompleted(firstClaim, 3);
        controls().pause();
        await(() -> controller.getPlaybackState().getState() == PlaybackState.STATE_PAUSED,
                "Pause must precede reopening the radio screen");
        SystemClock.sleep(1_650L);
        drainServiceCommands();
        long claimBefore = routingClaimId();
        int routesBefore = tuner.routeRequests;
        int focusBefore = tuner.focusRequests;
        int releasesBefore = tuner.focusReleases;
        RadioPlaybackService.noteUiBackgrounded();
        RadioPlaybackService.ensureRunningForUi(context);
        SystemClock.sleep(1_650L);
        drainServiceCommands();
        assertEquals(claimBefore, routingClaimId());
        assertEquals(routesBefore, tuner.routeRequests);
        assertEquals(focusBefore, tuner.focusRequests);
        assertEquals(releasesBefore, tuner.focusReleases);
        assertEquals(PlaybackState.STATE_PAUSED, controller.getPlaybackState().getState());
        assertFalse(RadioPlaybackService.isPlaybackRequested());
        controls().play();
        awaitRoutingClaimCompleted(claimBefore, routesBefore + 2);
        assertEquals(PlaybackState.STATE_PLAYING, controller.getPlaybackState().getState());
        assertEquals(focusBefore + 1, tuner.focusRequests);
    }

    @Test public void screenStationTapsReusePlaybackAfterTheFirstSelection() throws Exception {
        long firstClaim = routingClaimId();
        expectTune(() -> RadioPlaybackService.tuneStation(context, BRAVO), BRAVO);
        awaitRoutingClaimCompleted(firstClaim, 3);
        expectSteadyAdjacentTune(() -> RadioPlaybackService.tuneStation(context, CHARLIE), CHARLIE);
        expectSteadyAdjacentTune(() -> RadioPlaybackService.tuneStation(context, ALPHA), ALPHA);
    }

    @Test public void connectedBackendIsNotReinspectedWhenSelectingAStation() throws Exception {
        int generation = (Integer) field(RadioPlaybackService.class, "backendGeneration", service);
        long firstClaim = routingClaimId();
        expectTune(() -> RadioPlaybackService.tuneStation(context, BRAVO), BRAVO);
        awaitRoutingClaimCompleted(firstClaim, 3);
        assertEquals("Station selection must reuse the resolved backend", generation,
                field(RadioPlaybackService.class, "backendGeneration", service));
        assertEquals(false, field(RadioPlaybackService.class, "inspectingBackend", service));
        assertTrue(RadioPlaybackService.isPlaybackRequested());
    }

    @Test public void legacyPlaybackSkipsAbsentOemFocusEndpoints() throws Exception {
        List<Integer> transactions = new CopyOnWriteArrayList<>();
        Binder legacy = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                data.enforceInterface(RadioApiFactory.DESCRIPTOR);
                transactions.add(code);
                reply.writeNoException();
                switch (code) {
                    case 5:
                        synchronized (tuner) { tuner.band = (tuner.band + 1) % 4; }
                        break;
                    case 14:
                        synchronized (tuner) {
                            tuner.frequency = data.readInt();
                            tuner.requestedTunes.add(tuner.current());
                        }
                        break;
                    case 17: reply.writeInt(tuner.current().band); break;
                    case 18: reply.writeInt(tuner.current().frequency); break;
                    case 25:
                        tuner.routeRequests++;
                        reply.writeInt(1);
                        break;
                    default: throw new AssertionError("Unexpected legacy playback transaction " + code);
                }
                assertEquals(0, data.dataAvail());
                return true;
            }
        };
        legacy.attachInterface(null, RadioApiFactory.DESCRIPTOR);
        instrumentation.runOnMainSync(() -> setField(service, "radio", new LegacyHcnRadioApi(legacy)));
        long firstClaim = routingClaimId();
        expectTune(() -> RadioPlaybackService.tuneStation(context, DELTA), DELTA);
        awaitRoutingClaimCompleted(firstClaim, 3);
        controls().pause();
        await(() -> controller.getPlaybackState().getState() == PlaybackState.STATE_PAUSED,
                "Legacy pause must update the media session");
        drainServiceCommands();
        long pausedClaim = routingClaimId();
        int routesBeforeResume = tuner.routeRequests;
        controls().play();
        awaitRoutingClaimCompleted(pausedClaim, routesBeforeResume + 2);
        assertTrue(transactions.contains(14));
        assertTrue(transactions.contains(25));
        assertFalse(transactions.contains(30));
        assertFalse(transactions.contains(31));
        assertEquals(0, tuner.focusRequests);
        assertEquals(0, tuner.focusReleases);
    }

    @Test public void screenStationTapResumesAfterPauseThenReusesPlayback() throws Exception {
        long firstClaim = routingClaimId();
        expectTune(() -> RadioPlaybackService.tuneStation(context, BRAVO), BRAVO);
        awaitRoutingClaimCompleted(firstClaim, 3);
        controls().pause();
        await(() -> controller.getPlaybackState().getState() == PlaybackState.STATE_PAUSED,
                "Pause must complete before selecting a station");
        int routesBefore = tuner.routeRequests;
        long claimBefore = routingClaimId();
        expectTune(() -> RadioPlaybackService.tuneStation(context, CHARLIE), CHARLIE);
        awaitRoutingClaimCompleted(claimBefore, routesBefore + 3);
        expectSteadyAdjacentTune(() -> RadioPlaybackService.tuneStation(context, ALPHA), ALPHA);
    }

    @Test public void screenStationTapReclaimsAfterLeavingTheAppWhenHealthIsUnknown() throws Exception {
        long firstClaim = routingClaimId();
        expectTune(() -> RadioPlaybackService.tuneStation(context, BRAVO), BRAVO);
        awaitRoutingClaimCompleted(firstClaim, 3);
        RadioPlaybackService.noteUiBackgrounded();
        int routesBefore = tuner.routeRequests;
        int focusBefore = tuner.focusRequests;
        long claimBefore = routingClaimId();
        expectTune(() -> RadioPlaybackService.tuneStation(context, CHARLIE), CHARLIE);
        awaitRoutingClaimCompleted(claimBefore, routesBefore + 3);
        assertEquals("Returning from another app must regain OEM audio focus",
                focusBefore + 1, tuner.focusRequests);
        expectSteadyAdjacentTune(() -> RadioPlaybackService.tuneStation(context, ALPHA), ALPHA);
    }

    @Test public void screenStationIntentReusesPlaybackButWidgetIntentStillReclaims() throws Exception {
        long firstClaim = routingClaimId();
        expectTune(() -> startStationIntent(RadioPlaybackService.ACTION_TUNE_UI_STATION, BRAVO), BRAVO);
        awaitRoutingClaimCompleted(firstClaim, 3);
        expectSteadyAdjacentTune(
                () -> startStationIntent(RadioPlaybackService.ACTION_TUNE_UI_STATION, CHARLIE), CHARLIE);
        int routesBefore = tuner.routeRequests;
        long claimBefore = routingClaimId();
        expectTune(() -> startStationIntent(RadioPlaybackService.ACTION_TUNE_STATION, ALPHA), ALPHA);
        awaitRoutingClaimCompleted(claimBefore, routesBefore + 3);
    }

    private void startStationIntent(String action, FavoriteStation station) {
        context.startForegroundService(new Intent(context, RadioPlaybackService.class)
                .setAction(action)
                .putExtra(RadioPlaybackService.EXTRA_STATION_BAND, station.band)
                .putExtra(RadioPlaybackService.EXTRA_STATION_FREQUENCY, station.frequency)
                .putExtra(RadioPlaybackService.EXTRA_STATION_NAME, station.name));
    }

    @Test public void explicitMediaIdSelectionStillTakesOverWhenOemHealthIsUnknown() throws Exception {
        long firstClaim = routingClaimId();
        expectTune(() -> controls().skipToNext(), BRAVO);
        awaitRoutingClaimCompleted(firstClaim, 3);
        int focusBefore = tuner.focusRequests;
        int releasesBefore = tuner.focusReleases;
        int routesBefore = tuner.routeRequests;
        long claimBefore = routingClaimId();
        // An external media-ID command is deliberately stronger than an
        // in-app station tap: the OEM health bridge is unavailable on this emulator,
        // as it can be unreliable when another app is playing on some ROMs.
        expectTune(() -> controls().playFromMediaId(MediaStationId.encode(ALPHA), null), ALPHA);
        awaitRoutingClaimCompleted(claimBefore, routesBefore + 3);
        assertEquals(focusBefore + 1, tuner.focusRequests);
        assertEquals(releasesBefore + 1, tuner.focusReleases);
        assertEquals(0, tuner.seekCalls);
    }

    @Test public void optInTraceShowsTransportToTunerWithoutStationNames() throws Exception {
        SteeringDiagnosticTrace capture = SteeringDiagnosticTrace.get();
        capture.clear();
        expectTune(() -> controls().skipToNext(), BRAVO);
        assertTrue(capture.report().contains("total=0"));
        capture.begin();
        expectTune(() -> controls().skipToPrevious(), ALPHA);
        capture.stop();
        String report = capture.report();
        assertTrue(report, report.contains("source=transport"));
        assertTrue(report, report.contains("source=navigation"));
        assertTrue(report, report.contains("frequency-confirmed=true"));
        assertFalse(report, report.contains("QA Alpha"));
        assertFalse(report, report.contains("QA Bravo"));
        assertEquals(0, tuner.seekCalls);
    }

    @Test public void catalogReorderAndWrapAreReadWithoutRestartingTheService() throws Exception {
        navigation.setFavoritesSelected(false);
        expectTune(() -> controls().skipToPrevious(), BRAVO); // first -> last
        expectTune(() -> controls().skipToNext(), ALPHA); // last -> first
        catalog.replaceOrder(Arrays.asList(ALPHA, BRAVO, CHARLIE));
        expectTune(() -> controls().skipToNext(), BRAVO);
        expectTune(() -> controls().skipToNext(), CHARLIE);
        expectTune(() -> controls().skipToNext(), ALPHA);

        favorites.replaceOrder(Arrays.asList(ALPHA, CHARLIE, BRAVO));
        navigation.setFavoritesSelected(true);
        expectTune(() -> controls().skipToNext(), CHARLIE);
        assertEquals(0, tuner.seekCalls);
    }

    @Test public void selectedCatalogSurvivesServiceRecreation() throws Exception {
        navigation.setFavoritesSelected(false);
        expectTune(() -> controls().skipToNext(), CHARLIE);
        disconnectBrowserAndStop();
        assertFalse(new StationNavigationStore(context).favoritesSelected());
        connectBrowser();
        expectTune(() -> controls().skipToNext(), BRAVO);
        assertEquals(0, tuner.seekCalls);
    }

    @Test public void emptySelectedListsDoNotSeekOrBorrowTheOtherList() throws Exception {
        for (boolean selectedFavorites : new boolean[]{true, false}) {
            favorites.replaceOrder(selectedFavorites
                    ? Collections.emptyList() : Arrays.asList(ALPHA, BRAVO));
            catalog.replaceOrder(selectedFavorites
                    ? Arrays.asList(ALPHA, CHARLIE) : Collections.emptyList());
            navigation.setFavoritesSelected(selectedFavorites);
            int before = tuner.tunes().size();
            controls().skipToNext();
            controls().skipToPrevious();
            controls().fastForward();
            controls().rewind();
            // These callbacks intentionally have no tuner call to await.
            SystemClock.sleep(250L);
            drainServiceCommands();
            assertEquals("An empty active list is a no-op", before, tuner.tunes().size());
            assertEquals("An empty list must not fall back to seek", 0, tuner.seekCalls);
            assertEquals("An empty list must not activate the OEM audio route", 0, tuner.routeRequests);
            assertEquals("An empty list must leave passive playback paused",
                    PlaybackState.STATE_PAUSED, controller.getPlaybackState().getState());
            assertActualStation(ALPHA);
            assertMetadataStation(ALPHA);
        }
    }

    @Test public void singleStationNavigationNeverPausesPlayback() throws Exception {
        favorites.replaceOrder(Collections.singletonList(ALPHA));
        catalog.replaceOrder(Collections.singletonList(ALPHA));
        expectTune(() -> controls().skipToNext(), ALPHA);
        observedPlaybackStates.clear();
        expectTune(() -> controls().skipToPrevious(), ALPHA);
        navigation.setFavoritesSelected(false);
        expectTune(() -> controls().fastForward(), ALPHA);
        expectTune(() -> controls().rewind(), ALPHA);
        assertEquals(PlaybackState.STATE_PLAYING, controller.getPlaybackState().getState());
        assertFalse("Station navigation must not toggle pause",
                observedPlaybackStates.contains(PlaybackState.STATE_PAUSED));
        assertEquals(0, tuner.seekCalls);
    }

    @Test public void mediaKeyEventsAndManifestReceiverUseTheCatalog() throws Exception {
        navigation.setFavoritesSelected(false);
        expectTune(() -> sendControllerPress(KeyEvent.KEYCODE_MEDIA_NEXT), CHARLIE);
        expectTune(() -> sendControllerPress(KeyEvent.KEYCODE_MEDIA_PREVIOUS), ALPHA);
        expectTune(() -> sendReceiverPress(KeyEvent.KEYCODE_MEDIA_NEXT), CHARLIE);
        expectTune(() -> sendReceiverPress(KeyEvent.KEYCODE_MEDIA_PREVIOUS), ALPHA);
        assertEquals(0, tuner.seekCalls);
    }

    @Test public void oemMediaButtonLettersNavigateTheCatalogThroughTheManifestReceiver()
            throws Exception {
        navigation.setFavoritesSelected(false);
        // The verified OEM receiver uses raw L (40) for previous and raw R
        // (46), with exactly META_SHIFT_ON, for next. Send genuine broadcast
        // extras; Android's MediaController may reject letter key codes itself.
        expectTune(() -> sendReceiverPress(KeyEvent.KEYCODE_R, KeyEvent.META_SHIFT_ON), CHARLIE);
        expectTune(() -> sendReceiverPress(KeyEvent.KEYCODE_L, 0), ALPHA);
        expectTune(() -> sendReceiverPress(KeyEvent.KEYCODE_L, 0), BRAVO); // previous wraps
        expectTune(() -> sendReceiverPress(KeyEvent.KEYCODE_R, KeyEvent.META_SHIFT_ON), ALPHA);
        assertEquals("OEM media aliases must navigate saved stations, never seek", 0, tuner.seekCalls);
    }

    @Test public void receiverIgnoresPlainRAndNonExactShiftRVariants() throws Exception {
        navigation.setFavoritesSelected(false);
        for (int metaState : new int[]{0, KeyEvent.META_ALT_ON,
                KeyEvent.META_SHIFT_ON | KeyEvent.META_ALT_ON}) {
            sendReceiverPress(KeyEvent.KEYCODE_R, metaState);
        }
        // Wait beyond the DOWN-only fallback too: ignored aliases must not
        // quietly leave behind a pending navigation command.
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 200L);
        drainServiceCommands();
        assertEquals("Plain R and Alt+R are not OEM next commands", 0, tuner.tunes().size());
        assertEquals(0, tuner.seekCalls);
        assertEquals("Ignored letter events must not activate radio audio", 0, tuner.routeRequests);
        assertEquals(PlaybackState.STATE_PAUSED, controller.getPlaybackState().getState());
        assertActualStation(ALPHA);
        assertMetadataStation(ALPHA);
    }

    @Test public void oemAliasAndCanonicalMediaKeyForTheSamePressAdvanceOnlyOnce()
            throws Exception {
        navigation.setFavoritesSelected(false);
        FavoriteStation[] expected = {CHARLIE, BRAVO};
        for (int pass = 0; pass < expected.length; pass++) {
            int before = tuner.tunes().size();
            long downTime = nextKeyTime();
            Runnable oemPress = () -> {
                sendReceiverKey(key(downTime, downTime, KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_R, 0, 0, KeyEvent.META_SHIFT_ON));
                sendReceiverKey(key(downTime, downTime + 1, KeyEvent.ACTION_UP,
                        KeyEvent.KEYCODE_R, 0, 0, KeyEvent.META_SHIFT_ON));
            };
            Runnable canonicalPress = () -> {
                // A second delivery can rewrite both keyCode and eventTime,
                // while downTime still identifies the same physical press.
                dispatchControllerKey(key(downTime, downTime + 10, KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_MEDIA_NEXT, 0, 0));
                dispatchControllerKey(key(downTime, downTime + 11, KeyEvent.ACTION_UP,
                        KeyEvent.KEYCODE_MEDIA_NEXT, 0, 0));
            };
            expectTune(pass == 0 ? oemPress : canonicalPress, expected[pass]);
            (pass == 0 ? canonicalPress : oemPress).run();
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 200L);
            drainServiceCommands();
            assertEquals("Normalize OEM and canonical keys before press deduplication",
                    before + 1, tuner.tunes().size());
            assertActualStation(expected[pass]);
            assertMetadataStation(expected[pass]);
        }
        assertEquals(0, tuner.seekCalls);
    }

    @Test public void longMediaPressAndDuplicateDeliveryAdvanceExactlyOneStation() throws Exception {
        navigation.setFavoritesSelected(false);
        int before = tuner.tunes().size();
        long downTime = nextKeyTime();
        KeyEvent down = key(downTime, downTime, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_MEDIA_NEXT, 0, 0);
        KeyEvent repeat = key(downTime, downTime + 50, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_MEDIA_NEXT, 1, KeyEvent.FLAG_LONG_PRESS);
        KeyEvent secondRepeat = key(downTime, downTime + 70, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_MEDIA_NEXT, 2, 0);
        KeyEvent up = key(downTime, downTime + 90, KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_MEDIA_NEXT, 0, 0);
        dispatchControllerKey(down);
        dispatchControllerKey(repeat);
        dispatchControllerKey(secondRepeat);
        dispatchControllerKey(up);
        await(() -> tuner.tunes().size() > before, "Long media press did not reach the tuner");
        // A foreground activity and receiver may forward the same physical
        // events after the MediaSession has already handled them.
        sendReceiverKey(down);
        sendReceiverKey(repeat);
        sendReceiverKey(secondRepeat);
        sendReceiverKey(up);
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 200L);
        drainServiceCommands();
        assertEquals("Repeats, release and duplicate delivery must not tune twice",
                before + 1, tuner.tunes().size());
        assertActualStation(CHARLIE);
        assertMetadataStation(CHARLIE);
        assertEquals("Long press must not perform a frequency seek", 0, tuner.seekCalls);
    }

    @Test public void splitAliasEdgesAcrossReceiverAndMediaSessionAdvanceOnlyOnce() throws Exception {
        navigation.setFavoritesSelected(false);
        FavoriteStation[] ordered = {ALPHA, CHARLIE, BRAVO};
        int position = 0;
        int[] aliases = {KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, 272, 274,
                KeyEvent.KEYCODE_MEDIA_REWIND, 273, 275};
        for (int index = 0; index < aliases.length; index++) {
            boolean next = index < 3;
            int alias = aliases[index];
            int canonical = next ? KeyEvent.KEYCODE_MEDIA_NEXT : KeyEvent.KEYCODE_MEDIA_PREVIOUS;
            for (boolean aliasDown : new boolean[]{true, false}) {
                long down = nextKeyTime();
                position = (position + (next ? 1 : 2)) % ordered.length;
                // A single press can change its code and delivery channel
                // between its edges. Its DOWN fallback must be consumed too.
                expectOnlyTuneAfterFallback(() -> {
                    if (aliasDown) {
                        sendReceiverKey(key(down, down, KeyEvent.ACTION_DOWN, alias, 0, 0));
                        dispatchControllerKey(key(down, down + 1L, KeyEvent.ACTION_UP, canonical, 0, 0));
                    } else {
                        dispatchControllerKey(key(down, down, KeyEvent.ACTION_DOWN, canonical, 0, 0));
                        sendReceiverKey(key(down, down + 1L, KeyEvent.ACTION_UP, alias, 0, 0));
                    }
                }, ordered[position]);
            }
        }
    }

    @Test public void completeAliasCopiesAcrossChannelsAdvanceOnlyOnce() throws Exception {
        navigation.setFavoritesSelected(false);
        FavoriteStation[] ordered = {ALPHA, CHARLIE, BRAVO};
        int position = 0;
        int[] aliases = {KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, 272, 274,
                KeyEvent.KEYCODE_MEDIA_REWIND, 273, 275};
        for (int index = 0; index < aliases.length; index++) {
            boolean next = index < 3;
            int alias = aliases[index];
            int canonical = next ? KeyEvent.KEYCODE_MEDIA_NEXT : KeyEvent.KEYCODE_MEDIA_PREVIOUS;
            boolean receiverFirst = index % 2 == 0;
            long down = nextKeyTime();
            position = (position + (next ? 1 : 2)) % ordered.length;
            Runnable receiverCopy = () -> {
                sendReceiverKey(key(down, down, KeyEvent.ACTION_DOWN, alias, 0, 0));
                sendReceiverKey(key(down, down + 1L, KeyEvent.ACTION_UP, alias, 0, 0));
            };
            Runnable sessionCopy = () -> {
                // Different eventTime defeats exact-event deduplication;
                // these still have one physical downTime identity.
                dispatchControllerKey(key(down, down + 10L, KeyEvent.ACTION_DOWN, canonical, 0, 0));
                dispatchControllerKey(key(down, down + 11L, KeyEvent.ACTION_UP, canonical, 0, 0));
            };
            expectOnlyTuneAfterFallback(() -> {
                (receiverFirst ? receiverCopy : sessionCopy).run();
                (receiverFirst ? sessionCopy : receiverCopy).run();
            }, ordered[position]);
        }
    }

    @Test public void rewrittenReleaseIdentityAndLateCopiesAdvanceOnlyOnce() throws Exception {
        navigation.setFavoritesSelected(false);
        FavoriteStation[] ordered = {ALPHA, CHARLIE, BRAVO};
        int position = 0;
        for (boolean next : new boolean[]{true, false}) {
            int alias = next ? 274 : 275;
            int canonical = next ? KeyEvent.KEYCODE_MEDIA_NEXT : KeyEvent.KEYCODE_MEDIA_PREVIOUS;
            for (boolean receiverDown : new boolean[]{true, false}) {
                long down = nextKeyTime();
                long rewrittenDown = down + 25L;
                position = (position + (next ? 1 : 2)) % ordered.length;
                expectOnlyTuneAfterFallback(() -> {
                    if (receiverDown) {
                        sendReceiverKey(key(down, down, KeyEvent.ACTION_DOWN, alias, 0, 0));
                        dispatchControllerKey(key(rewrittenDown, rewrittenDown + 1L,
                                KeyEvent.ACTION_UP, canonical, 0, 0));
                    } else {
                        dispatchControllerKey(key(down, down, KeyEvent.ACTION_DOWN, canonical, 0, 0));
                        sendReceiverKey(key(rewrittenDown, rewrittenDown + 1L,
                                KeyEvent.ACTION_UP, alias, 0, 0));
                    }
                    // The sole open same-direction press can reconcile a
                    // reconstructed release. Preserve BOTH identities so
                    // delayed copies of either release cannot tune again.
                    sendReceiverKey(key(down, rewrittenDown + 10L, KeyEvent.ACTION_UP, alias, 0, 0));
                    dispatchControllerKey(key(rewrittenDown, rewrittenDown + 20L,
                            KeyEvent.ACTION_UP, canonical, 0, 0));
                }, ordered[position]);
            }
        }
    }

    @Test public void rewrittenHeldRepeatCannotLeaveASecondFallback() throws Exception {
        navigation.setFavoritesSelected(false);
        for (boolean next : new boolean[]{true, false}) {
            int alias = next ? 274 : 275;
            int canonical = next ? KeyEvent.KEYCODE_MEDIA_NEXT : KeyEvent.KEYCODE_MEDIA_PREVIOUS;
            long down = nextKeyTime();
            long rewrittenDown = down + 20L;
            expectOnlyTuneAfterFallback(() -> {
                sendReceiverKey(key(down, down, KeyEvent.ACTION_DOWN, alias, 0, 0));
                dispatchControllerKey(key(rewrittenDown, rewrittenDown + 1L,
                        KeyEvent.ACTION_DOWN, canonical, 1, KeyEvent.FLAG_LONG_PRESS));
                dispatchControllerKey(key(rewrittenDown, rewrittenDown + 10L,
                        KeyEvent.ACTION_DOWN, canonical, 2, 0));
                dispatchControllerKey(key(rewrittenDown, rewrittenDown + 20L,
                        KeyEvent.ACTION_UP, canonical, 0, 0));
                sendReceiverKey(key(down, rewrittenDown + 30L, KeyEvent.ACTION_UP, alias, 0, 0));
            }, next ? CHARLIE : ALPHA);
        }
    }

    @Test public void rapidDistinctPressesAndDirectionReversalAreNeverDebounced() throws Exception {
        navigation.setFavoritesSelected(false);
        long down = nextKeyTime();
        // Three legitimate complete press identities only 5 ms apart. A
        // same-direction time throttle would incorrectly drop the second tap;
        // a direction-independent throttle would also lose the reversal.
        dispatchControllerKey(key(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, 0, 0));
        dispatchControllerKey(key(down, down + 1L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT, 0, 0));
        dispatchControllerKey(key(down + 5L, down + 5L,
                KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, 0, 0));
        dispatchControllerKey(key(down + 5L, down + 6L,
                KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT, 0, 0));
        dispatchControllerKey(key(down + 10L, down + 10L,
                KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS, 0, 0));
        dispatchControllerKey(key(down + 10L, down + 11L,
                KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS, 0, 0));
        await(() -> tuner.tunes().size() >= 3, "Three real rapid presses did not reach the tuner");
        waitBeyondMediaKeyFallback();
        assertEquals("Preserve every rapid tap and its order",
                Arrays.asList(CHARLIE, BRAVO, CHARLIE), tuner.tunes());
        assertActualStation(CHARLIE);
        assertMetadataStation(CHARLIE);
        assertEquals(0, tuner.seekCalls);
    }

    @Test public void rejectedHardwareTuneNeverPublishesTheRequestedStation() throws Exception {
        favorites.replaceOrder(Arrays.asList(ALPHA, DELTA));
        drainServiceCommands();
        observedMediaIds.clear();
        tuner.rejectTunes = true;
        int routesBefore = tuner.routeRequests;
        controls().skipToNext();
        await(() -> tuner.tunes().size() == RadioTuneConfirmation.MAX_ATTEMPTS,
                "The rejected frequency was not retried through the OEM tuner API");
        drainServiceCommands();
        for (FavoriteStation attempt : tuner.tunes()) {
            assertEquals("The actual command must target the adjacent station", DELTA, attempt);
        }
        assertActualStation(ALPHA);
        assertMetadataStation(ALPHA);
        assertFalse("Never publish unconfirmed target metadata",
                observedMediaIds.contains(MediaStationId.encode(DELTA)));
        assertEquals("Do not execute the post-confirmation route request after a failed tune",
                routesBefore + 1, tuner.routeRequests);
        assertEquals(0, tuner.seekCalls);
    }

    private void connectBrowser() throws Exception {
        CountDownLatch connected = new CountDownLatch(1);
        List<String> errors = new CopyOnWriteArrayList<>();
        instrumentation.runOnMainSync(() -> {
            browser = new MediaBrowser(context, new ComponentName(context, RadioPlaybackService.class),
                    new MediaBrowser.ConnectionCallback() {
                        @Override public void onConnected() {
                            service = (RadioPlaybackService) field(
                                    RadioPlaybackService.class, "runningInstance", null);
                            if (service == null) {
                                errors.add("MediaBrowser connected without a live service");
                            } else {
                                // Browsing does not attach FMPlugService. Only this
                                // private dependency is replaced, on its owner thread.
                                setField(service, "radio", tuner.api);
                                controller = new MediaController(context, browser.getSessionToken());
                                controller.registerCallback(observer, new Handler(Looper.getMainLooper()));
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
        assertTrue("Timed out connecting to the real media service",
                connected.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertTrue(errors.toString(), errors.isEmpty());
        assertNotNull(controller);
        FavoriteStation actual = tuner.current();
        await(() -> metadataMatches(actual), "The service did not publish the tuner-reported frequency");
        drainServiceCommands();
    }

    private void disconnectBrowserAndStop() throws Exception {
        if (!ownsService || context == null) return;
        instrumentation.runOnMainSync(() -> {
            if (controller != null) controller.unregisterCallback(observer);
            if (browser != null) browser.disconnect();
            context.stopService(new Intent(context, RadioPlaybackService.class));
        });
        await(() -> field(RadioPlaybackService.class, "runningInstance", null) == null,
                "Test media service did not stop");
        browser = null;
        controller = null;
        service = null;
        ownsService = false;
    }

    private MediaController.TransportControls controls() {
        return controller.getTransportControls();
    }

    private void expectTune(Runnable action, FavoriteStation expected) throws Exception {
        int before = tuner.tunes().size();
        action.run();
        await(() -> tuner.tunes().size() > before, "Media command did not reach gotoFreq");
        drainServiceCommands();
        assertEquals("One command must select exactly one stored station",
                before + 1, tuner.tunes().size());
        assertEquals("Check the band and frequency actually passed to the tuner",
                expected, tuner.tunes().get(before));
        assertActualStation(expected);
        await(() -> metadataMatches(expected), "Media metadata did not follow the confirmed tuner state");
        assertMetadataStation(expected);
    }

    private long routingClaimId() {
        return (Long) field(RadioPlaybackService.class, "routingClaimId", service);
    }

    private void awaitRoutingClaimCompleted(long previousClaim, int expectedRoutes) throws Exception {
        await(() -> routingClaimId() > previousClaim
                        && "completed".equals(field(RadioPlaybackService.class, "routingPulseStatus", service))
                        && tuner.routeRequests >= expectedRoutes,
                "A new playback takeover must finish its routing pulse and OEM restoration");
        drainServiceCommands();
        assertEquals("Takeover route, confirmed-tune route and one post-pulse restore",
                expectedRoutes, tuner.routeRequests);
    }

    private void expectSteadyAdjacentTune(Runnable action, FavoriteStation expected) throws Exception {
        long claimBefore = routingClaimId();
        int focusBefore = tuner.focusRequests;
        int releasesBefore = tuner.focusReleases;
        int routesBefore = tuner.routeRequests;
        expectTune(action, expected);
        SystemClock.sleep(1_650L);
        drainServiceCommands();
        assertEquals("An adjacent station must reuse the established playback claim",
                claimBefore, routingClaimId());
        assertEquals("Adjacent navigation must not request OEM focus again", focusBefore, tuner.focusRequests);
        assertEquals("Adjacent navigation must not release OEM focus", releasesBefore, tuner.focusReleases);
        assertEquals("Keep only the immediate confirmed-tune route request, never a delayed restore",
                routesBefore + 1, tuner.routeRequests);
        assertActualStation(expected);
        assertMetadataStation(expected);
        assertEquals(0, tuner.seekCalls);
    }

    private void expectOnlyTuneAfterFallback(Runnable action, FavoriteStation expected) throws Exception {
        int before = tuner.tunes().size();
        action.run();
        await(() -> tuner.tunes().size() > before, "Media press did not reach gotoFreq");
        waitBeyondMediaKeyFallback();
        assertEquals("One physical press must remain one tune after the DOWN-only fallback",
                before + 1, tuner.tunes().size());
        assertEquals(expected, tuner.tunes().get(before));
        assertActualStation(expected);
        assertMetadataStation(expected);
        assertEquals("Aliases and repeated edges must never seek", 0, tuner.seekCalls);
    }

    private void waitBeyondMediaKeyFallback() throws Exception {
        SystemClock.sleep(Math.max(700L, ViewConfiguration.getLongPressTimeout() + 200L));
        drainServiceCommands();
    }

    private void assertActualStation(FavoriteStation expected) {
        assertEquals("OEM-reported band", expected.band, tuner.current().band);
        assertEquals("OEM-reported frequency", expected.frequency, tuner.current().frequency);
    }

    private void assertMetadataStation(FavoriteStation expected) {
        assertTrue("Metadata must represent " + expected.key(), metadataMatches(expected));
    }

    private boolean metadataMatches(FavoriteStation expected) {
        MediaMetadata metadata = controller.getMetadata();
        return metadata != null && MediaStationId.encode(expected).equals(
                metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID));
    }

    private void drainServiceCommands() throws Exception {
        // Main-thread callbacks enqueue OEM work; that executor posts metadata
        // back to main. Drain both directions, without calling any command handler.
        for (int pass = 0; pass < 3; pass++) {
            instrumentation.waitForIdleSync();
            ExecutorService executor = (ExecutorService) field(
                    RadioPlaybackService.class, "executor", service);
            executor.submit(() -> {}).get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
        instrumentation.waitForIdleSync();
    }

    private void sendControllerPress(int keyCode) {
        long down = nextKeyTime();
        dispatchControllerKey(key(down, down, KeyEvent.ACTION_DOWN, keyCode, 0, 0));
        dispatchControllerKey(key(down, down + 1, KeyEvent.ACTION_UP, keyCode, 0, 0));
    }

    private void dispatchControllerKey(KeyEvent event) {
        assertTrue("The MediaSession must accept the media key",
                controller.dispatchMediaButtonEvent(event));
    }

    private void sendReceiverPress(int keyCode) {
        sendReceiverPress(keyCode, 0);
    }

    private void sendReceiverPress(int keyCode, int metaState) {
        long down = nextKeyTime();
        sendReceiverKey(key(down, down, KeyEvent.ACTION_DOWN, keyCode, 0, 0, metaState));
        sendReceiverKey(key(down, down + 1, KeyEvent.ACTION_UP, keyCode, 0, 0, metaState));
    }

    private void sendReceiverKey(KeyEvent event) {
        CountDownLatch delivered = new CountDownLatch(1);
        Intent intent = new Intent(context, RadioMediaButtonReceiver.class)
                .setAction(Intent.ACTION_MEDIA_BUTTON)
                .putExtra(Intent.EXTRA_KEY_EVENT, event);
        context.sendOrderedBroadcast(intent, null, new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent received) {
                delivered.countDown();
            }
        }, new Handler(Looper.getMainLooper()), 0, null, null);
        try {
            assertTrue("Manifest media-button broadcast was not delivered",
                    delivered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    private long nextKeyTime() {
        lastKeyTime = Math.max(SystemClock.uptimeMillis(), lastKeyTime + 100L);
        return lastKeyTime;
    }

    private static KeyEvent key(long down, long time, int action, int code, int repeat, int flags) {
        return key(down, time, action, code, repeat, flags, 0);
    }

    private static KeyEvent key(long down, long time, int action, int code, int repeat, int flags,
            int metaState) {
        return new KeyEvent(down, time, action, code, repeat, metaState, -1, 0, flags);
    }

    private static void await(BooleanSupplier condition, String message) {
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(20L);
        }
        assertTrue(message, condition.getAsBoolean());
    }

    private static Object field(Class<?> type, String name, Object target) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(name, error);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(name, error);
        }
    }

    private static boolean hasVendorFramework() {
        for (String name : new String[]{"android.radio.RadioPlayer", "android.sourceservice.SourceInfo"}) {
            try {
                Class.forName(name);
                return true;
            } catch (ClassNotFoundException ignored) {
                // Stock Android emulators do not include either OEM bridge.
            }
        }
        return false;
    }

    private void restorePreferences(String name, Map<String, ?> values) {
        SharedPreferences.Editor editor = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit().clear();
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
        assertTrue("Restore preferences: " + name, editor.commit());
    }

    private static final class FakeTuner implements InvocationHandler {
        private final Binder binder = new Binder();
        private final List<FavoriteStation> requestedTunes = new ArrayList<>();
        private int band;
        private int frequency;
        volatile int seekCalls;
        volatile int routeRequests;
        volatile int focusRequests;
        volatile int focusReleases;
        volatile boolean rejectTunes;
        final IRadioServiceAPI api;

        FakeTuner(FavoriteStation initial) {
            band = initial.band;
            frequency = initial.frequency;
            api = (IRadioServiceAPI) Proxy.newProxyInstance(IRadioServiceAPI.class.getClassLoader(),
                    new Class<?>[]{IRadioServiceAPI.class}, this);
        }

        synchronized FavoriteStation current() {
            return new FavoriteStation(band, frequency, "");
        }

        synchronized List<FavoriteStation> tunes() {
            return new ArrayList<>(requestedTunes);
        }

        @Override public synchronized Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "asBinder": return binder;
                case "getCurrentBand": return band;
                case "getCurrentFreq": return frequency;
                case "getCurrentFreqRdsPs": return "";
                case "gotoFreq":
                    int requested = (Integer) args[0];
                    requestedTunes.add(new FavoriteStation(band, requested, ""));
                    if (!rejectTunes) frequency = requested;
                    return null;
                case "onBandEvent":
                    band = (band + 1) % 4;
                    return null;
                case "onSeekDownEvent":
                case "onSeekUpEvent":
                    seekCalls++;
                    return null;
                case "requestPlayAudio":
                    routeRequests++;
                    return true;
                case "requestAudioFocus":
                    focusRequests++;
                    return null;
                case "releaseAudioFocus":
                    focusReleases++;
                    return null;
                case "toString": return "MediaControlsTest.FakeTuner";
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
