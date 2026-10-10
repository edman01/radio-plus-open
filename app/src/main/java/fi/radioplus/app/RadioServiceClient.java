package fi.radioplus.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import com.hcn.autoradio.IRadioServiceAPI;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class RadioServiceClient {
    private static final String TAG = "RadioServiceClient";
    private static final long REBIND_DELAY_MS = 1500L;
    private static final long BIND_TIMEOUT_MS = 6000L;
    private static final long RADIO_TEXT_TTL_MS = 6000L;
    interface Listener {
        void onConnectionChanged(boolean connected, String message);

        void onStateChanged(RadioState state);

        void onRadioError(String message);
    }

    interface RemoteAction {
        void run(IRadioServiceAPI service) throws RemoteException;
    }

    interface PresetResult {
        void onResult(int band, int[] frequencies, boolean vendorMetadataAvailable);
    }

    static final class RadioState {
        final int band;
        final int frequency;
        final String rdsName;
        final String radioText;
        final String programType;
        final boolean rdsAvailable;
        final boolean stereo;
        final boolean localMode;
        final boolean autoScanning;
        final boolean scanning;
        final boolean seeking;
        final boolean oemFavorite;

        RadioState(
                int band,
                int frequency,
                String rdsName,
                String radioText,
                String programType,
                boolean rdsAvailable,
                boolean stereo,
                boolean localMode,
                boolean autoScanning,
                boolean scanning,
                boolean seeking,
                boolean oemFavorite
        ) {
            this.band = band;
            this.frequency = frequency;
            this.rdsName = RadioMetadataReader.clean(rdsName);
            this.radioText = RadioMetadataReader.clean(radioText);
            this.programType = RadioMetadataReader.clean(programType);
            this.rdsAvailable = rdsAvailable || !this.rdsName.isEmpty() || !this.radioText.isEmpty();
            this.stereo = stereo;
            this.localMode = localMode;
            this.autoScanning = autoScanning;
            this.scanning = scanning;
            this.seeking = seeking;
            this.oemFavorite = oemFavorite;
        }

        boolean hasSameContent(RadioState other) {
            return other != null
                    && band == other.band
                    && frequency == other.frequency
                    && rdsName.equals(other.rdsName)
                    && radioText.equals(other.radioText)
                    && programType.equals(other.programType)
                    && rdsAvailable == other.rdsAvailable
                    && stereo == other.stereo
                    && localMode == other.localMode
                    && autoScanning == other.autoScanning
                    && scanning == other.scanning
                    && seeking == other.seeking
                    && oemFavorite == other.oemFavorite;
        }
    }

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService actionExecutor = Executors.newSingleThreadExecutor();
    private final RadioMetadataReader metadataReader = new RadioMetadataReader();
    private final RdsTextStabilizer radioTextStabilizer = new RdsTextStabilizer();
    // Only poll workers take this lock. Lifecycle methods must remain responsive
    // while a remote read waits behind a scan, tune, or slow Binder transaction.
    private final Object pollingStateLock = new Object();

    private ScheduledExecutorService pollExecutor;
    private volatile long pollingGeneration;
    private long cachedPollingGeneration = -1L;
    private volatile IRadioServiceAPI service;
    private volatile boolean closed;
    private boolean bound;
    private boolean inspecting;
    private RadioApiFactory.Detection boundDetection;
    private int connectionGeneration;
    private boolean shouldBeBound;
    private int cachedMetadataBand = -1;
    private int cachedMetadataFrequency = -1;
    private String cachedRdsName = "";
    private String cachedRadioText = "";
    private String cachedProgramType = "";
    private long cachedRadioTextAt;
    private RadioState lastDispatchedState;

    private final Runnable rebindRunnable = this::bindInternal;
    private final Runnable connectionWatchdog = () -> {
        if (!closed && shouldBeBound && service == null) {
            recoverBinding();
        }
    };

    RadioServiceClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (closed || !shouldBeBound) {
                return;
            }
            mainHandler.removeCallbacks(rebindRunnable);
            mainHandler.removeCallbacks(connectionWatchdog);
            service = null;
            stopPolling();
            int generation = ++connectionGeneration;
            RadioApiFactory.resolve(context, boundDetection, binder, (api, detection) -> {
                if (closed || !shouldBeBound || !bound || generation != connectionGeneration) return;
                if (api == null) {
                    service = null;
                    String message = RadioApiFactory.unsupportedMessage(context);
                    notifyConnection(false, message);
                    notifyError(message);
                    return;
                }
                service = api;
                notifyConnection(true, tr("Radiolaitteisto yhdistetty", "Radio hardware connected")
                        + " (" + detection.profile.label + ")");
                startPolling();
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            connectionGeneration++;
            service = null;
            stopPolling();
            notifyConnection(false, tr(
                    "Radiolaitteiston yhteys katkesi",
                    "Radio hardware connection lost"
            ));
            mainHandler.removeCallbacks(connectionWatchdog);
            mainHandler.postDelayed(connectionWatchdog, REBIND_DELAY_MS);
        }

        @Override
        public void onBindingDied(ComponentName name) {
            connectionGeneration++;
            service = null;
            stopPolling();
            notifyConnection(false, tr(
                    "Radiolaitteisto käynnistyy uudelleen",
                    "Radio hardware is restarting"
            ));
            recoverBinding();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            connectionGeneration++;
            service = null;
            stopPolling();
            notifyConnection(false, tr(
                    "Radiolaitteisto ei tarjonnut ohjausrajapintaa",
                    "Radio hardware did not provide a control interface"
            ));
            recoverBinding();
        }
    };

    void bind() {
        if (closed) {
            return;
        }
        shouldBeBound = true;
        bindInternal();
    }

    private void bindInternal() {
        if (closed || !shouldBeBound || bound || inspecting) {
            return;
        }
        inspecting = true;
        int generation = ++connectionGeneration;
        RadioApiFactory.detect(context, detection -> {
            if (generation != connectionGeneration) return;
            inspecting = false;
            if (closed || !shouldBeBound) return;
            if (detection.profile == RadioBackendProfile.UNKNOWN) {
                String message = RadioApiFactory.unsupportedMessage(context);
                notifyConnection(false, message);
                notifyError(message);
                return;
            }
            bindRecognizedRadio(detection);
        });
    }

    private void bindRecognizedRadio(RadioApiFactory.Detection detection) {
        boundDetection = detection;
        Intent intent = RadioBackendContract.serviceIntent(detection.profile);
        try {
            if (detection.profile.isNwd()) context.startService(intent);
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!bound) {
                notifyConnection(false, tr(
                        "Vakioradion viritinpalvelua ei löytynyt",
                        "The stock radio tuner service was not found"
                ));
                scheduleRebind();
            } else {
                // Some Junsun builds return true from bindService even when
                // FMPlugService has wedged and never calls onServiceConnected.
                // Without a timeout the Activity stays disconnected forever.
                mainHandler.removeCallbacks(connectionWatchdog);
                mainHandler.postDelayed(connectionWatchdog, BIND_TIMEOUT_MS);
            }
        } catch (SecurityException error) {
            bound = false;
            Log.e(TAG, "Palveluun sitoutuminen estettiin", error);
            notifyConnection(false, tr(
                    "ROM esti radiolaitteiston käytön",
                    "The ROM blocked access to the radio hardware"
            ));
            scheduleRebind();
        } catch (RuntimeException error) {
            bound = false;
            Log.e(TAG, "Palveluun sitoutuminen epäonnistui", error);
            notifyConnection(false, tr(
                    "Radiolaitteisto ei ole käytettävissä",
                    "Radio hardware is unavailable"
            ));
            scheduleRebind();
        }
    }

    void unbind() {
        connectionGeneration++;
        inspecting = false;
        shouldBeBound = false;
        mainHandler.removeCallbacks(rebindRunnable);
        mainHandler.removeCallbacks(connectionWatchdog);
        stopPolling();
        if (bound) {
            try {
                context.unbindService(connection);
            } catch (IllegalArgumentException ignored) {
                // Palvelu on voinut kuolla ennen Activityn sulkeutumista.
            }
        }
        bound = false;
        service = null;
    }

    private void scheduleRebind() {
        if (closed || !shouldBeBound) {
            return;
        }
        mainHandler.removeCallbacks(rebindRunnable);
        mainHandler.postDelayed(rebindRunnable, REBIND_DELAY_MS);
    }

    private void recoverBinding() {
        if (closed || !shouldBeBound) {
            return;
        }
        mainHandler.removeCallbacks(connectionWatchdog);
        connectionGeneration++;
        inspecting = false;
        stopPolling();
        service = null;
        if (bound) {
            try {
                context.unbindService(connection);
            } catch (IllegalArgumentException ignored) {
                // Kuollut sidonta voi olla jo järjestelmän poistama.
            }
        }
        bound = false;
        scheduleRebind();
    }

    void close() {
        closed = true;
        unbind();
        actionExecutor.shutdownNow();
    }

    boolean isConnected() {
        return service != null;
    }

    boolean canUseObservedScanFallback() {
        IRadioServiceAPI current = service;
        return current != null && RadioApiFactory.usesHcnFramework(current);
    }

    void perform(RemoteAction action) {
        long generation = pollingGeneration;
        perform(service, generation, action);
    }

    private void perform(IRadioServiceAPI current, long generation, RemoteAction action) {
        perform(current, generation, false, action);
    }

    private void perform(IRadioServiceAPI current, long generation,
            boolean forceStateDispatch, RemoteAction action) {
        if (closed) {
            return;
        }
        if (current == null) {
            notifyConnection(false, tr(
                    "Radiolaitteisto ei ole yhdistetty",
                    "Radio hardware is not connected"
            ));
            return;
        }
        if (!isCurrentPoll(current, generation)) return;
        try {
            actionExecutor.submit(() -> {
                if (!isCurrentPoll(current, generation)) return;
                try {
                    action.run(current);
                    if (isCurrentPoll(current, generation)) pollNow(generation, forceStateDispatch);
                } catch (NwdRadioApi.CommandRejectedException rejected) {
                    if (!isCurrentPoll(current, generation)) return;
                    Log.w(TAG, "NWD command unavailable; connection and source left unchanged", rejected);
                    notifyError(current, generation,
                            tr("Radio-ohjaus epäonnistui", "Radio control failed"));
                    pollNow(generation, forceStateDispatch);
                } catch (RemoteException | RuntimeException | LinkageError error) {
                    if (!isCurrentPoll(current, generation)) return;
                    Log.e(TAG, "Radio-ohjaus epäonnistui", error);
                    String message = tr(
                            "Radio-ohjaus epäonnistui",
                            "Radio control failed"
                    );
                    mainHandler.post(() -> {
                        if (isCurrentPoll(current, generation)) handleConnectionFailure(current, message);
                    });
                }
            });
        } catch (RejectedExecutionException error) {
            Log.w(TAG, "Radio action ignored during shutdown", error);
        }
    }

    void tuneTo(int targetBand, int frequency) {
        long generation = pollingGeneration;
        IRadioServiceAPI current = service;
        if (!FrequencyRules.isValid(targetBand, frequency)) {
            notifyError(tr(
                    "Viritystaajuus ei ole kelvollinen",
                    "The tuning frequency is invalid"
            ));
            return;
        }
        // An explicit Tune needs a fresh hardware readback even when the
        // requested station is already current. Periodic polls stay deduplicated.
        perform(current, generation, true, remote -> {
            if (!isCurrentPoll(remote, generation)) return;
            if (remote instanceof NwdRadioApi) {
                try { ((NwdRadioApi) remote).validateTuningTarget(targetBand, frequency); }
                catch (RemoteException error) {
                    notifyError(remote, generation,
                            tr("Radio-ohjaus epäonnistui", "Radio control failed"));
                    return;
                }
                if (!isCurrentPoll(remote, generation)) return;
                if (!remote.requestPlayAudio()) {
                    notifyError(remote, generation,
                            tr("Radio-ohjaus epäonnistui", "Radio control failed"));
                    return;
                }
                if (!isCurrentPoll(remote, generation)) return;
                if (!((NwdRadioApi) remote).tuneToBand(targetBand, frequency,
                        () -> isCurrentPoll(remote, generation))) {
                    notifyError(remote, generation,
                            tr("Radiokaistaa ei voitu valita", "Could not select the radio band"));
                }
                return;
            }
            if (remote instanceof TsRadioApi) {
                if (!((TsRadioApi) remote).tuneToBand(targetBand, frequency,
                        () -> isCurrentPoll(remote, generation))) {
                    notifyError(remote, generation,
                            tr("Radiokaistaa ei voitu valita", "Could not select the radio band"));
                    return;
                }
                if (isCurrentPoll(remote, generation)) remote.requestPlayAudio();
                return;
            }
            int currentBand = remote.getCurrentBand();
            int normalizedTarget = normalizeBand(targetBand);
            int guard = 0;
            while (normalizeBand(currentBand) != normalizedTarget && guard < 5) {
                if (!isCurrentPoll(remote, generation)) return;
                remote.onBandEvent();
                if (!isCurrentPoll(remote, generation)) return;
                currentBand = remote.getCurrentBand();
                guard++;
            }
            if (!isCurrentPoll(remote, generation)) return;
            if (normalizeBand(currentBand) == normalizedTarget) {
                remote.gotoFreq(frequency);
            } else if (!metadataReader.tuneToBand(normalizedTarget, frequency)) {
                notifyError(remote, generation, tr(
                        "Valittua radiokaistaa ei voitu avata",
                        "The selected radio band could not be opened"
                ));
            }
            if (isCurrentPoll(remote, generation)) remote.requestPlayAudio();
        });
    }

    void readPresetFrequencies(int band, PresetResult callback) {
        IRadioServiceAPI current = service;
        long generation = pollingGeneration;
        if (!isCurrentPoll(current, generation)) return;
        try {
            actionExecutor.submit(() -> {
                if (!isCurrentPoll(current, generation)) return;
                if (current instanceof NwdRadioApi) {
                    if (!((NwdRadioApi) current).supportsScanning()) {
                        mainHandler.post(() -> {
                            if (isCurrentPoll(current, generation)) callback.onResult(band, new int[0], false);
                        });
                        return;
                    }
                    int[] frequencies = new int[0];
                    boolean available = false;
                    try {
                        frequencies = sanitizePresets(band, ((NwdRadioApi) current).readScanPresets(band,
                                () -> isCurrentPoll(current, generation)));
                        available = true;
                    } catch (RemoteException | RuntimeException error) {
                        if (isCurrentPoll(current, generation)) {
                            Log.w(TAG, "NWD scan results could not be read completely", error);
                        }
                    }
                    final int[] results = frequencies;
                    final boolean complete = available;
                    mainHandler.post(() -> {
                        if (isCurrentPoll(current, generation)) callback.onResult(band, results, complete);
                    });
                    return;
                }
                if (current instanceof TsRadioApi || !RadioApiFactory.selectedSupportsScanning()) {
                    mainHandler.post(() -> {
                        if (isCurrentPoll(current, generation)) callback.onResult(band, new int[0], false);
                    });
                    return;
                }
                int[] raw = metadataReader.readPresets(band);
                int[] sanitized = sanitizePresets(band, raw);
                boolean available = metadataReader.isAvailable();
                mainHandler.post(() -> {
                    if (isCurrentPoll(current, generation)) {
                        callback.onResult(band, sanitized, available);
                    }
                });
            });
        } catch (RejectedExecutionException error) {
            Log.w(TAG, "Preset read ignored during shutdown", error);
        }
    }

    private int normalizeBand(int band) {
        return band >= 3 ? 3 : Math.max(0, band);
    }

    private synchronized void startPolling() {
        if (closed) {
            return;
        }
        stopPolling();
        long generation = pollingGeneration;
        pollExecutor = Executors.newSingleThreadScheduledExecutor();
        pollExecutor.scheduleWithFixedDelay(() -> pollNow(generation), 0, 800, TimeUnit.MILLISECONDS);
    }

    private synchronized void stopPolling() {
        // Also invalidate already-queued UI delivery when the next binding
        // resolves to exactly the same shared API instance.
        pollingGeneration++;
        if (pollExecutor != null) {
            pollExecutor.shutdownNow();
            pollExecutor = null;
        }
    }

    private void pollNow() {
        pollNow(pollingGeneration);
    }

    private void pollNow(long generation) {
        pollNow(generation, false);
    }

    private void pollNow(long generation, boolean forceStateDispatch) {
        IRadioServiceAPI current = service;
        if (!isCurrentPoll(current, generation)) return;
        synchronized (pollingStateLock) {
            if (!isCurrentPoll(current, generation)) return;
            if (cachedPollingGeneration != generation) {
                cachedPollingGeneration = generation;
                cachedMetadataBand = -1;
                cachedMetadataFrequency = -1;
                cachedRdsName = "";
                cachedRadioText = "";
                cachedProgramType = "";
                cachedRadioTextAt = 0L;
                radioTextStabilizer.reset();
                lastDispatchedState = null;
            }
            pollCurrent(current, generation, forceStateDispatch);
        }
    }

    private boolean isCurrentPoll(IRadioServiceAPI current, long generation) {
        return current != null && !closed && service == current && pollingGeneration == generation;
    }

    private void pollCurrent(IRadioServiceAPI current, long generation, boolean forceStateDispatch) {
        try {
            NwdRadioApi.Frequency nwdFrequency = current instanceof NwdRadioApi
                    ? ((NwdRadioApi) current).frequency() : null;
            int band = nwdFrequency == null ? current.getCurrentBand() : nwdFrequency.band();
            int frequency = nwdFrequency == null ? current.getCurrentFreq() : nwdFrequency.khz;
            if (!FrequencyRules.isValid(band, frequency)) {
                return;
            }
            String serviceRdsName = RadioMetadataReader.clean(nwdFrequency == null
                    ? current.getCurrentFreqRdsPs() : nwdFrequency.name);
            RadioMetadataReader.Metadata metadata = RadioApiFactory.usesHcnFramework(current)
                    ? metadataReader.read() : null;
            if (nwdFrequency != null) {
                String text = ((NwdRadioApi) current).radioText();
                NwdRadioApi.Frequency after = ((NwdRadioApi) current).frequency();
                if (after.rawBand == nwdFrequency.rawBand && after.khz == frequency) {
                    metadata = new RadioMetadataReader.Metadata(frequency, serviceRdsName, text, "", false);
                }
            }
            if (band != cachedMetadataBand || frequency != cachedMetadataFrequency) {
                cachedMetadataBand = band;
                cachedMetadataFrequency = frequency;
                cachedRdsName = "";
                cachedRadioText = "";
                cachedProgramType = "";
                cachedRadioTextAt = 0L;
                radioTextStabilizer.reset();
            }
            if (!serviceRdsName.isEmpty()) {
                cachedRdsName = serviceRdsName;
            }
            boolean metadataMatches = metadata != null
                    && metadata.frequency == frequency;
            if (metadataMatches) {
                if (!metadata.stationName.isEmpty()) {
                    cachedRdsName = metadata.stationName;
                }
                if (!metadata.radioText.isEmpty()
                        && !metadata.radioText.equalsIgnoreCase(cachedRdsName)) {
                    String stableRadioText = radioTextStabilizer.observe(
                            stationKey(band, frequency),
                            metadata.radioText
                    );
                    if (!stableRadioText.isEmpty()) {
                        cachedRadioText = stableRadioText;
                        cachedRadioTextAt = SystemClock.elapsedRealtime();
                    }
                }
                if (!metadata.programType.isEmpty()) {
                    cachedProgramType = metadata.programType;
                }
            }
            if (!cachedRadioText.isEmpty()
                    && SystemClock.elapsedRealtime() - cachedRadioTextAt > RADIO_TEXT_TTL_MS) {
                cachedRadioText = "";
                radioTextStabilizer.reset();
            }
            boolean autoScanning = current.IsAS();
            boolean scanning = autoScanning || current.IsPS() || current.IsScan();
            RadioState state = new RadioState(
                    band,
                    frequency,
                    cachedRdsName,
                    cachedRadioText,
                    cachedProgramType,
                    metadataMatches && metadata.rdsAvailable,
                    current.IsStereo(),
                    current.IsDxLocal(),
                    autoScanning,
                    scanning,
                    current.IsSeek(),
                    current.currentFreqIsFavorite()
            );
            if (!isCurrentPoll(current, generation)) return;
            if (!forceStateDispatch && state.hasSameContent(lastDispatchedState)) {
                return;
            }
            lastDispatchedState = state;
            mainHandler.post(() -> {
                if (isCurrentPoll(current, generation)) listener.onStateChanged(state);
            });
        } catch (RemoteException | RuntimeException | LinkageError error) {
            if (!isCurrentPoll(current, generation)) return;
            Log.e(TAG, "Radiotilan lukeminen epäonnistui", error);
            String message = tr(
                    "Radiotilan lukeminen epäonnistui",
                    "Reading the radio state failed"
            );
            mainHandler.post(() -> {
                if (isCurrentPoll(current, generation)) handleConnectionFailure(current, message);
            });
        }
    }

    private static long stationKey(int band, int frequency) {
        return ((long) band << 32) | (frequency & 0xffffffffL);
    }

    private void handleConnectionFailure(IRadioServiceAPI failedService, String message) {
        if (service != failedService) {
            return;
        }
        service = null;
        stopPolling();
        notifyConnection(false, message);
        mainHandler.post(this::recoverBinding);
    }

    private void notifyConnection(boolean connected, String message) {
        if (!closed) {
            mainHandler.post(() -> {
                if (!closed) {
                    listener.onConnectionChanged(connected, message);
                }
            });
        }
    }

    private void notifyError(String message) {
        if (!closed) {
            mainHandler.post(() -> {
                if (!closed) {
                    listener.onRadioError(message);
                }
            });
        }
    }

    private void notifyError(IRadioServiceAPI current, long generation, String message) {
        if (!isCurrentPoll(current, generation)) return;
        mainHandler.post(() -> {
            if (isCurrentPoll(current, generation)) listener.onRadioError(message);
        });
    }

    private String tr(String finnish, String english) {
        return AppLanguage.text(context, finnish, english);
    }

    static int[] sanitizePresets(int band, int[] raw) {
        if (raw == null || raw.length == 0) {
            return new int[0];
        }
        HashMap<Integer, Integer> counts = new HashMap<>();
        for (int frequency : raw) {
            if (validFrequency(band, frequency)) {
                counts.put(frequency, counts.getOrDefault(frequency, 0) + 1);
            }
        }
        ArrayList<Integer> clean = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
            // OEM pads unused preset slots with the band minimum. Those values
            // appear repeatedly and must not become fake stations.
            int paddingFrequency = FrequencyRules.isFm(band)
                    ? FrequencyRules.FM_MIN
                    : FrequencyRules.AM_MIN;
            if (entry.getKey() != paddingFrequency || entry.getValue() == 1) {
                clean.add(entry.getKey());
            }
        }
        int[] result = new int[clean.size()];
        for (int i = 0; i < clean.size(); i++) {
            result[i] = clean.get(i);
        }
        Arrays.sort(result);
        return result;
    }

    private static boolean validFrequency(int band, int frequency) {
        return FrequencyRules.isValid(band, frequency);
    }
}
