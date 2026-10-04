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
    private static final long[] STEERING_SESSION_PROMOTION_DELAYS_MS = {
            0L,
            250L,
            750L,
            1500L,
            3000L,
            5000L
    };
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
    private boolean stopping;
    private boolean foregroundStarted;
    // A MediaBrowser client (for example the Junsun launcher) may bind this
    // service without asking the radio to play. Never acquire the vendor's
    // global audio focus until an explicit app, widget or media-key command.
    private boolean playbackRequested;
    // FMPlugService acquires its own singleton AudioFocusRequest in onCreate().
    // Track only a focus release explicitly requested through Radio+'s pause
    // control; never pretend that Radio+ owns the vendor service's request.
    private final OemFocusSessionState oemFocusState = new OemFocusSessionState();
    private volatile boolean oemRouteActive;
    private final MediaKeyPressTracker mediaKeyPressTracker =
            new MediaKeyPressTracker();
    private final MediaKeyEventDeduplicator mediaKeyEvents = new MediaKeyEventDeduplicator();
    private IRadioServiceAPI radio;
    private IBinder radioBinder;
    private long rebindDelayMs = MIN_REBIND_DELAY_MS;
    private FavoriteStore favoriteStore;
    private StationStore stationStore;
    private volatile FavoriteStation pendingWidgetStation;
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
    private final Runnable steeringSessionPromotionTask =
            this::promoteMediaSessionForSteeringControls;
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
            refreshMediaMetadataFromRadio();
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
                SystemClock.elapsedRealtime()
        ));
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
            radio = IRadioServiceAPI.Stub.asInterface(binder);
            oemRouteActive = false;
            oemFocusState.onServiceConnected();
            try {
                binder.linkToDeath(radioDeathRecipient, 0);
            } catch (RemoteException error) {
                handleOemConnectionLoss("binder died while connecting");
                return;
            }
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

    static void tuneStation(Context context, FavoriteStation station) {
        if (station == null
                || !FrequencyRules.isValid(station.band, station.frequency)) {
            return;
        }
        RadioPlaybackService current = runningInstance;
        if (current != null && !current.stopping) {
            current.mainHandler.post(() -> current.queueStation(station));
            return;
        }
        Intent service = new Intent(context, RadioPlaybackService.class);
        service.setAction(ACTION_TUNE_STATION);
        service.putExtra(EXTRA_STATION_BAND, station.band);
        service.putExtra(EXTRA_STATION_FREQUENCY, station.frequency);
        service.putExtra(EXTRA_STATION_NAME, station.name);
        context.startForegroundService(service);
    }

    static void pause(Context context) {
        Intent service = new Intent(context, RadioPlaybackService.class)
                .setAction(ACTION_PAUSE_PLAYBACK);
        context.startForegroundService(service);
    }

    static boolean isPlaybackRequested() {
        RadioPlaybackService current = runningInstance;
        return current != null && !current.stopping && current.playbackRequested;
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
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        radioVolumeGuard = new RadioVolumeGuard();
        registerAccWakeMonitor();
        createNotificationChannel();
        createMediaSession();
        // The ROM routes analog FM audio itself. Creating even a silent MEDIA
        // AudioTrack here can take that route away from the tuner on Junsun
        // units. Keep key routing independent of the physical audio route.
        Log.i(TAG, "MediaSession active without a PCM audio route");
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
            selectFavorite(false);
            return START_NOT_STICKY;
        }
        if (ACTION_MEDIA_TOGGLE.equals(action)) {
            togglePlayback();
            return START_NOT_STICKY;
        }
        if (ACTION_MEDIA_NEXT.equals(action)) {
            selectFavorite(true);
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
        if (stopping || bound) {
            return;
        }
        mainHandler.removeCallbacks(rebindTask);
        Intent intent = new Intent(RadioBackendContract.SERVICE_ACTION);
        intent.setComponent(RadioBackendContract.SERVICE_COMPONENT);
        try {
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
        executeRadioCommand("play", current -> {
            activateOemPlayback(current, explicitTakeover);
            Log.i(TAG, "Stock radio background playback requested");
        });
    }

    private void pausePlayback() {
        setPlaybackRequested(false);
        // FMPlugService.releaseAudioFocus() only abandons the stock request; it
        // does not call RadioPlayer.setMute(true). Taking media focus first
        // makes the stock focus listener run its real mute path, exactly as it
        // does when switching from radio to another media source.
        boolean focusTaken = takePauseAudioFocus();
        // Direct framework mute is independent of the asynchronous OEM Binder
        // connection, so a tap also stops audio during service reconnection.
        boolean directMuteSent = playbackHealthReader.setMuted(true);
        if (focusTaken) {
            // Give the stock process time to handle AUDIOFOCUS_LOSS before the
            // fallback release. Calling release first bypasses its mute code.
            mainHandler.postDelayed(this::releasePlaybackFocus, 250L);
            mainHandler.postDelayed(this::abandonPauseAudioFocus,
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

    private void reassertPausedRoute() {
        if (stopping || playbackRequested) {
            return;
        }
        boolean muted = playbackHealthReader.setMuted(true);
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
        executeRadioCommand("pause", current -> {
            if (!oemFocusState.wasFocusReleasedByRadioPlus()) {
                current.releaseAudioFocus();
                oemFocusState.markFocusReleasedByRadioPlus();
            }
            boolean muted = playbackHealthReader.setMuted(true);
            oemRouteActive = false;
            Log.i(TAG, muted
                    ? "Stock radio playback paused and tuner muted"
                    : "Stock radio playback paused through OEM audio focus");
        });
    }

    private void activateOemPlayback(
            IRadioServiceAPI current,
            boolean explicitTakeover
    )
            throws RemoteException {
        if (OemFocusInteropPolicy.shouldReplaceFocusRequest(
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
        RadioPlaybackHealthReader.Snapshot snapshot = playbackHealthReader.read();
        boolean requestFocus = oemFocusState.shouldRequestFocus(
                snapshot.sourceKnown,
                snapshot.radioOwnsSource(),
                snapshot.muteKnown,
                snapshot.muted
        );
        if (OemFocusInteropPolicy.shouldRequestRoute(oemRouteActive, requestFocus)) {
            // Match the stock RadioAppManager: select the analog radio source
            // before asking FMPlugService to unmute it through audio focus.
            oemRouteActive = current.requestPlayAudio();
            oemFocusState.markRouteRequested();
            Log.i(TAG, oemRouteActive
                    ? "Stock radio audio route active"
                    : "Stock radio audio route activation was rejected");
        }
        if (requestFocus) {
            // requestAudioFocus() is also the vendor's unmute operation. RDS
            // can keep updating while this step is missing, which is why a
            // metadata-only success must not be treated as active playback.
            current.requestAudioFocus();
            playbackHealthReader.setMuted(false);
            oemFocusState.markFocusResumed();
            Log.i(TAG, snapshot.muteKnown && snapshot.muted
                    ? "Stock radio audio focus resumed from OEM mute"
                    : "Stock radio audio focus activated for this session");
        }
        // FMPlugService activates the stock radio's own MediaSession when its
        // audio focus request succeeds. Re-promote Radio+ only after that
        // synchronous vendor call, otherwise steering-wheel media keys remain
        // targeted at the stock receiver even though Radio+ is visible.
        scheduleSteeringSessionPromotion();
        scheduleStartupVolumePolicy(requestFocus);
    }

    private void scheduleSteeringSessionPromotion() {
        mainHandler.removeCallbacks(steeringSessionPromotionTask);
        for (long delay : STEERING_SESSION_PROMOTION_DELAYS_MS) {
            mainHandler.postDelayed(steeringSessionPromotionTask, delay);
        }
    }

    private void scheduleStartupVolumePolicy(boolean focusRequested) {
        // The stock V7 radio restores the shared FM input path to 100% in its
        // delayed 500 ms focus callback. Restore normal gain once after that
        // route transition. There is no first-step attenuation anymore.
        mainHandler.removeCallbacks(startupVolumePolicyTask);
        if (focusRequested) {
            mainHandler.postDelayed(startupVolumePolicyTask, OEM_GAIN_SETTLE_MS);
        } else {
            mainHandler.post(startupVolumePolicyTask);
        }
    }

    private void reassertRadioVolumePolicy() {
        if (radioVolumeGuard != null && playbackRequested && !stopping) {
            radioVolumeGuard.reassertNow();
        }
    }

    private void applyRadioVolumePolicy() {
        if (radioVolumeGuard != null && playbackRequested && !stopping) {
            radioVolumeGuard.applyNow();
        }
    }

    private void promoteMediaSessionForSteeringControls() {
        if (mediaSession == null || stopping || !playbackRequested) {
            return;
        }
        mediaSession.setActive(false);
        mediaSession.setActive(true);
        updatePlaybackState();
        Log.i(TAG, "Radio+ MediaSession promoted after OEM audio activation");
    }

    private void activateForTunerCommand(IRadioServiceAPI current)
            throws RemoteException {
        // Selecting, seeking or tuning a station explicitly leaves the
        // currently playing media source and makes the radio audible.
        activateOemPlayback(current, true);
    }

    private void resetOemPlaybackOwnership() {
        oemRouteActive = false;
        oemFocusState.onServiceDisconnected();
    }

    private void togglePlayback() {
        Log.i(TAG, "Media command: play/pause");
        if (playbackRequested) {
            pausePlayback();
        } else {
            requestPlayback(true);
        }
    }

    private void selectFavorite(boolean next) {
        Log.i(TAG, next
                ? "Media command: next preset"
                : "Media command: previous preset");
        setPlaybackRequested(true);
        executeRadioCommand(next ? "next preset" : "previous preset", current -> {
            activateForTunerCommand(current);
            int band = normalizeBand(current.getCurrentBand());
            int frequency = current.getCurrentFreq();
            List<FavoriteStation> favorites = favoriteStore.load();
            FavoriteStation target = FavoriteNavigator.selectInStoredOrder(
                    favorites,
                    band,
                    frequency,
                    next
            );
            if (target == null) {
                // Mirrors the stock Junsun radio: if no preset exists, use seek.
                if (next) {
                    current.onSeekDownEvent();
                } else {
                    current.onSeekUpEvent();
                }
            } else {
                boolean tuned = tuneTo(current, target);
                if (tuned) {
                    oemRouteActive = current.requestPlayAudio();
                    mainHandler.post(this::scheduleSteeringSessionPromotion);
                    mainHandler.post(this::refreshMediaMetadataFromRadio);
                    Log.i(TAG, "Media preset confirmed: " + target.key());
                } else {
                    mainHandler.post(this::refreshMediaMetadataFromRadio);
                    Log.w(TAG, "Media preset was not confirmed: " + target.key());
                }
            }
            if (target == null) {
                oemRouteActive = current.requestPlayAudio();
                mainHandler.post(this::scheduleSteeringSessionPromotion);
            }
        });
    }

    private void seekStation(boolean higher) {
        Log.i(TAG, higher
                ? "Media command: seek higher"
                : "Media command: seek lower");
        setPlaybackRequested(true);
        executeRadioCommand(higher ? "seek higher" : "seek lower", current -> {
            activateForTunerCommand(current);
            // The Junsun API names are reversed relative to the displayed
            // frequency: seekDown raises and seekUp lowers the frequency.
            if (higher) {
                current.onSeekDownEvent();
            } else {
                current.onSeekUpEvent();
            }
            oemRouteActive = current.requestPlayAudio();
            mainHandler.post(this::scheduleSteeringSessionPromotion);
        });
    }

    private void queueWidgetStation(Intent intent) {
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
        ));
    }

    private void queueStation(FavoriteStation station) {
        if (station == null
                || !FrequencyRules.isValid(station.band, station.frequency)) {
            return;
        }
        FavoriteStation stored = findStoredStation(station.band, station.frequency);
        pendingWidgetStation = stored == null ? station : stored;
        setPlaybackRequested(true);
        if (!bound && !stopping) {
            bindOemRadio();
        }
        tunePendingWidgetStation();
    }

    private void tunePendingWidgetStation() {
        FavoriteStation target = pendingWidgetStation;
        if (target == null) {
            return;
        }
        executeRadioCommand("widget station", current -> {
            activateForTunerCommand(current);
            boolean tuned = tuneTo(current, target);
            if (tuned) {
                oemRouteActive = current.requestPlayAudio();
                mainHandler.post(this::scheduleSteeringSessionPromotion);
            }
            mainHandler.post(this::refreshMediaMetadataFromRadio);
            mainHandler.post(() -> {
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

    private boolean tuneTo(IRadioServiceAPI current, FavoriteStation target)
            throws RemoteException {
        int normalizedTarget = normalizeBand(target.band);
        int currentBand = normalizeBand(current.getCurrentBand());
        boolean commandIssued;
        if (currentBand == normalizedTarget) {
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
                    current.onBandEvent();
                    observedBand = current.getCurrentBand();
                    guard++;
                }
                if (normalizeBand(observedBand) == normalizedTarget) {
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
                Log.w(TAG, "Tuner did not confirm " + target.key()
                        + "; retry " + (attempt + 1)
                        + " (actual=" + observedBand + ":" + observedFrequency + ")");
                if (!tunerMetadataReader.tuneToBand(
                        normalizedTarget,
                        target.frequency
                )) {
                    current.gotoFreq(target.frequency);
                }
            }
        }
        return false;
    }

    private int normalizeBand(int band) {
        return band >= 3 ? 3 : Math.max(0, band);
    }

    private void executeRadioCommand(String command, RadioCommand action) {
        IRadioServiceAPI current = radio;
        if (current == null || stopping) {
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
        try {
            executor.submit(() -> {
                try {
                    action.run(current);
                } catch (RemoteException | RuntimeException | LinkageError error) {
                    Log.w(TAG, "Stock radio command failed: " + command, error);
                    mainHandler.post(() -> {
                        if (radio == current && !stopping) {
                            handleOemConnectionLoss("command failed: " + command);
                        }
                    });
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Service shutdown raced with a vendor callback.
        }
    }

    private void refreshMediaMetadataFromRadio() {
        if (radio == null || stopping) {
            return;
        }
        executeRadioCommand("media metadata", current -> {
            int band = normalizeBand(current.getCurrentBand());
            int frequency = current.getCurrentFreq();
            if (!FrequencyRules.isValid(band, frequency)) {
                return;
            }
            String rdsName = RadioMetadataReader.clean(
                    current.getCurrentFreqRdsPs()
            );
            mainHandler.post(() -> updateMediaMetadata(
                    band,
                    frequency,
                    rdsName,
                    ""
            ));
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
        List<FavoriteStation> favorites = favoriteStore.load();
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
        mediaSession.setQueueTitle("Radio+ suosikit");
    }

    private long stationQueueId(FavoriteStation station) {
        return ((long) station.band << 32)
                | (station.frequency & 0xffffffffL);
    }

    private FavoriteStation stationForQueueId(long queueId) {
        for (FavoriteStation station : favoriteStore.load()) {
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
                selectFavorite(true);
            }

            @Override
            public void onSkipToPrevious() {
                selectFavorite(false);
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
                selectFavorite(true);
            }

            @Override
            public void onRewind() {
                selectFavorite(false);
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
        // Publish PLAYING only after activation so Android promotes this
        // session as the current steering-wheel media-button target.
        updatePlaybackState();
        refreshMediaQueue();
    }

    private boolean handleMediaKeyEvent(KeyEvent event) {
        if (event == null || !supportsMediaKey(event.getKeyCode())) {
            return false;
        }
        int keyCode = event.getKeyCode();
        if (event.isCanceled()) {
            mainHandler.removeCallbacks(pendingShortMediaTask);
            mediaKeyPressTracker.reset();
            return true;
        }
        if (!mediaKeyEvents.accept(keyCode, event.getAction(), event.getDownTime(),
                event.getEventTime(), event.getRepeatCount())) {
            return true;
        }
        Log.i(TAG, "Media key received: code=" + keyCode
                + " action=" + event.getAction()
                + " repeat=" + event.getRepeatCount());
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            boolean longPress = event.isLongPress() || event.getRepeatCount() > 0;
            MediaKeyPressTracker.Decision decision = mediaKeyPressTracker.onDown(
                    keyCode, longPress, event.getRepeatCount(), SystemClock.elapsedRealtime());
            if (decision.action == MediaKeyPressTracker.Action.SCHEDULE_SHORT) {
                scheduleShortMediaKey();
            } else {
                mainHandler.removeCallbacks(pendingShortMediaTask);
                handleTrackedMediaDecision(decision);
            }
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_UP) {
            return true;
        }
        mainHandler.removeCallbacks(pendingShortMediaTask);
        handleTrackedMediaDecision(mediaKeyPressTracker.onUp(keyCode, SystemClock.elapsedRealtime()));
        return true;
    }

    private void scheduleShortMediaKey() {
        mainHandler.removeCallbacks(pendingShortMediaTask);
        mainHandler.postDelayed(
                pendingShortMediaTask,
                ViewConfiguration.getLongPressTimeout() + MEDIA_LONG_PRESS_GRACE_MS
        );
    }

    private void handleTrackedMediaDecision(MediaKeyPressTracker.Decision decision) {
        if (decision == null || decision.action == MediaKeyPressTracker.Action.NONE) {
            return;
        }
        if (decision.action == MediaKeyPressTracker.Action.DISPATCH_LONG) {
            if (isNextMediaKey(decision.keyCode)) {
                seekStation(true);
            } else if (isPreviousMediaKey(decision.keyCode)) {
                seekStation(false);
            } else {
                dispatchShortMediaKey(decision.keyCode);
            }
            return;
        }
        if (decision.action == MediaKeyPressTracker.Action.DISPATCH_SHORT) {
            dispatchShortMediaKey(decision.keyCode);
        }
    }

    private void dispatchShortMediaKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KEYCODE_JUNSUN_TUNER_NEXT:
            case KEYCODE_JUNSUN_SKIP_NEXT:
                selectFavorite(true);
                break;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KEYCODE_JUNSUN_TUNER_PREVIOUS:
            case KEYCODE_JUNSUN_SKIP_PREVIOUS:
                selectFavorite(false);
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
        playbackRequested = requested;
        if (!requested) {
            pendingRadioCommandName = "";
            pendingRadioCommand = null;
        }
        if (radioVolumeGuard != null) {
            radioVolumeGuard.setActive(requested);
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
                        playbackRequested
                                ? PlaybackState.STATE_PLAYING
                                : PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        playbackRequested ? 1f : 0f,
                        SystemClock.elapsedRealtime()
                )
                .build());
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
        void run(IRadioServiceAPI service) throws RemoteException;
    }
}
