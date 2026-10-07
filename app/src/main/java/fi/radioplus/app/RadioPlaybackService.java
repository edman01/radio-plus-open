package fi.radioplus.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser.MediaItem;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.net.Uri;
import android.service.media.MediaBrowserService;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ViewConfiguration;

import com.hcn.autoradio.IRadioServiceAPI;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Keeps the stock radio tuner service alive while Radio+ is playing in the
 * background. The stock process owns the analog FM audio route in the ROM.
 */
public final class RadioPlaybackService extends MediaBrowserService {
    private static final String TAG = "RadioBackground";
    private static final String CHANNEL_ID = "radio_playback";
    private static final int NOTIFICATION_ID = 701;
    private static final long MIN_REBIND_DELAY_MS = 1500L;
    private static final long MAX_REBIND_DELAY_MS = 30_000L;
    private static final long OEM_BIND_TIMEOUT_MS = 6000L;
    private static final long MEDIA_LONG_PRESS_GRACE_MS = 100L;
    private static final long METADATA_REFRESH_MS = 1500L;
    private static final long PAUSE_FOCUS_HANDOFF_MS = 650L;
    private static final long OEM_GAIN_SETTLE_MS = 650L;
    // Junsun's framework exposes these as McuConstant.K_STEP_FORWARD (274)
    // and K_STEP_BACKWARD (275). They are not Android DPAD directions.
    private static final int KEYCODE_JUNSUN_TUNER_NEXT = 274;
    private static final int KEYCODE_JUNSUN_TUNER_PREVIOUS = 275;
    private static final int KEYCODE_JUNSUN_SKIP_NEXT = 272;
    private static final int KEYCODE_JUNSUN_SKIP_PREVIOUS = 273;
    private static final long TUNE_CONFIRM_DELAY_MS = 140L;
    private static final int MEDIA_ARTWORK_SIZE_PX = 320;
    private static final String MEDIA_ROOT_ID = "radio_plus";
    private static final String MEDIA_FAVORITES_ID = "radio_plus:favorites";
    private static final String MEDIA_STATIONS_ID = "radio_plus:stations";
    private static final String ACTION_STOP =
            "fi.radioplus.app.action.STOP_BACKGROUND_PLAYBACK";
    private static final String ACTION_PAUSE_PLAYBACK =
            "fi.radioplus.app.action.PAUSE_PLAYBACK";
    private static final String ACTION_MEDIA_PREVIOUS =
            "fi.radioplus.app.action.MEDIA_PREVIOUS";
    private static final String ACTION_MEDIA_TOGGLE =
            "fi.radioplus.app.action.MEDIA_TOGGLE";
    private static final String ACTION_MEDIA_NEXT =
            "fi.radioplus.app.action.MEDIA_NEXT";
    private static final String ACTION_TAKE_OVER_PLAYBACK =
            "fi.radioplus.app.action.TAKE_OVER_PLAYBACK";
    static final String ACTION_TUNE_STATION =
            "fi.radioplus.app.action.TUNE_WIDGET_STATION";
    static final String ACTION_TUNE_UI_STATION =
            "fi.radioplus.app.action.TUNE_UI_STATION";
    static final String EXTRA_STATION_BAND =
            "fi.radioplus.app.extra.STATION_BAND";
    static final String EXTRA_STATION_FREQUENCY =
            "fi.radioplus.app.extra.STATION_FREQUENCY";
    static final String EXTRA_STATION_NAME =
            "fi.radioplus.app.extra.STATION_NAME";
    private static volatile RadioPlaybackService runningInstance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean bound;
    private volatile boolean stopping;
    private boolean foregroundStarted;
    // A MediaBrowser client (for example the Junsun launcher) may bind this
    // service without asking the radio to play. Never acquire the vendor's
    // global audio focus until an explicit app, widget or media-key command.
    private volatile boolean playbackRequested;
    // Invalidate queued/delayed work on pause/resume, not on each consecutive
    // Next press: every press in one playback epoch must advance one station.
    private volatile long playbackEpoch;
    private volatile long playbackRequestRevision;
    private volatile PendingNwdPause pendingNwdPause;
    private boolean stopAfterNwdPause;
    // Accessed by serialized tuner commands; pause/resume changes playbackEpoch.
    private volatile long activatedPlaybackEpoch = -1L;
    private volatile boolean uiTakeoverRequired;
    private volatile long routingClaimId;
    private final PlaybackOwnershipPolicy playbackOwnership = new PlaybackOwnershipPolicy();
    private Runnable pendingRoutingClaim;
    private volatile String routingPulseStatus = "not-requested";
    // FMPlugService acquires its own singleton AudioFocusRequest in onCreate().
    // Track only a focus release explicitly requested through Radio+'s pause
    // control; never pretend that Radio+ owns the vendor service's request.
    private final OemFocusSessionState oemFocusState = new OemFocusSessionState();
    private volatile boolean oemRouteActive;
    private final MediaKeyPressTracker mediaKeyPressTracker =
            new MediaKeyPressTracker();
    private final MediaKeyEventDeduplicator mediaKeyEvents = new MediaKeyEventDeduplicator();
    private volatile IRadioServiceAPI radio;
    private IBinder radioBinder;
    private boolean inspectingBackend;
    private RadioApiFactory.Detection boundDetection;
    private int backendGeneration;
    private long rebindDelayMs = MIN_REBIND_DELAY_MS;
    private FavoriteStore favoriteStore;
    private StationStore stationStore;
    private StationNavigationStore stationNavigationStore;
    private volatile FavoriteStation pendingWidgetStation;
    private boolean pendingStationReusesPlayback;
    private boolean pendingStationPreviousPlaybackRequested;
    private long pendingStationPlaybackRevision;
    private RadioCommand pendingRadioCommand;
    private String pendingRadioCommandName = "";
    private MediaSession mediaSession;
    private int publishedMediaBand = -1;
    private int publishedMediaFrequency = -1;
    private String publishedMediaName = "";
    private String publishedRadioText = "";
    private String publishedLogo = "";
    private final RadioPlaybackHealthReader playbackHealthReader =
            new RadioPlaybackHealthReader();
    private AudioManager audioManager;
    private AudioFocusRequest pauseFocusRequest;
    private boolean pauseFocusHeld;
    private final AudioManager.OnAudioFocusChangeListener pauseFocusListener =
            focusChange -> {
                // Radio+ does not render PCM audio. This short-lived request is
                // used only to make FMPlugService execute its stock
                // AUDIOFOCUS_LOSS -> setMute(true) path.
            };
    private final RadioMetadataReader tunerMetadataReader =
            new RadioMetadataReader();
    private RadioVolumeGuard radioVolumeGuard;
    private BroadcastReceiver accWakeReceiver;
    private long screenOffAtElapsed = -1L;

    private final Runnable rebindTask = this::bindOemRadio;
    private final Runnable startupVolumePolicyTask =
            this::reassertRadioVolumePolicy;
    private final Runnable oemBindWatchdogTask = () -> {
        if (stopping || !bound || radio != null) {
            return;
        }
        Log.w(TAG, "Stock radio bind timed out; rebuilding the binding");
        releaseBinding();
        scheduleRebind();
    };
    private final Runnable metadataRefreshTask = new Runnable() {
        @Override
        public void run() {
            resolvePendingNwdPause();
            refreshMediaMetadataFromRadio();
            reconcilePlaybackOwnership();
            applyRadioVolumePolicy();
            if (!stopping) {
                mainHandler.postDelayed(this, METADATA_REFRESH_MS);
            }
        }
    };
    private final IBinder.DeathRecipient radioDeathRecipient = () ->
            mainHandler.post(() -> handleOemConnectionLoss("binder died"));
    private final Runnable pendingShortMediaTask = () -> {
        handleTrackedMediaDecision(mediaKeyPressTracker.onFallback(
                SystemClock.elapsedRealtime(), mediaKeyFallbackTimeout()
        ));
        scheduleShortMediaKey();
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (stopping) {
                return;
            }
            mainHandler.removeCallbacks(rebindTask);
            mainHandler.removeCallbacks(oemBindWatchdogTask);
            rebindDelayMs = MIN_REBIND_DELAY_MS;
            radioBinder = binder;
            radio = null;
            oemRouteActive = false;
            oemFocusState.onServiceConnected();
            try {
                binder.linkToDeath(radioDeathRecipient, 0);
            } catch (RemoteException error) {
                handleOemConnectionLoss("binder died while connecting");
                return;
            }
            int generation = ++backendGeneration;
            RadioApiFactory.resolve(RadioPlaybackService.this, boundDetection, binder, (api, detection) -> {
                if (stopping || !bound || radioBinder != binder || generation != backendGeneration) return;
                if (api == null) {
                    Log.w(TAG, "Stock radio contract not recognized; no control calls sent");
                    setPlaybackRequested(false);
                    return;
                }
                radio = api;
                if (shouldIgnoreRawMediaKeys()) resetRawMediaKeyDecisions();
                Log.i(TAG, "Selected stock radio profile: " + detection.profile.label);
                onRecognizedRadioConnected();
            });
        }

        private void onRecognizedRadioConnected() {
            if (pendingWidgetStation != null) {
                tunePendingWidgetStation();
            } else if (pendingRadioCommand != null) {
                executeRadioCommand(pendingRadioCommandName, pendingRadioCommand);
            } else if (playbackRequested) {
                requestPlayback(false);
            } else {
                // A launcher or MediaBrowser client may bind for metadata only.
                // FMPlugService owns the global radio focus, so a passive bind
                // must never release it.
                Log.i(TAG, "Stock radio bound without a playback request");
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            handleOemConnectionLoss("service disconnected");
        }

        @Override
        public void onBindingDied(ComponentName name) {
            handleOemConnectionLoss("binding died");
        }

        @Override
        public void onNullBinding(ComponentName name) {
            handleOemConnectionLoss("null binding");
        }
    };

    static void ensureRunning(Context context) {
        Intent service = new Intent(context, RadioPlaybackService.class)
                .setAction(ACTION_TAKE_OVER_PLAYBACK);
        context.startForegroundService(service);
    }

    static void ensureRunningForUi(Context context) {
        RadioPlaybackService current = runningInstance;
        // Reopening the screen is not a Play command. Preserve both ongoing
        // radio playback and an intentional pause without another focus/PCM
        // handoff. A cold launch retains the normal radio startup behavior.
        if (current == null || current.stopping) ensureRunning(context);
    }

    static void tuneStation(Context context, FavoriteStation station) {
        if (station == null
                || !FrequencyRules.isValid(station.band, station.frequency)) {
            return;
        }
        RadioPlaybackService current = runningInstance;
        if (current != null && !current.stopping) {
            current.mainHandler.post(() -> current.queueStation(station, !current.uiTakeoverRequired));
            return;
        }
        Intent service = new Intent(context, RadioPlaybackService.class);
        service.setAction(ACTION_TUNE_UI_STATION);
        service.putExtra(EXTRA_STATION_BAND, station.band);
        service.putExtra(EXTRA_STATION_FREQUENCY, station.frequency);
        service.putExtra(EXTRA_STATION_NAME, station.name);
        context.startForegroundService(service);
    }

    static void noteUiBackgrounded() {
        RadioPlaybackService current = runningInstance;
        if (current != null && !current.stopping) current.uiTakeoverRequired = true;
    }

    static void pause(Context context) {
        Intent service = new Intent(context, RadioPlaybackService.class)
                .setAction(ACTION_PAUSE_PLAYBACK);
        context.startForegroundService(service);
    }

    static void skipStation(Context context, boolean next) {
        context.startForegroundService(new Intent(context, RadioPlaybackService.class)
                .setAction(next ? ACTION_MEDIA_NEXT : ACTION_MEDIA_PREVIOUS));
    }

    static boolean isPlaybackRequested() {
        RadioPlaybackService current = runningInstance;
        return current != null && !current.stopping && current.playbackRequested;
    }

    /** Read on the UI thread; local state does not prove system media-key ownership. */
    static String steeringDiagnosticStatus() {
        RadioPlaybackService current = runningInstance;
        if (current == null || current.stopping) return "service=false";
        return "service=true session=" + (current.mediaSession != null && current.mediaSession.isActive())
                + "\nplayRequested=" + current.playbackRequested
                + " oemConnected=" + (current.radio != null)
                + " routeReported=" + current.oemRouteActive
                + "\nroutingPulse=" + current.routingPulseStatus;
    }

    static void publishMediaState(
            int band,
            int frequency,
            String stationName,
            String radioText
    ) {
        RadioPlaybackService current = runningInstance;
        if (current == null || current.stopping) {
            return;
        }
        current.mainHandler.post(() -> current.updateMediaMetadata(
                band,
                frequency,
                stationName,
                radioText
        ));
    }

    static void notifyMediaLibraryChanged() {
        RadioPlaybackService current = runningInstance;
        if (current == null || current.stopping) {
            return;
        }
        current.mainHandler.post(current::notifyMediaLibraryChangedInternal);
    }

    static void refreshLocalizedText() {
        RadioPlaybackService current = runningInstance;
        if (current == null || current.stopping) {
            return;
        }
        current.mainHandler.post(() -> {
            current.createNotificationChannel();
            current.refreshNotification();
            current.notifyMediaLibraryChangedInternal();
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        runningInstance = this;
        favoriteStore = new FavoriteStore(this);
        stationStore = new StationStore(this);
        stationNavigationStore = new StationNavigationStore(this);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        radioVolumeGuard = new RadioVolumeGuard();
        registerAccWakeMonitor();
        createNotificationChannel();
        createMediaSession();
        // Passive startup must never create a PCM route. Internal dev builds
        // register one finite media-routing pulse only after explicit playback,
        // then release it before restoring the OEM analog route.
        Log.i(TAG, "MediaSession created without taking audio ownership");
        startForeground(NOTIFICATION_ID, createNotification());
        foregroundStarted = true;
        // Bluetooth, the launcher and System UI may bind only to browse the
        // media library. Do not wake or attach the stock tuner for that passive
        // bind; onStartCommand and explicit station/media commands bind it when
        // actual playback has been requested.
        mainHandler.post(metadataRefreshTask);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // START_STICKY used to restart the process with a null Intent and the
        // old default playbackRequested=true. That could seize or reset the
        // stock radio's audio long after the user had left Radio+. A killed
        // foreground service is now resumed only by an explicit app, widget
        // or steering-wheel command.
        if (intent == null) {
            return START_NOT_STICKY;
        }
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            pausePlayback();
            if (pendingNwdPause != null) {
                // Keep observing our unresolved MCU start until it can be
                // canceled safely; stopping now would discard that pause intent.
                stopAfterNwdPause = true;
                return START_NOT_STICKY;
            }
            stopping = true;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_PAUSE_PLAYBACK.equals(action)) {
            pausePlayback();
            return START_NOT_STICKY;
        }
        if (Intent.ACTION_MEDIA_BUTTON.equals(action)) {
            KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            handleMediaKeyEvent(event);
            return START_NOT_STICKY;
        }
        if (ACTION_MEDIA_PREVIOUS.equals(action)) {
            SteeringDiagnosticTrace.get().event("service-intent", "previous");
            selectAdjacentStation(false);
            return START_NOT_STICKY;
        }
        if (ACTION_MEDIA_TOGGLE.equals(action)) {
            togglePlayback();
            return START_NOT_STICKY;
        }
        if (ACTION_MEDIA_NEXT.equals(action)) {
            SteeringDiagnosticTrace.get().event("service-intent", "next");
            selectAdjacentStation(true);
            return START_NOT_STICKY;
        }
        if (ACTION_TUNE_UI_STATION.equals(action)) {
            queueWidgetStation(intent, !uiTakeoverRequired);
            return START_NOT_STICKY;
        }
        if (ACTION_TUNE_STATION.equals(action)) {
            queueWidgetStation(intent);
            return START_NOT_STICKY;
        }
        if (ACTION_TAKE_OVER_PLAYBACK.equals(action)) {
            requestPlayback(true);
            return START_NOT_STICKY;
        }

        setPlaybackRequested(true);
        if (!bound && !stopping) {
            bindOemRadio();
        } else if (radio != null) {
            requestPlayback(false);
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }

    @Override
    public void onDestroy() {
        stopping = true;
        pendingNwdPause = null;
        stopAfterNwdPause = false;
        playbackEpoch++;
        cancelRoutingClaim();
        if (runningInstance == this) {
            runningInstance = null;
        }
        foregroundStarted = false;
        mainHandler.removeCallbacksAndMessages(null);
        mediaKeyPressTracker.reset();
        abandonPauseAudioFocus();
        unregisterAccWakeMonitor();
        if (radioVolumeGuard != null) {
            radioVolumeGuard.stop();
            radioVolumeGuard = null;
        }
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        releaseBinding();
        executor.shutdownNow();
        super.onDestroy();
    }

    private void registerAccWakeMonitor() {
        if (accWakeReceiver != null) {
            return;
        }
        accWakeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent == null ? null : intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    screenOffAtElapsed = SystemClock.elapsedRealtime();
                    return;
                }
                if (!Intent.ACTION_SCREEN_ON.equals(action)
                        || !AutoStartPreferences.isEnabled(context)) {
                    return;
                }
                long now = SystemClock.elapsedRealtime();
                if (AutoStartLaunchPolicy.isWakeAfterSleep(screenOffAtElapsed, now)) {
                    AutoStartLauncher.launch(context, "ACC/screen wake");
                }
                screenOffAtElapsed = -1L;
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(accWakeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(accWakeReceiver, filter);
        }
    }

    private void unregisterAccWakeMonitor() {
        if (accWakeReceiver == null) {
            return;
        }
        try {
            unregisterReceiver(accWakeReceiver);
        } catch (IllegalArgumentException ignored) {
            // The process may already have detached the receiver.
        }
        accWakeReceiver = null;
    }

    @Override
    public BrowserRoot onGetRoot(
            String clientPackageName,
            int clientUid,
            Bundle rootHints
    ) {
        return new BrowserRoot(MEDIA_ROOT_ID, null);
    }

    @Override
    public void onLoadChildren(String parentId, Result<List<MediaItem>> result) {
        if (MEDIA_ROOT_ID.equals(parentId)) {
            ArrayList<MediaItem> roots = new ArrayList<>();
            roots.add(createBrowsableItem(
                    MEDIA_FAVORITES_ID,
                    tr("Suosikit", "Favorites"),
                    tr("Radio+:aan tallennetut suosikit", "Favorites saved in Radio+")
            ));
            roots.add(createBrowsableItem(
                    MEDIA_STATIONS_ID,
                    tr("Asemalista", "Station list"),
                    tr("Kaikki löydetyt asemat", "All found stations")
            ));
            result.sendResult(roots);
            return;
        }
        List<FavoriteStation> stations;
        if (MEDIA_FAVORITES_ID.equals(parentId)) {
            stations = favoriteStore.load();
        } else if (MEDIA_STATIONS_ID.equals(parentId)) {
            stations = stationStore.load();
        } else {
            result.sendResult(Collections.emptyList());
            return;
        }
        ArrayList<MediaItem> items = new ArrayList<>(stations.size());
        for (FavoriteStation station : stations) {
            items.add(createPlayableItem(station));
        }
        result.sendResult(items);
    }

    private void bindOemRadio() {
        if (stopping || bound || radio != null || inspectingBackend) {
            return;
        }
        mainHandler.removeCallbacks(rebindTask);
        inspectingBackend = true;
        int generation = ++backendGeneration;
        RadioApiFactory.detect(this, detection -> {
            if (generation != backendGeneration) return;
            inspectingBackend = false;
            // A connection may have become usable while APK inspection ran.
            // Never let an obsolete detection replace an established backend.
            if (stopping || bound || radio != null) return;
            if (detection.profile == RadioBackendProfile.UNKNOWN) {
                Log.w(TAG, "Stock radio APK not recognized; binding and control blocked");
                setPlaybackRequested(false);
                return;
            }
            bindRecognizedOemRadio(detection);
        });
    }

    private void bindRecognizedOemRadio(RadioApiFactory.Detection detection) {
        boundDetection = detection;
        if (shouldIgnoreRawMediaKeys()) resetRawMediaKeyDecisions();
        Intent intent = RadioBackendContract.serviceIntent(detection.profile);
        try {
            if (detection.profile.isNwd()) startService(intent);
            bound = bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!bound) {
                scheduleRebind();
            } else {
                // bindService may report success even though a wedged vendor
                // process never supplies the Binder. Recover instead of
                // leaving queued playback commands without audio forever.
                mainHandler.removeCallbacks(oemBindWatchdogTask);
                mainHandler.postDelayed(oemBindWatchdogTask, OEM_BIND_TIMEOUT_MS);
            }
        } catch (RuntimeException exception) {
            bound = false;
            Log.w(TAG, "Stock radio keep-alive binding unavailable", exception);
            scheduleRebind();
        }
    }

    private void releaseBinding() {
        backendGeneration++;
        inspectingBackend = false;
        mainHandler.removeCallbacks(oemBindWatchdogTask);
        IBinder binder = radioBinder;
        if (binder != null) {
            try {
                binder.unlinkToDeath(radioDeathRecipient, 0);
            } catch (RuntimeException ignored) {
                // A dead vendor binder may already have removed the recipient.
            }
        }
        if (bound) {
            try {
                unbindService(connection);
            } catch (IllegalArgumentException ignored) {
                // The vendor process may have removed a dead binding already.
            }
        }
        bound = false;
        radio = null;
        radioBinder = null;
        resetOemPlaybackOwnership();
    }

    private void handleOemConnectionLoss(String reason) {
        if (stopping) {
            return;
        }
        Log.w(TAG, "Stock radio connection lost: " + reason);
        if (radio instanceof NwdRadioApi
                || (boundDetection != null && boundDetection.profile.isNwd())) {
            // The shared NWD cache can return this same API object after a
            // rebind. Identity alone must not revive a pre-loss queued tune
            // or its in-flight continuation; keep HCN/TS recovery unchanged.
            playbackEpoch++;
            playbackRequestRevision++;
            pendingWidgetStation = null;
            pendingRadioCommand = null;
            pendingRadioCommandName = "";
            resetRawMediaKeyDecisions();
        }
        backendGeneration++;
        inspectingBackend = false;
        radio = null;
        resetOemPlaybackOwnership();
        // Leave the ServiceConnection callback before unbinding. Several
        // Junsun builds otherwise retain a logically bound but dead handle.
        mainHandler.post(() -> {
            if (stopping || radio != null) {
                return;
            }
            releaseBinding();
            scheduleRebind();
        });
    }

    private void scheduleRebind() {
        if (stopping) {
            return;
        }
        mainHandler.removeCallbacks(rebindTask);
        long delay = rebindDelayMs;
        rebindDelayMs = Math.min(MAX_REBIND_DELAY_MS, rebindDelayMs * 2L);
        mainHandler.postDelayed(rebindTask, delay);
    }

    private void requestPlayback(boolean explicitTakeover) {
        abandonPauseAudioFocus();
        setPlaybackRequested(true);
        executeRadioCommand("play", (current, epoch) -> {
            activateOemPlayback(current, explicitTakeover, epoch);
            Log.i(TAG, "Stock radio background playback requested");
        });
    }

    private void pausePlayback() {
        SteeringDiagnosticTrace.get().event("playback", "pause-request");
        IRadioServiceAPI current = radio;
        pendingNwdPause = current instanceof NwdRadioApi
                && ((NwdRadioApi) current).markPendingAudioCancellation()
                ? new PendingNwdPause((NwdRadioApi) current,
                        playbackEpoch + (playbackRequested ? 1L : 0L), playbackRequestRevision + 1L)
                : null;
        setPlaybackRequested(false);
        if (pendingNwdPause != null) {
            resolvePendingNwdPause();
            return;
        }
        long pauseEpoch = playbackEpoch;
        if (usesSourceExitBackend()) {
            // TS and NWD exit their radio source through the verified vendor
            // command. HCN's focus/mute handoff does not apply.
            releasePlaybackFocus();
            return;
        }
        // FMPlugService.releaseAudioFocus() only abandons the stock request; it
        // does not call RadioPlayer.setMute(true). Taking media focus first
        // makes the stock focus listener run its real mute path, exactly as it
        // does when switching from radio to another media source.
        boolean focusTaken = takePauseAudioFocus();
        // Direct framework mute is independent of the asynchronous OEM Binder
        // connection, so a tap also stops audio during service reconnection.
        boolean directMuteSent = setTunerMuted(true);
        if (focusTaken) {
            // Give the stock process time to handle AUDIOFOCUS_LOSS before the
            // fallback release. Calling release first bypasses its mute code.
            mainHandler.postDelayed(() -> {
                if (playbackEpoch == pauseEpoch && !playbackRequested) releasePlaybackFocus();
            }, 250L);
            mainHandler.postDelayed(() -> {
                if (playbackEpoch == pauseEpoch && !playbackRequested) abandonPauseAudioFocus();
            },
                    PAUSE_FOCUS_HANDOFF_MS);
        } else {
            releasePlaybackFocus();
        }
        Log.i(TAG, "Pause requested; focus takeover=" + focusTaken
                + ", direct tuner mute=" + directMuteSent);
        // FMPlugService unmutes the tuner 500 ms after an earlier focus gain.
        // Reassert the explicit pause after that vendor callback has finished.
        mainHandler.postDelayed(this::reassertPausedRoute, 700L);
        mainHandler.postDelayed(this::reassertPausedRoute, 1400L);
    }

    private void resolvePendingNwdPause() {
        PendingNwdPause pending = pendingNwdPause;
        if (pending == null || playbackRequested
                || !isCurrentNwdRequest(pending.api, pending.epoch, pending.revision)) return;
        executeRadioCommand("cancel pending NWD audio start", (current, epoch) -> {
            if (pendingNwdPause != pending || playbackRequested
                    || !isCurrentNwdRequest(pending.api, pending.epoch, pending.revision)) return;
            // Never retry Play, tune or a generic pause here. Only the original
            // unresolved source=4 request is eligible for this cancellation.
            pending.api.cancelPendingAudioStart();
            boolean unresolved = pending.api.hasPendingAudioStart();
            mainHandler.post(() -> {
                if (pendingNwdPause != pending || playbackRequested
                        || !isCurrentNwdRequest(pending.api, pending.epoch, pending.revision)) return;
                if (unresolved) return;
                pendingNwdPause = null;
                oemRouteActive = false;
                updatePlaybackState();
                if (foregroundStarted) refreshNotification();
                if (stopAfterNwdPause) {
                    stopAfterNwdPause = false;
                    stopping = true;
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                }
            });
        });
    }

    private static final class PendingNwdPause {
        final NwdRadioApi api;
        final long epoch;
        final long revision;

        PendingNwdPause(NwdRadioApi api, long epoch, long revision) {
            this.api = api;
            this.epoch = epoch;
            this.revision = revision;
        }
    }

    private void reassertPausedRoute() {
        if (stopping || playbackRequested) {
            return;
        }
        boolean muted = setTunerMuted(true);
        Log.i(TAG, muted
                ? "Stock radio pause reasserted"
                : "Stock radio pause reassertion unavailable");
    }

    private boolean takePauseAudioFocus() {
        if (audioManager == null) {
            return false;
        }
        abandonPauseAudioFocus();
        if (pauseFocusRequest == null) {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            pauseFocusRequest = new AudioFocusRequest.Builder(
                    AudioManager.AUDIOFOCUS_GAIN
            )
                    .setAudioAttributes(attributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(
                            pauseFocusListener,
                            mainHandler
                    )
                    .build();
        }
        int result;
        try {
            result = audioManager.requestAudioFocus(pauseFocusRequest);
        } catch (RuntimeException error) {
            Log.w(TAG, "Pause audio-focus takeover failed", error);
            return false;
        }
        pauseFocusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return pauseFocusHeld;
    }

    private void abandonPauseAudioFocus() {
        if (!pauseFocusHeld || audioManager == null || pauseFocusRequest == null) {
            return;
        }
        try {
            audioManager.abandonAudioFocusRequest(pauseFocusRequest);
        } catch (RuntimeException error) {
            Log.w(TAG, "Pause audio-focus release failed", error);
        } finally {
            pauseFocusHeld = false;
        }
    }

    private void releasePlaybackFocus() {
        if (stopping || playbackRequested) return;
        SteeringDiagnosticTrace.get().event("playback", "pause-release requested=" + playbackRequested);
        executeRadioCommand("pause", (current, epoch) -> {
            if (stopping || playbackRequested || playbackEpoch != epoch || radio != current) return;
            if (RadioApiFactory.supportsSeparateAudioFocus(current)
                    && !oemFocusState.wasFocusReleasedByRadioPlus()) {
                current.releaseAudioFocus();
                oemFocusState.markFocusReleasedByRadioPlus();
            }
            boolean muted = setTunerMuted(true);
            oemRouteActive = false;
            Log.i(TAG, muted
                    ? "Stock radio playback paused and tuner muted"
                    : "Stock radio playback paused through OEM audio focus");
        });
    }

    private void activateOemPlayback(
            IRadioServiceAPI current,
            boolean explicitTakeover,
            long epoch
    )
            throws RemoteException {
        if (!isCurrentPlayback(epoch, current)) return;
        boolean separateFocus = RadioApiFactory.supportsSeparateAudioFocus(current);
        if (separateFocus && OemFocusInteropPolicy.shouldReplaceFocusRequest(
                explicitTakeover,
                oemFocusState.wasFocusReleasedByRadioPlus()
        )) {
            // FMPlugService keeps mFocusRequest after its listener abandons
            // focus. Replace that exact request before selecting radio so a
            // currently playing YouTube session receives focus loss.
            current.releaseAudioFocus();
            oemFocusState.markFocusReleasedByRadioPlus();
            oemRouteActive = false;
            Log.i(TAG, "Stale OEM audio focus released for explicit radio takeover");
        }
        RadioPlaybackHealthReader.Snapshot snapshot = readPlaybackHealth();
        if (!isCurrentPlayback(epoch, current)) return;
        boolean requestFocus = oemFocusState.shouldRequestFocus(
                snapshot.sourceKnown,
                snapshot.radioOwnsSource(),
                snapshot.muteKnown,
                snapshot.muted
        ) || (!separateFocus && explicitTakeover);
        // Legacy HCN has no separate focus endpoint. An explicit Play/takeover
        // must still request its audio route and attempt optional unmute, even
        // if the delayed pause cleanup has not yet reset oemRouteActive.
        if (OemFocusInteropPolicy.shouldRequestRoute(oemRouteActive, requestFocus)) {
            // Match the stock RadioAppManager: select the analog radio source
            // before asking FMPlugService to unmute it through audio focus.
            oemRouteActive = current.requestPlayAudio();
            if (current instanceof NwdRadioApi && !oemRouteActive) {
                throw new NwdRadioApi.CommandRejectedException("NWD source takeover is not available");
            }
            oemFocusState.markRouteRequested();
            Log.i(TAG, oemRouteActive
                    ? "Stock radio audio route active"
                    : "Stock radio audio route activation was rejected");
        }
        if (requestFocus) {
            if (!isCurrentPlayback(epoch, current)) return;
            // requestAudioFocus() is also the vendor's unmute operation. RDS
            // can keep updating while this step is missing, which is why a
            // metadata-only success must not be treated as active playback.
            if (separateFocus) {
                current.requestAudioFocus();
            }
            if (!isCurrentPlayback(epoch, current)) return;
            setTunerMuted(false);
            oemFocusState.markFocusResumed();
            Log.i(TAG, !separateFocus
                    ? "Legacy radio playback requested; optional unmute attempted"
                    : snapshot.muteKnown && snapshot.muted
                            ? "Stock radio audio focus resumed from OEM mute"
                            : "Stock radio audio focus activated for this session");
        }
        // PLAYING/setActive alone does not register our UID in Android's audio
        // playback history. Wait for the stock focus handoff (including its
        // delayed unmute) before a single bounded pulse, never a keep-alive loop.
        if (!isCurrentPlayback(epoch, current)) return;
        activatedPlaybackEpoch = epoch;
        if (explicitTakeover) {
            uiTakeoverRequired = false;
            // The V7 PCM routing handoff is hardware-tested only on HCN.
            // On TS, Android PCM may itself switch the analog source to mode 0.
            // Do not apply that workaround before a TS device test verifies it.
            if (RadioApiFactory.usesHcnFramework(current)) scheduleRoutingClaim(current, epoch);
        }
        scheduleStartupVolumePolicy(requestFocus);
    }

    private boolean isCurrentPlayback(long epoch, IRadioServiceAPI current) {
        return !stopping && playbackRequested && playbackEpoch == epoch && radio == current;
    }

    private boolean isRadioOrOwnSource(RadioPlaybackHealthReader.Snapshot health) {
        // Some ROMs report the client package while our finite PCM marker is
        // open. Never mistake it for another app; do NOT allow the other flavor.
        return health.radioOwnsSource() || getPackageName().equals(health.sourcePackage)
                || health.sourcePackage.startsWith(getPackageName() + "/");
    }

    private void cancelRoutingClaim() {
        routingClaimId++;
        if (pendingRoutingClaim != null) mainHandler.removeCallbacks(pendingRoutingClaim);
        pendingRoutingClaim = null;
    }

    private void scheduleRoutingClaim(IRadioServiceAPI current, long epoch) {
        mainHandler.post(() -> {
            if (!isCurrentPlayback(epoch, current)) return;
            cancelRoutingClaim();
            long claim = routingClaimId;
            routingPulseStatus = "scheduled";
            pendingRoutingClaim = () -> {
                pendingRoutingClaim = null;
                if (!isCurrentPlayback(epoch, current) || routingClaimId != claim) return;
                executeRadioCommand("media routing", (connected, commandEpoch) -> {
                    if (!isCurrentPlayback(epoch, current) || routingClaimId != claim) return;
                    RadioPlaybackHealthReader.Snapshot health = readPlaybackHealth();
                    if (health.sourceKnown && !isRadioOrOwnSource(health)) {
                        routingPulseStatus = "skipped-external-source";
                        return; // Never reclaim radio from another app in a delayed callback.
                    }
                    routingPulseStatus = "running";
                    MediaKeyRoutingPulse.Result result = MediaKeyRoutingPulse.run(() ->
                            !isCurrentPlayback(epoch, current) || routingClaimId != claim);
                    routingPulseStatus = result.status.name().toLowerCase(Locale.ROOT);
                    SteeringDiagnosticTrace.get().event("routing-pulse", result.diagnostic());
                    if (!isCurrentPlayback(epoch, current) || routingClaimId != claim) return;
                    if (result.trackCreated && !result.trackReleased) return;
                    // AudioTrack can affect a vendor analog path even with no
                    // focus request. It has been RELEASED before this one-time
                    // restore. Do not request focus again or change any volume.
                    health = readPlaybackHealth();
                    if (health.sourceKnown && !isRadioOrOwnSource(health)) return;
                    if (!isCurrentPlayback(epoch, current) || routingClaimId != claim) return;
                    oemRouteActive = current.requestPlayAudio();
                    SteeringDiagnosticTrace.get().event("routing-pulse",
                            "route-restored=" + oemRouteActive);
                });
            };
            mainHandler.postDelayed(pendingRoutingClaim, OEM_GAIN_SETTLE_MS);
        });
    }

    private void reconcilePlaybackOwnership() {
        IRadioServiceAPI current = radio;
        if (current == null || stopping || !playbackRequested) return;
        long epoch = playbackEpoch;
        long requestRevision = playbackRequestRevision;
        executeRadioCommand("playback ownership", (connected, commandEpoch) -> {
            RadioPlaybackHealthReader.Snapshot health = readPlaybackHealth();
            if (connected instanceof NwdRadioApi) {
                if (!isCurrentNwdRequest(connected, commandEpoch, requestRevision)
                        || !playbackOwnership.shouldYield(SystemClock.elapsedRealtime(),
                                health.sourceKnown, health.radioOwnsSource())) return;
                // A pending MCU source=4 request can still arrive after the
                // source snapshot. Cancel only that owned start before yielding;
                // an already handed-off external source is never switched.
                if (!prepareNwdAdvertisedPause((NwdRadioApi) connected,
                        commandEpoch, requestRevision)) return;
                mainHandler.post(() -> {
                    if (!isCurrentNwdRequest(connected, commandEpoch, requestRevision)) return;
                    setPlaybackRequested(false);
                    oemRouteActive = false;
                    SteeringDiagnosticTrace.get().event("playback", "external-source-paused");
                });
                return;
            }
            mainHandler.post(() -> {
                if (!isCurrentPlayback(epoch, current)) return;
                if (!playbackOwnership.shouldYield(SystemClock.elapsedRealtime(),
                        health.sourceKnown, isRadioOrOwnSource(health))) return;
                // Only correct OUR advertised state. Do not mute/release focus
                // belonging to the user's newly selected app or restart FM.
                setPlaybackRequested(false);
                oemRouteActive = false;
                SteeringDiagnosticTrace.get().event("playback", "external-source-paused");
            });
        });
    }

    private void scheduleStartupVolumePolicy(boolean focusRequested) {
        // The stock V7 radio restores the shared FM input path to 100% in its
        // delayed 500 ms focus callback. Restore normal gain once after that
        // route transition. There is no first-step attenuation anymore.
        mainHandler.removeCallbacks(startupVolumePolicyTask);
        if (radioVolumeGuard != null) {
            // The input-gain workaround belongs to the V7 HCN contract, not TS
            // or an unidentified backend, even if similarly named classes exist.
            radioVolumeGuard.setActive(playbackRequested
                    && RadioApiFactory.supportsSeparateAudioFocus(radio));
        }
        if (!RadioApiFactory.supportsSeparateAudioFocus(radio)) return;
        if (focusRequested) {
            mainHandler.postDelayed(startupVolumePolicyTask, OEM_GAIN_SETTLE_MS);
        } else {
            mainHandler.post(startupVolumePolicyTask);
        }
    }

    private void reassertRadioVolumePolicy() {
        if (radioVolumeGuard != null && playbackRequested && !stopping
                && RadioApiFactory.supportsSeparateAudioFocus(radio)) {
            radioVolumeGuard.reassertNow();
        }
    }

    private void applyRadioVolumePolicy() {
        if (radioVolumeGuard != null && playbackRequested && !stopping
                && RadioApiFactory.supportsSeparateAudioFocus(radio)) {
            radioVolumeGuard.applyNow();
        }
    }

    private void activateForTunerCommand(IRadioServiceAPI current, long epoch)
            throws RemoteException {
        // Selecting, seeking or tuning a station explicitly leaves the
        // currently playing media source and makes the radio audible.
        activateOemPlayback(current, true, epoch);
    }

    private void activateForStationNavigation(IRadioServiceAPI current, long epoch)
            throws RemoteException {
        RadioPlaybackHealthReader.Snapshot health = readPlaybackHealth();
        boolean takeover = OemFocusInteropPolicy.shouldTakeOverForTuning(
                activatedPlaybackEpoch == playbackEpoch,
                oemRouteActive,
                oemFocusState.wasFocusReleasedByRadioPlus(),
                health.sourceKnown && !isRadioOrOwnSource(health),
                health.muteKnown && health.muted
        );
        // Keep cold start, resume and observed source changes on the original
        // takeover path. A healthy next/previous needs only tuning, not another
        // delayed PCM marker. In-app station taps use this same path while the
        // radio screen stays open. Returning from another app, widgets and
        // external media-ID commands retain explicit takeover on unknown ROMs.
        activateOemPlayback(current, takeover, epoch);
    }

    private void resetOemPlaybackOwnership() {
        cancelRoutingClaim();
        activatedPlaybackEpoch = -1L;
        oemRouteActive = false;
        oemFocusState.onServiceDisconnected();
    }

    private boolean usesSourceExitBackend() {
        return radio instanceof TsRadioApi || radio instanceof NwdRadioApi
                || (boundDetection != null && (boundDetection.profile.isTs()
                    || boundDetection.profile.isNwd()));
    }

    private RadioPlaybackHealthReader.Snapshot readPlaybackHealth() {
        IRadioServiceAPI current = radio;
        if (current == null) return new RadioPlaybackHealthReader.Snapshot(false, "", false, false);
        if (current instanceof NwdRadioApi) return ((NwdRadioApi) current).readHealth();
        if (!(current instanceof TsRadioApi)) {
            if (usesSourceExitBackend()) return new RadioPlaybackHealthReader.Snapshot(false, "", false, false);
            return playbackHealthReader.read();
        }
        try {
            return ((TsRadioApi) current).readHealth();
        } catch (RemoteException | RuntimeException error) {
            Log.w(TAG, "TS source state unavailable", error);
            return new RadioPlaybackHealthReader.Snapshot(false, "", false, false);
        }
    }

    private boolean setTunerMuted(boolean muted) {
        IRadioServiceAPI current = radio;
        if (current == null) return false;
        if (current instanceof NwdRadioApi) {
            if (!muted || playbackRequested) return false;
            if (Looper.myLooper() == Looper.getMainLooper()) {
                executeRadioCommand("pause NWD source", (connected, epoch) -> {
                    if (!playbackRequested && connected == current && playbackEpoch == epoch
                            && radio == connected) ((NwdRadioApi) connected).pauseRadioSource();
                });
                return false;
            }
            return ((NwdRadioApi) current).pauseRadioSource();
        }
        if (!(current instanceof TsRadioApi)) return !usesSourceExitBackend() && playbackHealthReader.setMuted(muted);
        // TS has a verified source exit but no verified idempotent tuner mute.
        // Do not toggle global mute, run HCN reflection, or block the main thread.
        if (!muted || playbackRequested) return false;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            executeRadioCommand("pause TS source", (connected, epoch) -> {
                if (!playbackRequested && connected instanceof TsRadioApi && playbackEpoch == epoch
                        && radio == connected) {
                    ((TsRadioApi) connected).pauseRadioSource();
                }
            });
            return false;
        }
        try { return ((TsRadioApi) current).pauseRadioSource(); }
        catch (RemoteException | RuntimeException error) {
            Log.w(TAG, "TS source exit failed", error);
            return false;
        }
    }

    private void togglePlayback() {
        Log.i(TAG, "Media command: play/pause");
        if (playbackRequested) {
            pausePlayback();
        } else {
            requestPlayback(true);
        }
    }

    private void selectAdjacentStation(boolean next) {
        // Snapshot the list when the command arrives. A later tab change must
        // not redirect a queued press, and no list must ever fall back to seek.
        List<FavoriteStation> stations = stationNavigationStore.load();
        SteeringDiagnosticTrace.get().event("navigation", (next ? "next" : "previous")
                + " list=" + (stationNavigationStore.favoritesSelected() ? "favorites" : "stations")
                + " count=" + stations.size());
        if (stations.isEmpty()) {
            Log.i(TAG, "Media skip ignored: selected station list is empty");
            return;
        }
        Log.i(TAG, next
                ? "Media command: next preset"
                : "Media command: previous preset");
        boolean previouslyRequested = playbackRequested;
        setPlaybackRequested(true);
        long requestRevision = playbackRequestRevision;
        executeRadioCommand(next ? "next preset" : "previous preset", (current, epoch) -> {
            int band = normalizeBand(current.getCurrentBand());
            int frequency = current.getCurrentFreq();
            FavoriteStation target = FavoriteNavigator.selectInStoredOrder(
                    stations,
                    band,
                    frequency,
                    next
            );
            if (target == null) {
                SteeringDiagnosticTrace.get().event("navigation", "no-target");
                return;
            } else {
                if (!validateStationTarget(current, target, epoch, requestRevision, previouslyRequested)) return;
                activateForStationNavigation(current, epoch);
                boolean tuned = tuneTo(current, target, epoch);
                SteeringDiagnosticTrace.get().event("tuner", "frequency-confirmed=" + tuned);
                if (tuned && isCurrentPlayback(epoch, current)) {
                    oemRouteActive = finishConfirmedTuneRoute(current);
                    SteeringDiagnosticTrace.get().event("tuner", "route-reported=" + oemRouteActive);
                    mainHandler.post(this::refreshMediaMetadataFromRadio);
                    Log.i(TAG, "Media preset confirmed: " + target.key());
                } else {
                    mainHandler.post(this::refreshMediaMetadataFromRadio);
                    Log.w(TAG, "Media preset was not confirmed: " + target.key());
                }
            }
        });
    }

    private void queueWidgetStation(Intent intent) {
        queueWidgetStation(intent, false);
    }

    private void queueWidgetStation(Intent intent, boolean reusePlayback) {
        int band = intent.getIntExtra(EXTRA_STATION_BAND, -1);
        int frequency = intent.getIntExtra(EXTRA_STATION_FREQUENCY, -1);
        if (!FrequencyRules.isValid(band, frequency)) {
            Log.w(TAG, "Widget supplied an invalid station");
            return;
        }
        queueStation(new FavoriteStation(
                band,
                frequency,
                intent.getStringExtra(EXTRA_STATION_NAME)
        ), reusePlayback);
    }

    private void queueStation(FavoriteStation station) {
        queueStation(station, false);
    }

    private void queueStation(FavoriteStation station, boolean reusePlayback) {
        if (station == null
                || !FrequencyRules.isValid(station.band, station.frequency)) {
            return;
        }
        FavoriteStation stored = findStoredStation(station.band, station.frequency);
        pendingWidgetStation = stored == null ? station : stored;
        pendingStationReusesPlayback = reusePlayback;
        pendingStationPreviousPlaybackRequested = playbackRequested;
        setPlaybackRequested(true);
        pendingStationPlaybackRevision = playbackRequestRevision;
        if (!bound && !stopping) {
            bindOemRadio();
        }
        tunePendingWidgetStation();
    }

    private void tunePendingWidgetStation() {
        FavoriteStation target = pendingWidgetStation;
        boolean reusePlayback = pendingStationReusesPlayback;
        boolean previouslyRequested = pendingStationPreviousPlaybackRequested;
        long requestRevision = pendingStationPlaybackRevision;
        if (target == null) {
            return;
        }
        executeRadioCommand("widget station", (current, epoch) -> {
            if (!validateStationTarget(current, target, epoch, requestRevision, previouslyRequested)) return;
            if (reusePlayback) activateForStationNavigation(current, epoch);
            else activateForTunerCommand(current, epoch);
            boolean tuned = tuneTo(current, target, epoch);
            if (tuned && isCurrentPlayback(epoch, current)) {
                oemRouteActive = finishConfirmedTuneRoute(current);
            }
            mainHandler.post(this::refreshMediaMetadataFromRadio);
            mainHandler.post(() -> {
                if (radio != current || playbackEpoch != epoch
                        || playbackRequestRevision != requestRevision) return;
                FavoriteStation pending = pendingWidgetStation;
                if (target.equals(pending)) {
                    pendingWidgetStation = null;
                }
            });
            Log.i(TAG, tuned
                    ? "Station tune confirmed: " + target.key()
                    : "Station tune was not confirmed: " + target.key());
        });
    }

    private boolean finishConfirmedTuneRoute(IRadioServiceAPI current) throws RemoteException {
        if (current instanceof NwdRadioApi) {
            // NWD selected and checked source 4 before/during its tune. A later
            // source handoff must not be undone by a redundant tail Play.
            return ((NwdRadioApi) current).readHealth().radioOwnsSource();
        }
        return current.requestPlayAudio();
    }

    private boolean tuneTo(IRadioServiceAPI current, FavoriteStation target, long epoch)
            throws RemoteException {
        if (!isCurrentPlayback(epoch, current)) return false;
        int normalizedTarget = normalizeBand(target.band);
        if (current instanceof NwdRadioApi) {
            ((NwdRadioApi) current).validateTuningTarget(normalizedTarget, target.frequency);
            // NWD owns asynchronous confirmation and unresolved-command state.
            // Replaying a confirmed tune here can fight a later OEM key/seek.
            return ((NwdRadioApi) current).tuneToBand(normalizedTarget, target.frequency,
                    () -> isCurrentPlayback(epoch, current));
        }
        int currentBand = normalizeBand(current.getCurrentBand());
        if (!isCurrentPlayback(epoch, current)) return false;
        boolean commandIssued;
        if (current instanceof TsRadioApi) {
            commandIssued = ((TsRadioApi) current).tuneToBand(normalizedTarget, target.frequency,
                    () -> isCurrentPlayback(epoch, current));
        } else if (currentBand == normalizedTarget) {
            current.gotoFreq(target.frequency);
            commandIssued = true;
        } else {
            // New-skin Junsun ROMs toggle onBandEvent only between FM and AM,
            // so it cannot reliably select FM1/FM2/FM3. Prefer the vendor's
            // atomic band+frequency API when changing bands.
            commandIssued = tunerMetadataReader.tuneToBand(
                    normalizedTarget,
                    target.frequency
            );
            if (!commandIssued) {
                int guard = 0;
                int observedBand = current.getCurrentBand();
                while (normalizeBand(observedBand) != normalizedTarget && guard < 5) {
                    if (!isCurrentPlayback(epoch, current)) return false;
                    current.onBandEvent();
                    observedBand = current.getCurrentBand();
                    guard++;
                }
                if (normalizeBand(observedBand) == normalizedTarget) {
                    if (!isCurrentPlayback(epoch, current)) return false;
                    current.gotoFreq(target.frequency);
                    commandIssued = true;
                }
            }
        }

        if (!commandIssued) {
            Log.w(TAG, "Media preset band could not be selected: " + target.band);
            return false;
        }

        for (int attempt = 1; attempt <= RadioTuneConfirmation.MAX_ATTEMPTS; attempt++) {
            SystemClock.sleep(TUNE_CONFIRM_DELAY_MS);
            if (!isCurrentPlayback(epoch, current)) return false;
            int observedBand = normalizeBand(current.getCurrentBand());
            int observedFrequency = current.getCurrentFreq();
            if (RadioTuneConfirmation.matches(
                    normalizedTarget,
                    target.frequency,
                    observedBand,
                    observedFrequency
            )) {
                return true;
            }
            if (attempt < RadioTuneConfirmation.MAX_ATTEMPTS) {
                if (!isCurrentPlayback(epoch, current)) return false;
                Log.w(TAG, "Tuner did not confirm " + target.key()
                        + "; retry " + (attempt + 1)
                        + " (actual=" + observedBand + ":" + observedFrequency + ")");
                if (current instanceof TsRadioApi) {
                    ((TsRadioApi) current).tuneToBand(normalizedTarget, target.frequency,
                            () -> isCurrentPlayback(epoch, current));
                } else if (!tunerMetadataReader.tuneToBand(
                        normalizedTarget,
                        target.frequency
                )) {
                    current.gotoFreq(target.frequency);
                }
            }
        }
        return false;
    }

    /** Invalid NWD targets must not trigger source takeover, or a rebind that later takes over. */
    private boolean validateStationTarget(IRadioServiceAPI current, FavoriteStation target,
            long epoch, long requestRevision, boolean previouslyRequested) {
        if (!isCurrentPlayback(epoch, current)) return false;
        if (!(current instanceof NwdRadioApi)) return true;
        try {
            ((NwdRadioApi) current).validateTuningTarget(normalizeBand(target.band), target.frequency);
            return isCurrentPlayback(epoch, current);
        } catch (RemoteException | RuntimeException error) {
            Log.w(TAG, "NWD station target is not available; playback source left unchanged", error);
            mainHandler.post(() -> {
                if (!isCurrentPlayback(epoch, current)
                        || playbackRequestRevision != requestRevision) return;
                FavoriteStation pending = pendingWidgetStation;
                if (pending != null && !target.equals(pending)) return;
                if (target.equals(pending)) pendingWidgetStation = null;
                if (!previouslyRequested) setPlaybackRequested(false);
                refreshMediaMetadataFromRadio();
            });
            return false;
        }
    }

    private int normalizeBand(int band) {
        return band >= 3 ? 3 : Math.max(0, band);
    }

    private void executeRadioCommand(String command, RadioCommand action) {
        IRadioServiceAPI current = radio;
        if (current == null || stopping) {
            SteeringDiagnosticTrace.get().event("oem", "waiting: " + command);
            if (!stopping && playbackRequested) {
                // A cold media-button or widget command can arrive before the
                // asynchronous OEM binding completes. Preserve only the newest
                // command and execute it as soon as FMPlugService connects.
                pendingRadioCommandName = command;
                pendingRadioCommand = action;
                bindOemRadio();
            }
            Log.i(TAG, "Media command waiting for stock radio: " + command);
            return;
        }
        pendingRadioCommandName = "";
        pendingRadioCommand = null;
        long epoch = playbackEpoch;
        long requestRevision = playbackRequestRevision;
        try {
            executor.submit(() -> {
                if (stopping || playbackEpoch != epoch || radio != current) return;
                try {
                    action.run(current, epoch);
                } catch (NwdRadioApi.CommandRejectedException error) {
                    // Busy/readiness/source rejection is not Binder death. A
                    // rebind would replay the pending target and could steal
                    // audio back from the source the user selected meanwhile.
                    Log.w(TAG, "NWD command unavailable: " + command, error);
                    settleRejectedNwdCommand(current, epoch, requestRevision);
                } catch (RemoteException | RuntimeException | LinkageError error) {
                    SteeringDiagnosticTrace.get().event("oem", "command-failed: " + command);
                    Log.w(TAG, "Stock radio command failed: " + command, error);
                    mainHandler.post(() -> {
                        if (radio == current && !stopping
                                && (!(current instanceof NwdRadioApi) || playbackEpoch == epoch)) {
                            handleOemConnectionLoss("command failed: " + command);
                        }
                    });
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Service shutdown raced with a vendor callback.
        }
    }

    private void settleRejectedNwdCommand(IRadioServiceAPI current, long epoch, long requestRevision) {
        if (!isCurrentNwdRequest(current, epoch, requestRevision)) return;
        RadioPlaybackHealthReader.Snapshot health = current instanceof NwdRadioApi
                ? ((NwdRadioApi) current).readHealth() : null;
        boolean shouldPause = health != null && !health.radioOwnsSource()
                && prepareNwdAdvertisedPause((NwdRadioApi) current, epoch, requestRevision);
        mainHandler.post(() -> {
            if (!isCurrentNwdRequest(current, epoch, requestRevision)) return;
            pendingWidgetStation = null;
            pendingRadioCommand = null;
            pendingRadioCommandName = "";
            if (shouldPause) {
                oemRouteActive = false;
                // Any owned pending MCU start was canceled above. Do not mute
                // another source, reacquire it or replay the rejected command.
                setPlaybackRequested(false);
            }
        });
    }

    private boolean isCurrentNwdRequest(IRadioServiceAPI current, long epoch, long requestRevision) {
        return !stopping && radio == current && playbackEpoch == epoch
                && playbackRequestRevision == requestRevision;
    }

    private boolean prepareNwdAdvertisedPause(NwdRadioApi current, long epoch, long requestRevision) {
        if (!isCurrentNwdRequest(current, epoch, requestRevision)) return false;
        boolean canceled = current.cancelPendingAudioStart();
        RadioPlaybackHealthReader.Snapshot freshHealth = current.readHealth();
        if (!isCurrentNwdRequest(current, epoch, requestRevision)) return false;
        // Unknown source ownership leaves the pending start unresolved. Keep
        // observing instead of saying paused while our source=4 can still land.
        // A fresh source=4 with no cancellation is already acknowledged playback.
        return canceled || (!current.hasPendingAudioStart() && !freshHealth.radioOwnsSource());
    }

    private void refreshMediaMetadataFromRadio() {
        if (radio == null || stopping) {
            return;
        }
        executeRadioCommand("media metadata", (current, epoch) -> {
            int band = normalizeBand(current.getCurrentBand());
            int frequency = current.getCurrentFreq();
            if (!FrequencyRules.isValid(band, frequency)) {
                return;
            }
            String rdsName = RadioMetadataReader.clean(
                    current.getCurrentFreqRdsPs()
            );
            mainHandler.post(() -> {
                if (!stopping && radio == current && playbackEpoch == epoch) {
                    updateMediaMetadata(band, frequency, rdsName, "");
                }
            });
        });
    }

    private void updateMediaMetadata(
            int band,
            int frequency,
            String stationName,
            String radioText
    ) {
        if (mediaSession == null
                || !FrequencyRules.isValid(band, frequency)) {
            return;
        }
        FavoriteStation stored = findStoredStation(band, frequency);
        FavoriteStation station = stored == null
                ? new FavoriteStation(band, frequency, stationName)
                : stored;
        String cleanRdsName = RadioMetadataReader.clean(stationName);
        String displayName = station.name.isEmpty()
                ? cleanRdsName
                : AppLanguage.stationName(this, station.name);
        if (displayName.isEmpty()) {
            displayName = station.frequencyLabel();
        }
        String cleanRadioText = RadioMetadataReader.clean(radioText);
        boolean sameStation = band == publishedMediaBand
                && frequency == publishedMediaFrequency;
        if (sameStation && cleanRadioText.isEmpty()) {
            cleanRadioText = publishedRadioText;
        }
        if (sameStation
                && displayName.equals(publishedMediaName)
                && cleanRadioText.equals(publishedRadioText)
                && station.logo.equals(publishedLogo)) {
            return;
        }

        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, MediaStationId.encode(station))
                .putString(MediaMetadata.METADATA_KEY_TITLE, displayName)
                .putString(
                        MediaMetadata.METADATA_KEY_ARTIST,
                        cleanRadioText.isEmpty() ? "Radio+" : cleanRadioText
                )
                .putString(MediaMetadata.METADATA_KEY_ALBUM, station.frequencyLabel())
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, displayName)
                .putString(
                        MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                        cleanRadioText.isEmpty()
                                ? station.frequencyLabel()
                                : cleanRadioText
                )
                .putString(
                        MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION,
                        station.bandLabel() + " \u2022 " + station.frequencyLabel()
                );
        Bitmap artwork = loadMediaArtwork(station, displayName);
        if (artwork != null) {
            metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork);
            metadata.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, artwork);
        }
        mediaSession.setMetadata(metadata.build());
        publishedMediaBand = band;
        publishedMediaFrequency = frequency;
        publishedMediaName = displayName;
        publishedRadioText = cleanRadioText;
        publishedLogo = station.logo;
        if (foregroundStarted) {
            refreshNotification();
        }
    }

    private Bitmap loadMediaArtwork(
            FavoriteStation station,
            String displayName
    ) {
        Bitmap source = null;
        File customLogo = FavoriteLogoStore.fileForToken(this, station.logo);
        if (customLogo != null && customLogo.isFile()) {
            source = BitmapFactory.decodeFile(customLogo.getAbsolutePath());
        }
        if (source == null) {
            int resource = StationLogoResolver.resolveForStation(
                    station.logo, false, false, displayName);
            if (resource != 0) {
                source = BitmapFactory.decodeResource(getResources(), resource);
            }
        }
        if (source == null) {
            return null;
        }
        int largest = Math.max(source.getWidth(), source.getHeight());
        if (largest <= MEDIA_ARTWORK_SIZE_PX) {
            return source;
        }
        float scale = MEDIA_ARTWORK_SIZE_PX / (float) largest;
        Bitmap scaled = Bitmap.createScaledBitmap(
                source,
                Math.max(1, Math.round(source.getWidth() * scale)),
                Math.max(1, Math.round(source.getHeight() * scale)),
                true
        );
        if (scaled != source) {
            source.recycle();
        }
        return scaled;
    }

    private FavoriteStation findStoredStation(int band, int frequency) {
        FavoriteStation favorite = favoriteStore.find(band, frequency);
        if (favorite != null) {
            return favorite;
        }
        return stationStore.find(band, frequency);
    }

    private FavoriteStation findStationByName(String query) {
        String cleanQuery = RadioMetadataReader.clean(query)
                .toLowerCase(Locale.ROOT);
        if (cleanQuery.isEmpty()) {
            return null;
        }
        ArrayList<FavoriteStation> candidates = new ArrayList<>(
                favoriteStore.load()
        );
        for (FavoriteStation station : stationStore.load()) {
            if (!candidates.contains(station)) {
                candidates.add(station);
            }
        }
        FavoriteStation partial = null;
        for (FavoriteStation station : candidates) {
            String name = station.name.toLowerCase(Locale.ROOT);
            if (name.equals(cleanQuery)) {
                return station;
            }
            if (partial == null && name.contains(cleanQuery)) {
                partial = station;
            }
        }
        return partial;
    }

    private MediaItem createBrowsableItem(
            String mediaId,
            String title,
            String subtitle
    ) {
        MediaDescription description = new MediaDescription.Builder()
                .setMediaId(mediaId)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIconUri(resourceUri(R.drawable.ic_radio_launcher_art))
                .build();
        return new MediaItem(description, MediaItem.FLAG_BROWSABLE);
    }

    private MediaItem createPlayableItem(FavoriteStation station) {
        return new MediaItem(
                createStationDescription(station),
                MediaItem.FLAG_PLAYABLE
        );
    }

    private MediaDescription createStationDescription(FavoriteStation station) {
        String name = station.name.isEmpty()
                ? station.frequencyLabel()
                : AppLanguage.stationName(this, station.name);
        MediaDescription.Builder description = new MediaDescription.Builder()
                .setMediaId(MediaStationId.encode(station))
                .setTitle(name)
                .setSubtitle(station.bandLabel() + " \u2022 " + station.frequencyLabel());
        int logo = StationLogoResolver.resolveForStation(station.logo, false, false, name);
        description.setIconUri(resourceUri(logo == 0 ? R.drawable.ic_radio : logo));
        return description.build();
    }

    private Uri resourceUri(int resource) {
        return Uri.parse(
                "android.resource://" + getPackageName() + "/" + resource
        );
    }

    private void notifyMediaLibraryChangedInternal() {
        notifyChildrenChanged(MEDIA_FAVORITES_ID);
        notifyChildrenChanged(MEDIA_STATIONS_ID);
        refreshMediaQueue();
        if (publishedMediaBand >= 0) {
            updateMediaMetadata(
                    publishedMediaBand,
                    publishedMediaFrequency,
                    publishedMediaName,
                    publishedRadioText
            );
        }
    }

    private void refreshMediaQueue() {
        if (mediaSession == null) {
            return;
        }
        List<FavoriteStation> favorites = stationNavigationStore.load();
        ArrayList<MediaSession.QueueItem> queue = new ArrayList<>(
                favorites.size()
        );
        for (FavoriteStation station : favorites) {
            queue.add(new MediaSession.QueueItem(
                    createStationDescription(station),
                    stationQueueId(station)
            ));
        }
        mediaSession.setQueue(queue);
        mediaSession.setQueueTitle(stationNavigationStore.favoritesSelected()
                ? tr("Suosikit", "Favorites") : tr("Asemalista", "Station list"));
    }

    private long stationQueueId(FavoriteStation station) {
        return ((long) station.band << 32)
                | (station.frequency & 0xffffffffL);
    }

    private FavoriteStation stationForQueueId(long queueId) {
        for (FavoriteStation station : stationNavigationStore.load()) {
            if (stationQueueId(station) == queueId) {
                return station;
            }
        }
        return null;
    }

    private void createMediaSession() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent open = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        mediaButtonIntent.setComponent(new ComponentName(
                this,
                RadioMediaButtonReceiver.class
        ));
        int mediaButtonFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android fills the KeyEvent extra when it sends this PendingIntent.
            mediaButtonFlags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent mediaButton = PendingIntent.getBroadcast(
                this,
                2,
                mediaButtonIntent,
                mediaButtonFlags
        );

        mediaSession = new MediaSession(this, "RadioPlusSteeringControls");
        mediaSession.setSessionActivity(open);
        mediaSession.setMediaButtonReceiver(mediaButton);
        mediaSession.setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                        | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
        );
        mediaSession.setPlaybackToLocal(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                KeyEvent event = mediaButtonIntent == null
                        ? null
                        : mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (event != null) SteeringDiagnosticTrace.get().key("media-session", event.getKeyCode(),
                        event.getAction(), event.getMetaState(), event.getRepeatCount(),
                        event.getScanCode(), event.getFlags(), event.getSource(), event.getDownTime(), event.getEventTime());
                if (event != null && shouldIgnoreRawMediaKeys()) {
                    resetRawMediaKeyDecisions();
                    // Do not call super: it translates raw STOP/NEXT into transport
                    // callbacks. NWD emits STOP itself while selecting source 4.
                    return false;
                }
                if (handleMediaKeyEvent(event)) {
                    return true;
                }
                return super.onMediaButtonEvent(mediaButtonIntent);
            }

            @Override
            public void onPlay() {
                requestPlayback(true);
            }

            @Override
            public void onPause() {
                pausePlayback();
            }

            @Override
            public void onStop() {
                pausePlayback();
            }

            @Override
            public void onSkipToNext() {
                SteeringDiagnosticTrace.get().event("transport", "next");
                selectAdjacentStation(true);
            }

            @Override
            public void onSkipToPrevious() {
                SteeringDiagnosticTrace.get().event("transport", "previous");
                selectAdjacentStation(false);
            }

            @Override
            public void onSkipToQueueItem(long id) {
                FavoriteStation station = stationForQueueId(id);
                if (station != null) {
                    queueStation(station);
                }
            }

            @Override
            public void onFastForward() {
                SteeringDiagnosticTrace.get().event("transport", "fast-forward");
                selectAdjacentStation(true);
            }

            @Override
            public void onRewind() {
                SteeringDiagnosticTrace.get().event("transport", "rewind");
                selectAdjacentStation(false);
            }

            @Override
            public void onPlayFromMediaId(String mediaId, Bundle extras) {
                FavoriteStation station = MediaStationId.decode(mediaId);
                if (station != null) {
                    queueStation(station);
                }
            }

            @Override
            public void onPrepareFromMediaId(String mediaId, Bundle extras) {
                FavoriteStation station = MediaStationId.decode(mediaId);
                if (station != null) {
                    FavoriteStation stored = findStoredStation(
                            station.band,
                            station.frequency
                    );
                    FavoriteStation target = stored == null ? station : stored;
                    updateMediaMetadata(
                            target.band,
                            target.frequency,
                            target.name,
                            ""
                    );
                }
            }

            @Override
            public void onPlayFromSearch(String query, Bundle extras) {
                FavoriteStation station = findStationByName(query);
                if (station != null) {
                    queueStation(station);
                }
            }
        }, mainHandler);
        setSessionToken(mediaSession.getSessionToken());
        mediaSession.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Radio+")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Autoradio")
                .build());
        mediaSession.setActive(true);
        // Publish our state after activation. This does not prove the ROM
        // routes steering-wheel media buttons to this session.
        updatePlaybackState();
        refreshMediaQueue();
    }

    private boolean handleMediaKeyEvent(KeyEvent event) {
        if (event == null) {
            return false;
        }
        if (shouldIgnoreRawMediaKeys()) {
            resetRawMediaKeyDecisions();
            return false;
        }
        if (radio == null) {
            // A cold raw-key start must identify the backend BEFORE changing
            // playback state. In particular, a vendor startup STOP is not Pause.
            long epoch = playbackEpoch;
            RadioApiFactory.detect(this, detection -> {
                if (stopping || playbackEpoch != epoch) return;
                if (detection.profile == RadioBackendProfile.UNKNOWN || shouldIgnoreRawMediaKeys()) {
                    resetRawMediaKeyDecisions();
                    return;
                }
                handleResolvedMediaKeyEvent(event);
            });
            return true;
        }
        return handleResolvedMediaKeyEvent(event);
    }

    static boolean shouldIgnoreRawMediaKeys() {
        RadioPlaybackService current = runningInstance;
        return RadioApiFactory.selectedUsesNwd()
                || (current != null && (current.radio instanceof NwdRadioApi
                || (current.boundDetection != null && current.boundDetection.profile.isNwd())));
    }

    private void resetRawMediaKeyDecisions() {
        mainHandler.removeCallbacks(pendingShortMediaTask);
        mediaKeyPressTracker.reset();
    }

    private boolean handleResolvedMediaKeyEvent(KeyEvent event) {
        if (shouldIgnoreRawMediaKeys()) {
            resetRawMediaKeyDecisions();
            return false;
        }
        // Called only by the MEDIA_BUTTON intent or MediaSession callback.
        // Window/accessibility input keeps the strict supportsMediaKey filter.
        int keyCode = MediaButtonKeyMapping.commandKeyCode(
                event.getKeyCode(), event.getMetaState());
        SteeringDiagnosticTrace.get().key("handler", event.getKeyCode(),
                event.getAction(), event.getMetaState(), event.getRepeatCount(),
                event.getScanCode(), event.getFlags(), event.getSource(), event.getDownTime(), event.getEventTime());
        Log.i(TAG, "Media button received: raw=" + event.getKeyCode()
                + " meta=" + event.getMetaState() + " command=" + keyCode
                + " action=" + event.getAction() + " repeat=" + event.getRepeatCount());
        if (!supportsMediaKey(keyCode)) {
            SteeringDiagnosticTrace.get().event("handler", "unsupported=" + keyCode);
            return false;
        }
        if (event.isCanceled()) {
            SteeringDiagnosticTrace.get().event("handler", "canceled");
            mediaKeyPressTracker.cancel(keyCode, event.getDownTime());
            scheduleShortMediaKey();
            return true;
        }
        if (!mediaKeyEvents.accept(keyCode, event.getAction(), event.getDownTime(),
                event.getEventTime(), event.getRepeatCount())) {
            SteeringDiagnosticTrace.get().event("handler", "duplicate");
            return true;
        }
        Log.i(TAG, "Media key received: code=" + keyCode
                + " action=" + event.getAction()
                + " repeat=" + event.getRepeatCount());
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            boolean longPress = event.isLongPress() || event.getRepeatCount() > 0;
            MediaKeyPressTracker.Decision decision = mediaKeyPressTracker.onDown(
                    keyCode, longPress, event.getRepeatCount(),
                    SystemClock.elapsedRealtime(), event.getDownTime());
            handleTrackedMediaDecision(decision);
            scheduleShortMediaKey();
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_UP) {
            return true;
        }
        handleTrackedMediaDecision(mediaKeyPressTracker.onUp(
                keyCode, SystemClock.elapsedRealtime(), event.getDownTime()));
        scheduleShortMediaKey();
        return true;
    }

    private void scheduleShortMediaKey() {
        if (shouldIgnoreRawMediaKeys()) {
            resetRawMediaKeyDecisions();
            return;
        }
        mainHandler.removeCallbacks(pendingShortMediaTask);
        long delay = mediaKeyPressTracker.nextFallbackDelay(
                SystemClock.elapsedRealtime(), mediaKeyFallbackTimeout());
        if (delay >= 0L) mainHandler.postDelayed(pendingShortMediaTask, delay);
    }

    private long mediaKeyFallbackTimeout() {
        return ViewConfiguration.getLongPressTimeout() + MEDIA_LONG_PRESS_GRACE_MS;
    }

    private void handleTrackedMediaDecision(MediaKeyPressTracker.Decision decision) {
        if (shouldIgnoreRawMediaKeys()) {
            resetRawMediaKeyDecisions();
            return;
        }
        if (decision == null || decision.action == MediaKeyPressTracker.Action.NONE) {
            return;
        }
        SteeringDiagnosticTrace.get().event("tracker", decision.action.name() + " code=" + decision.keyCode);
        if (decision.action == MediaKeyPressTracker.Action.DISPATCH_LONG) {
            // CAN adapters may flag even brief presses as repeats. Media skip
            // always means an adjacent saved station, never a frequency search.
            dispatchShortMediaKey(decision.keyCode);
            return;
        }
        if (decision.action == MediaKeyPressTracker.Action.DISPATCH_SHORT) {
            dispatchShortMediaKey(decision.keyCode);
        }
    }

    private void dispatchShortMediaKey(int keyCode) {
        SteeringDiagnosticTrace.get().event("command", "code=" + keyCode);
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KEYCODE_JUNSUN_TUNER_NEXT:
            case KEYCODE_JUNSUN_SKIP_NEXT:
                selectAdjacentStation(true);
                break;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KEYCODE_JUNSUN_TUNER_PREVIOUS:
            case KEYCODE_JUNSUN_SKIP_PREVIOUS:
                selectAdjacentStation(false);
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                requestPlayback(true);
                break;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                pausePlayback();
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                togglePlayback();
                break;
            default:
                return;
        }
    }

    static boolean supportsMediaKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KEYCODE_JUNSUN_TUNER_NEXT:
            case KEYCODE_JUNSUN_TUNER_PREVIOUS:
            case KEYCODE_JUNSUN_SKIP_NEXT:
            case KEYCODE_JUNSUN_SKIP_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                return true;
            default:
                return false;
        }
    }

    static boolean isNextMediaKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_MEDIA_NEXT
                || keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                || keyCode == KEYCODE_JUNSUN_TUNER_NEXT
                || keyCode == KEYCODE_JUNSUN_SKIP_NEXT;
    }

    static boolean isPreviousMediaKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS
                || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND
                || keyCode == KEYCODE_JUNSUN_TUNER_PREVIOUS
                || keyCode == KEYCODE_JUNSUN_SKIP_PREVIOUS;
    }

    private void setPlaybackRequested(boolean requested) {
        playbackRequestRevision++;
        if (requested) {
            pendingNwdPause = null;
            stopAfterNwdPause = false;
            abandonPauseAudioFocus();
        }
        if (playbackRequested != requested) playbackEpoch++;
        playbackRequested = requested;
        playbackOwnership.reset(SystemClock.elapsedRealtime());
        if (!requested) {
            cancelRoutingClaim();
            mainHandler.removeCallbacks(startupVolumePolicyTask);
            pendingWidgetStation = null;
            pendingRadioCommandName = "";
            pendingRadioCommand = null;
        }
        if (radioVolumeGuard != null) {
            radioVolumeGuard.setActive(requested
                    && RadioApiFactory.supportsSeparateAudioFocus(radio));
        }
        updatePlaybackState();
        if (foregroundStarted) {
            refreshNotification();
        }
    }

    private void updatePlaybackState() {
        if (mediaSession == null) {
            return;
        }
        long actions = PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_STOP
                | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM
                | PlaybackState.ACTION_FAST_FORWARD
                | PlaybackState.ACTION_REWIND;
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(
                        advertisedPlaybackState(),
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        playbackRequested ? 1f : 0f,
                        SystemClock.elapsedRealtime()
                )
                .build());
    }

    private int advertisedPlaybackState() {
        return playbackRequested ? PlaybackState.STATE_PLAYING
                : pendingNwdPause != null ? PlaybackState.STATE_BUFFERING
                : PlaybackState.STATE_PAUSED;
    }

    private void refreshNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, createNotification());
        }
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                tr("Radion taustatoisto", "Radio background playback"),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(tr(
                "Pitää autoradion äänen käynnissä sovelluksen taustalla",
                "Keeps the car radio playing while the app is in the background"
        ));
        channel.setSound(null, null);
        manager.createNotificationChannel(channel);
    }

    private Notification createNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent open = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_radio)
                .setContentTitle(publishedMediaName.isEmpty()
                        ? "Radio+"
                        : publishedMediaName)
                .setContentText(notificationSubtitle())
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .addAction(notificationAction(
                        R.drawable.ic_previous,
                        tr("Edellinen asema", "Previous station"),
                        ACTION_MEDIA_PREVIOUS,
                        10
                ))
                .addAction(notificationAction(
                        playbackRequested ? R.drawable.ic_pause : R.drawable.ic_play,
                        playbackRequested
                                ? tr("Tauko", "Pause")
                                : tr("Toista", "Play"),
                        ACTION_MEDIA_TOGGLE,
                        11
                ))
                .addAction(notificationAction(
                        R.drawable.ic_next,
                        tr("Seuraava asema", "Next station"),
                        ACTION_MEDIA_NEXT,
                        12
                ))
                .addAction(notificationAction(
                        R.drawable.ic_radio,
                        tr("Lopeta", "Stop"),
                        ACTION_STOP,
                        13
                ));
        if (mediaSession != null) {
            builder.setStyle(new Notification.MediaStyle()
                    .setMediaSession(mediaSession.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2));
        }
        return builder.build();
    }

    private String tr(String finnish, String english) {
        return AppLanguage.text(this, finnish, english);
    }

    private String notificationSubtitle() {
        if (!publishedRadioText.isEmpty()) {
            return publishedRadioText;
        }
        if (FrequencyRules.isValid(publishedMediaBand, publishedMediaFrequency)) {
            if (!FrequencyRules.isFm(publishedMediaBand)) {
                return String.format(
                        Locale.US,
                        "AM - %d kHz",
                        publishedMediaFrequency
                );
            }
            return String.format(
                    Locale.US,
                    "FM - %.1f MHz",
                    publishedMediaFrequency / 100f
            );
        }
        return playbackRequested
                ? tr("Radio soi taustalla", "Radio is playing in the background")
                : tr("Radio on tauolla", "Radio is paused");
    }

    private Notification.Action notificationAction(
            int icon,
            String title,
            String action,
            int requestCode
    ) {
        Intent intent = new Intent(this, RadioPlaybackService.class);
        intent.setAction(action);
        PendingIntent pendingIntent = PendingIntent.getService(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        return new Notification.Action.Builder(icon, title, pendingIntent).build();
    }

    private interface RadioCommand {
        void run(IRadioServiceAPI service, long epoch) throws RemoteException;
    }
}
