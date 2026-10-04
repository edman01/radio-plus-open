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

    private ScheduledExecutorService pollExecutor;
    private volatile IRadioServiceAPI service;
    private volatile boolean closed;
    private boolean bound;
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
            service = IRadioServiceAPI.Stub.asInterface(binder);
            notifyConnection(true, tr(
                    "Radiolaitteisto yhdistetty",
                    "Radio hardware connected"
            ));
            startPolling();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
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
        if (closed || !shouldBeBound || bound) {
            return;
        }
        Intent intent = new Intent(RadioBackendContract.SERVICE_ACTION);
        intent.setComponent(RadioBackendContract.SERVICE_COMPONENT);
        try {
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

    void perform(RemoteAction action) {
        if (closed) {
            return;
        }
        IRadioServiceAPI current = service;
        if (current == null) {
            notifyConnection(false, tr(
                    "Radiolaitteisto ei ole yhdistetty",
                    "Radio hardware is not connected"
            ));
            return;
        }
        try {
            actionExecutor.submit(() -> {
                if (closed || service != current) {
                    return;
                }
                try {
                    action.run(current);
                    pollNow();
                } catch (RemoteException | RuntimeException | LinkageError error) {
                    Log.e(TAG, "Radio-ohjaus epäonnistui", error);
                    handleConnectionFailure(current, tr(
                            "Radio-ohjaus epäonnistui",
                            "Radio control failed"
                    ));
                }
            });
        } catch (RejectedExecutionException error) {
            Log.w(TAG, "Radio action ignored during shutdown", error);
        }
    }

    void tuneTo(int targetBand, int frequency) {
        if (!FrequencyRules.isValid(targetBand, frequency)) {
            notifyError(tr(
                    "Viritystaajuus ei ole kelvollinen",
                    "The tuning frequency is invalid"
            ));
            return;
        }
        perform(remote -> {
            int currentBand = remote.getCurrentBand();
            int normalizedTarget = normalizeBand(targetBand);
            int guard = 0;
            while (normalizeBand(currentBand) != normalizedTarget && guard < 5) {
                remote.onBandEvent();
                currentBand = remote.getCurrentBand();
                guard++;
            }
            if (normalizeBand(currentBand) == normalizedTarget) {
                remote.gotoFreq(frequency);
            } else if (!metadataReader.tuneToBand(normalizedTarget, frequency)) {
                notifyError(tr(
                        "Valittua radiokaistaa ei voitu avata",
                        "The selected radio band could not be opened"
                ));
            }
            remote.requestPlayAudio();
        });
    }

    void readPresetFrequencies(int band, PresetResult callback) {
        if (closed) {
            return;
        }
        try {
            actionExecutor.submit(() -> {
            int[] raw = metadataReader.readPresets(band);
            int[] sanitized = sanitizePresets(band, raw);
            if (!closed) {
                mainHandler.post(() -> {
                    if (!closed) {
                        callback.onResult(
                                band,
                                sanitized,
                                metadataReader.isAvailable()
                        );
                    }
                });
            }
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
        lastDispatchedState = null;
        pollExecutor = Executors.newSingleThreadScheduledExecutor();
        pollExecutor.scheduleWithFixedDelay(this::pollNow, 0, 800, TimeUnit.MILLISECONDS);
    }

    private synchronized void stopPolling() {
        if (pollExecutor != null) {
            pollExecutor.shutdownNow();
            pollExecutor = null;
        }
    }

    private synchronized void pollNow() {
        IRadioServiceAPI current = service;
        if (current == null) {
            return;
        }
        try {
            int band = current.getCurrentBand();
            int frequency = current.getCurrentFreq();
            if (!FrequencyRules.isValid(band, frequency)) {
                return;
            }
            String serviceRdsName = RadioMetadataReader.clean(current.getCurrentFreqRdsPs());
            RadioMetadataReader.Metadata metadata = metadataReader.read();
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
            if (state.hasSameContent(lastDispatchedState)) {
                return;
            }
            lastDispatchedState = state;
            if (!closed) {
                mainHandler.post(() -> {
                    if (!closed) {
                        listener.onStateChanged(state);
                    }
                });
            }
        } catch (RemoteException | RuntimeException | LinkageError error) {
            Log.e(TAG, "Radiotilan lukeminen epäonnistui", error);
            handleConnectionFailure(current, tr(
                    "Radiotilan lukeminen epäonnistui",
                    "Reading the radio state failed"
            ));
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
