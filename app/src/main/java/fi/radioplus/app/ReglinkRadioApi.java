package fi.radioplus.app;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;

import com.hcn.autoradio.IRadioCallBack;
import com.hcn.autoradio.IRadioServiceAPI;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Independently authored tuner protocol. The opt-in assisted profiles keep the
 * real stock application as audio owner; it never claims its MediaHub identity.
 * The single-argument constructor is the protocol test foundation only.
 */
final class ReglinkRadioApi implements IRadioServiceAPI, AutoCloseable {
    static final String COMMON_DESCRIPTOR = "com.reglink.services.IDroidService";
    static final String RADIO_DESCRIPTOR = "com.reglink.services.IRadioService";
    static final String CALLBACK_DESCRIPTOR = "com.reglink.services.IRadioCallback";
    private static final long TUNE_WAIT_MS = 1600;
    private static final long BAND_RECALL_WAIT_MS = 3200;
    // S6.02/4754 schedules its source-local unmute 1.5 s after a band change;
    // the global mute getter alone need not reflect that quiet period.
    private static final long STOCK_BAND_UNMUTE_MS = 1500;
    private static final long SCAN_STOP_WAIT_MS = 1600;
    private static final long SCAN_START_WAIT_MS = 2500;
    private static final int MAX_SCAN_RESULTS = 512;

    /** A rejected target is not a broken binding and must not cause auto-replay. */
    static final class CommandRejectedException extends RemoteException {
        CommandRejectedException(String message) { super(message); SupportTestLog.event(SupportTestLog.Event.COMMAND_REJECTED); }
    }

    static final class Snapshot {
        final String rawBand;
        final int rawFrequency;
        final int band;
        final int khz;
        final boolean powered;
        final boolean scanning;
        final boolean callbackStateKnown;
        final boolean stereo;
        final boolean seeking;

        Snapshot(String rawBand, int rawFrequency, boolean powered, boolean scanning,
                boolean callbackStateKnown, int state) {
            this.rawBand = rawBand;
            this.rawFrequency = rawFrequency;
            this.band = ReglinkTuningRules.appBand(rawBand);
            this.khz = ReglinkTuningRules.observedKhz(rawBand, rawFrequency);
            this.powered = powered;
            this.scanning = scanning;
            this.callbackStateKnown = callbackStateKnown;
            this.stereo = callbackStateKnown && (state & 2) != 0;
            this.seeking = callbackStateKnown && (state & 32) != 0;
        }
    }

    interface Writer { void write(Parcel data); }
    interface Reader<T> { T read(Parcel reply) throws RemoteException; }
    private final IBinder common;
    private final IBinder radio;
    private final ReglinkCapabilityReader.Snapshot capabilities;
    private final ReglinkStockSession stockSession;
    private final boolean s602External4754;
    private volatile ReglinkRdsNames rdsNames;
    private final Object operationLock = new Object();
    private final Object stateLock = new Object();
    private volatile boolean closed;
    private volatile long bandSettlingUntil;
    private volatile boolean capabilitiesInvalidated;
    private RadioObserver observer;
    private RadioObserver pendingObserverCleanup;
    private long observerGeneration;
    private long observationGeneration;
    private String callbackBand = "";
    private int callbackFrequency;
    private int callbackState;
    private String pendingBand = "";
    private int pendingFrequency;
    // Only a completed (not cancelled/failed) wait may be superseded, by a
    // different selection after the last coherent readback is verified again.
    private Snapshot timedOutAt;
    private String timedOutTargetBand;
    private int timedOutTargetRaw;
    private long tuneGeneration;
    private boolean tuneDispatching;
    // Request bookkeeping, not exclusive OEM ownership. The assisted path
    // observes the stock owner and rejects observable interference, but cannot
    // identify every concurrent same-band OEM request without wire request IDs.
    private boolean scanOwned;
    private boolean scanStarted;
    private boolean scanComplete;
    private boolean scanStopIssued;
    private boolean scanTerminal;
    private boolean scanInvalidated;
    private String scanBand = "";
    private long scanRequestedAt;
    private final Set<Integer> scanResults = new LinkedHashSet<>();

    ReglinkRadioApi(IBinder common) throws RemoteException { this(common, null); }

    static ReglinkRadioApi stockAssisted(IBinder common, RadioBackendProfile profile) throws RemoteException {
        if (profile == null || !profile.isAssistedReglink()) throw new RemoteException("Unknown assisted profile");
        return new ReglinkRadioApi(common, profile);
    }

    private ReglinkRadioApi(IBinder common, RadioBackendProfile profile) throws RemoteException {
        boolean assisted = profile != null;
        if (common == null || !COMMON_DESCRIPTOR.equals(common.getInterfaceDescriptor())) {
            throw new RemoteException("Unexpected Reglink service Binder");
        }
        this.common = common;
        // The Radio facade alone reports every band as supported before its
        // concrete tuner connects, and MCU variants accept arbitrary bands.
        // Read independently identified hardware facts before fetching Radio.
        capabilities = ReglinkCapabilityReader.read(common);
        if (capabilities.kind == ReglinkCapabilityReader.Kind.UNKNOWN) {
            SupportTestLog.problem(SupportTestLog.Problem.HARDWARE_NOT_READY);
            throw new RemoteException("Reglink hardware capabilities are not verified");
        }
        // Native FM uses a different audio renderer and remains disabled here.
        if (assisted && capabilities.kind != ReglinkCapabilityReader.Kind.EXTERNAL_FM_AM) {
            SupportTestLog.problem(SupportTestLog.Problem.TUNER_NOT_SUPPORTED);
            throw new RemoteException("Reglink test build requires the external MCU tuner");
        }
        s602External4754 = profile == RadioBackendProfile.REGLINK_S602_ASSISTED && "4754".equals(capabilities.module);
        stockSession = assisted ? new ReglinkStockSession(common, profile, capabilities.module) : null;
        if (assisted) SupportTestLog.event(SupportTestLog.Event.CAPABILITIES, capabilities.kind.ordinal(),
                Integer.parseInt(capabilities.module));
        radio = transact(common, COMMON_DESCRIPTOR, 2,
                data -> data.writeString("Radio"), Parcel::readStrongBinder);
        if (radio == null || !RADIO_DESCRIPTOR.equals(radio.getInterfaceDescriptor())) {
            throw new RemoteException("Unexpected Reglink radio Binder");
        }
        // This facade returns true for all bands before its tuner is connected.
        // A valid coherent readback must therefore also exist before accepting it.
        Snapshot initial = snapshot();
        if (!supportsBand(initial.band)) throw new RemoteException("Reglink tuner is not ready");
        if (assisted) startObserving();
    }

    boolean isStockAssisted() { return stockSession != null; }
    boolean isS602External4754() { return s602External4754; }
    boolean isClosed() { return closed; }

    void observeRdsNames(android.content.Context application) {
        synchronized (operationLock) {
            if (!s602External4754 || closed || rdsNames != null) return;
            rdsNames = new ReglinkRdsNames(application);
            rdsNames.bindIfRunning();
        }
    }

    String cachedStationName(int band, int khz) {
        ReglinkRdsNames cache = rdsNames;
        return closed || cache == null ? "" : cache.nameAt(band, khz);
    }

    Map<Integer, String> cachedScanNames(int band, int[] frequencies, BooleanSupplier stillCurrent) {
        Map<Integer, String> names = new HashMap<>();
        if (rdsNames != null && FrequencyRules.isFm(band)) {
            for (int khz : frequencies) {
                if (!canContinue(stillCurrent)) break;
                String name = cachedStationName(band, khz);
                if (!name.isEmpty()) names.put(khz, name);
            }
        }
        if (s602External4754 && canContinue(stillCurrent)) {
            ReglinkRdsNames cache = rdsNames;
            SupportTestLog.event(SupportTestLog.Event.RDS_SCAN_CACHE,
                    cache == null ? 0 : cache.isConnected() ? 2 : 1, names.size());
        }
        return names;
    }

    private void requireStockRoute() throws RemoteException {
        if (stockSession == null) return;
        boolean scanMute;
        synchronized (stateLock) { scanMute = scanOwned && !scanInvalidated; }
        try { stockSession.requireRoute(scanMute); }
        catch (RemoteException failure) {
            synchronized (stateLock) {
                if (scanOwned || scanComplete) {
                    SupportTestLog.problem(SupportTestLog.Problem.SOURCE_CHANGED_DURING_SCAN);
                    invalidateScanLocked();
                }
            }
            throw failure;
        }
    }

    private ReglinkStockSession.State observeStockRoute() throws RemoteException {
        try {
            ReglinkStockSession.State route = stockSession.read();
            if (!route.stockOwnsRoute) {
                invalidateObservedScan(SupportTestLog.Problem.SOURCE_CHANGED_DURING_SCAN);
            }
            return route;
        } catch (RemoteException | RuntimeException error) {
            invalidateObservedScan(SupportTestLog.Problem.SOURCE_READ_FAILED);
            throw error;
        }
    }

    private void invalidateObservedScan(SupportTestLog.Problem reason) {
        synchronized (stateLock) {
            if (scanOwned || scanComplete) {
                SupportTestLog.problem(reason);
                invalidateScanLocked();
            }
        }
    }

    Snapshot snapshotForPoll() throws RemoteException {
        boolean watch;
        synchronized (stateLock) { watch = stockSession != null && scanOwned; }
        if (watch) observeStockRoute();
        return snapshot();
    }

    RadioPlaybackHealthReader.Snapshot readHealth() {
        try {
            if (stockSession == null) return new RadioPlaybackHealthReader.Snapshot(false, "", false, false);
            ReglinkStockSession.State route = observeStockRoute();
            Snapshot state = snapshot();
            return new RadioPlaybackHealthReader.Snapshot(true,
                    route.stockOwnsRoute ? "com.reglink.apps.radio" : "other",
                    true, route.globallyMuted || !state.powered);
        } catch (RemoteException | RuntimeException failure) {
            return new RadioPlaybackHealthReader.Snapshot(false, "", false, false);
        }
    }

    boolean requestStockPlayback() throws RemoteException {
        synchronized (operationLock) {
            if (stockSession == null) return false;
            revalidateCapabilities();
            requireStockRoute();
            if (snapshot().powered) return true; // Adopt; never reinitialize audible radio.
            // Unlike the core fallback, this external service's shutdown/play
            // changes GLOBAL media mute. It is not a source-local pause API.
            throw new CommandRejectedException("Start the stock radio first; audio takeover is disabled");
        }
    }

    static <T> T transact(IBinder binder, String descriptor, int code,
            Writer writer, Reader<T> reader) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            if (writer != null) writer.write(data);
            if (!binder.transact(code, data, reply, 0)) {
                throw new RemoteException("Reglink transaction rejected: " + code);
            }
            if (reply.dataAvail() < 4) throw new RemoteException("Missing Reglink reply status");
            reply.readException();
            if (reader != null && reply.dataAvail() < 4) {
                throw new RemoteException("Truncated Reglink reply");
            }
            T result = reader == null ? null : reader.read(reply);
            if (reply.dataAvail() != 0) throw new RemoteException("Unexpected Reglink reply layout");
            return result;
        } finally { reply.recycle(); data.recycle(); }
    }

    private <T> T call(int code, Writer writer, Reader<T> reader) throws RemoteException {
        if (closed) throw new CommandRejectedException("Reglink adapter is closed");
        return transact(radio, RADIO_DESCRIPTOR, code, writer, reader);
    }

    private boolean flag(int code) throws RemoteException {
        return call(code, null, reply -> {
            int value = reply.readInt();
            if (value != 0 && value != 1) throw new RemoteException("Invalid Reglink boolean");
            return value == 1;
        });
    }

    boolean supportsBand(int band) throws RemoteException {
        String name = ReglinkTuningRules.nativeBand(band);
        if (name.isEmpty() || capabilitiesInvalidated || !capabilities.supportsBand(band)) return false;
        return call(20, data -> data.writeString(name), reply -> {
            int value = reply.readInt();
            if (value != 0 && value != 1) throw new RemoteException("Invalid Reglink capability");
            return value == 1;
        });
    }

    private void revalidateCapabilities() throws RemoteException {
        if (closed) throw new CommandRejectedException("Reglink adapter is closed");
        if (capabilitiesInvalidated) {
            throw new CommandRejectedException("Reglink hardware changed; reconnect before control");
        }
        ReglinkCapabilityReader.Snapshot current;
        try { current = ReglinkCapabilityReader.read(common); }
        catch (RemoteException | RuntimeException failure) {
            capabilitiesInvalidated = true;
            throw new CommandRejectedException("Reglink hardware revalidation failed; reconnect before control");
        }
        if (current.kind == ReglinkCapabilityReader.Kind.UNKNOWN
                || current.kind != capabilities.kind
                || !capabilities.module.equals(current.module)
                || current.onboardRadio != capabilities.onboardRadio
                || current.initialized != capabilities.initialized) {
            capabilitiesInvalidated = true;
            throw new CommandRejectedException("Reglink hardware changed; reconnect before control");
        }
        requireStockRoute();
    }

    static final class BandTransitionReadback extends RemoteException {
        BandTransitionReadback(String message) { super(message); }
    }

    private RemoteException unstableRead(String message) {
        return SystemClock.elapsedRealtime() < bandSettlingUntil
                ? new BandTransitionReadback(message) : new RemoteException(message);
    }

    /** Read-only; never substitutes callback scan progress for the tuned frequency. */
    Snapshot snapshot() throws RemoteException {
        for (int attempt = 0; attempt < 3; attempt++) {
            long observedTuneGeneration;
            long observedObservationGeneration;
            synchronized (stateLock) {
                observedTuneGeneration = tuneGeneration;
                observedObservationGeneration = observationGeneration;
            }
            String before = call(5, null, Parcel::readString);
            int frequency = call(7, null, Parcel::readInt);
            boolean scanning = flag(14);
            boolean powered = flag(15);
            int afterFrequency = call(7, null, Parcel::readInt);
            String after = call(5, null, Parcel::readString);
            if (before == null || !before.equals(after) || frequency != afterFrequency) continue;
            if (ReglinkTuningRules.observedKhz(before, frequency) < 0) {
                throw unstableRead("Reglink frequency is not in the inspected range");
            }
            synchronized (stateLock) {
                // A queued one-way callback may overtake the getter sequence.
                // Never combine its new terminal event with an older scan flag.
                if (observedObservationGeneration != observationGeneration) continue;
                // A poll begun before dispatch cannot acknowledge that command.
                if (!tuneDispatching && observedTuneGeneration == tuneGeneration
                        && pendingBand.equals(before) && (pendingFrequency == frequency
                        || (s602External4754 && pendingFrequency == 0))) pendingBand = "";
                if (scanOwned || scanComplete) {
                    if (!scanBand.equals(before) || (scanComplete && scanning)) {
                        invalidateScanLocked();
                    } else if (scanOwned) {
                        if (scanning) scanStarted = true;
                        else if (scanTerminal) completeScanLocked();
                    }
                }
                boolean known = observer != null && callbackBand.equals(before)
                        && callbackFrequency == frequency;
                return new Snapshot(before, frequency, powered, scanning, known, callbackState);
            }
        }
        throw unstableRead("Reglink frequency changed during inspection");
    }

    void validateTuningTarget(int band, int khz) throws RemoteException {
        if (!ReglinkTuningRules.validTarget(band, khz) || !supportsBand(band)) {
            throw new CommandRejectedException("Reglink tuning target is not supported");
        }
    }

    private Snapshot requireReadyForMutation() throws RemoteException {
        return requireReadyForMutation(null, -1);
    }

    private Snapshot requireReadyForMutation(String newBand, int newRaw) throws RemoteException {
        Snapshot current = snapshot();
        synchronized (stateLock) {
            if (scanInvalidated) {
                throw new CommandRejectedException("Reglink scan ownership changed; reconnect before control");
            }
            if (scanOwned || current.scanning || current.seeking) {
                throw new CommandRejectedException("Stop the existing Reglink scan first");
            }
            if (!pendingBand.isEmpty()) {
                if (newBand == null || !s602External4754 || timedOutAt == null || !current.powered
                        || (newBand.equals(timedOutTargetBand) && newRaw == timedOutTargetRaw)
                        || !sameStation(current, timedOutAt)) {
                    throw new CommandRejectedException("Previous Reglink tune has not been confirmed");
                }
                pendingBand = "";
                timedOutAt = null;
            }
        }
        return current;
    }

    boolean tuneToBand(int band, int khz) throws RemoteException {
        return tuneToBand(band, khz, () -> true);
    }

    boolean tuneToBand(int band, int khz, BooleanSupplier stillCurrent) throws RemoteException {
        if (stockSession != null) SupportTestLog.event(SupportTestLog.Event.TUNE_REQUEST, band, khz);
        synchronized (operationLock) {
            if (!canContinue(stillCurrent)) return false;
            revalidateCapabilities();
            validateTuningTarget(band, khz);
            if (!canContinue(stillCurrent)) return false;
            String name = ReglinkTuningRules.nativeBand(band);
            int raw = ReglinkTuningRules.rawFrequency(band, khz);
            Snapshot current = requireReadyForMutation(name, raw);
            if (stockSession != null && !current.powered) throw new CommandRejectedException("Resume the stock radio before tuning");
            if (!canContinue(stillCurrent)) return false;
            // Retuning the same station is not an audio-neutral operation in
            // this firmware. It can unmute or restart a route unnecessarily.
            if (name.equals(current.rawBand) && current.rawFrequency == raw) return true;
            if (s602External4754 && !name.equals(current.rawBand)) {
                // The stock band buttons recall the tuner's last station on that band.
                // Wait for the hardware band and unmuted stock route before any fine tune.
                SupportTestLog.event(SupportTestLog.Event.BAND_RECALL, band, khz);
                Snapshot recalled = sendAndObserve(name, 0, raw, current, stillCurrent);
                if (recalled == null) return false;
                current = requireReadyForMutation();
                if (!sameStation(current, recalled) || !current.powered || !canContinue(stillCurrent)) return false;
                if (current.rawFrequency == raw) {
                    SupportTestLog.event(SupportTestLog.Event.TUNE_CONFIRMED, band, khz);
                    return true;
                }
            }
            if (sendAndObserve(name, raw, raw, current, stillCurrent) == null) return false;
            if (stockSession != null) SupportTestLog.event(SupportTestLog.Event.TUNE_CONFIRMED, band, khz);
            return true;
        }
    }

    private static boolean sameStation(Snapshot a, Snapshot b) {
        return a.rawBand.equals(b.rawBand) && a.rawFrequency == b.rawFrequency;
    }

    private Snapshot sendAndObserve(String name, int wireRaw, int requestedRaw, Snapshot expected,
            BooleanSupplier stillCurrent) throws RemoteException {
        revalidateCapabilities();
        if (s602External4754) {
            Snapshot fresh = requireReadyForMutation();
            if (!fresh.powered || !sameStation(expected, fresh)) return null;
        }
        if (!canContinue(stillCurrent)) return null;
        synchronized (stateLock) {
            pendingBand = name; pendingFrequency = wireRaw; timedOutAt = null;
            tuneGeneration++; tuneDispatching = true; callbackBand = "";
            clearScanLocked();
        }
        if (wireRaw == 0) bandSettlingUntil = SystemClock.elapsedRealtime() + BAND_RECALL_WAIT_MS;
        try { call(6, data -> { data.writeString(name); data.writeInt(wireRaw); }, null); }
        finally { synchronized (stateLock) { tuneDispatching = false; } }
        // OEM band recall can temporarily mute its own tuner for ~1.5 seconds.
        long sentAt = SystemClock.elapsedRealtime();
        long recallReadyAt = wireRaw == 0 ? sentAt + STOCK_BAND_UNMUTE_MS : 0;
        long deadline = sentAt + (wireRaw == 0 ? BAND_RECALL_WAIT_MS : TUNE_WAIT_MS);
        if (wireRaw == 0) bandSettlingUntil = deadline;
        Snapshot last = null, previousReadyRecall = null;
        do {
            if (!canContinue(stillCurrent)) return null;
            boolean muted = false;
            if (wireRaw == 0) {
                ReglinkStockSession.State route = observeStockRoute();
                if (!route.stockOwnsRoute) throw new CommandRejectedException("Stock radio lost the band-change route");
                muted = route.globallyMuted;
            } else requireStockRoute();
            try { last = snapshot(); }
            catch (BandTransitionReadback changing) {
                if (wireRaw != 0) throw changing;
                last = null;
            }
            if (last != null) {
                if (s602External4754 && (last.scanning || last.seeking || (!last.powered && wireRaw != 0))) return null;
                boolean ready = !muted && name.equals(last.rawBand) && (wireRaw != 0 || last.powered);
                // The first band/frequency publication can precede the stock
                // mute/restore. Require a second coherent, unmuted observation.
                if (ready && (wireRaw == 0
                        ? SystemClock.elapsedRealtime() >= recallReadyAt
                                && previousReadyRecall != null && sameStation(previousReadyRecall, last)
                        : last.rawFrequency == wireRaw)) {
                    bandSettlingUntil = 0;
                    return last;
                }
                previousReadyRecall = ready && wireRaw == 0 ? last : null;
            } else previousReadyRecall = null;
            SystemClock.sleep(wireRaw == 0 ? 100 : 50);
        } while (SystemClock.elapsedRealtime() < deadline);
        if (s602External4754 && last != null && canContinue(stillCurrent)) {
            synchronized (stateLock) {
                timedOutAt = last; timedOutTargetBand = name; timedOutTargetRaw = requestedRaw;
            }
            SupportTestLog.event(SupportTestLog.Event.TUNE_READBACK, last.band, last.khz);
        }
        if (stockSession != null) SupportTestLog.problem(SupportTestLog.Problem.TUNE_UNCONFIRMED);
        return null;
    }

    /** Explicit subscription, separate from construction and read-only polling. */
    void startObserving() throws RemoteException {
        synchronized (operationLock) { ensureObserver(); }
    }

    private void ensureObserver() throws RemoteException {
        if (closed) throw new CommandRejectedException("Reglink adapter is closed");
        if (pendingObserverCleanup != null) {
            removePendingObserver();
        }
        if (observer != null) return;
        RadioObserver fresh;
        synchronized (stateLock) {
            fresh = new RadioObserver(++observerGeneration);
            observer = fresh;
            callbackBand = "";
            observationGeneration++;
        }
        try { call(1, data -> data.writeStrongBinder(fresh), null); }
        catch (RemoteException | RuntimeException failure) {
            synchronized (stateLock) { observer = null; observerGeneration++; }
            // Registration may have succeeded before its reply failed.
            pendingObserverCleanup = fresh;
            try { removePendingObserver(); }
            catch (RemoteException | RuntimeException ignored) { }
            throw failure;
        }
    }

    private void removePendingObserver() throws RemoteException {
        RadioObserver pending = pendingObserverCleanup;
        if (pending == null) return;
        transact(radio, RADIO_DESCRIPTOR, 2, data -> data.writeStrongBinder(pending), null);
        pendingObserverCleanup = null;
    }

    private final class RadioObserver extends Binder {
        final long generation;
        RadioObserver(long generation) {
            this.generation = generation;
            attachInterface(null, CALLBACK_DESCRIPTOR);
        }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(CALLBACK_DESCRIPTOR);
                return true;
            }
            if (code != 1) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(CALLBACK_DESCRIPTOR);
            String band = data.readString();
            if (data.dataAvail() != 8) throw new RemoteException("Invalid Reglink callback layout");
            int raw = data.readInt();
            int state = data.readInt();
            int khz = ReglinkTuningRules.observedKhz(band, raw);
            synchronized (stateLock) {
                if (closed || observer != this || generation != observerGeneration) return true;
                observationGeneration++;
                // Invalid final scan callbacks are present in this OEM build;
                // their completion bit is useful but frequency must be rejected.
                if (khz >= 0) {
                    callbackBand = band;
                    callbackFrequency = raw;
                    callbackState = state;
                }
                if ((scanOwned || scanComplete) && !scanInvalidated) {
                    if (khz >= 0 && !scanBand.equals(band)) {
                        // A valid band handoff can begin and end between getter
                        // polls. Do not retain results across that transition.
                        invalidateScanLocked();
                        return true;
                    }
                    if (!scanBand.equals(band)) return true;
                    if ((scanComplete || scanTerminal) && (state & 4) != 0) {
                        // A new scan followed the terminal callback. Its
                        // results cannot belong to our completed request.
                        invalidateScanLocked();
                        return true;
                    }
                    if (!scanOwned) return true;
                    if ((state & 4) != 0) scanStarted = true;
                    if (scanStarted && (state & 8) != 0 && khz >= 0
                            && FrequencyRules.isValid(ReglinkTuningRules.appBand(band), khz)
                            && scanResults.size() < MAX_SCAN_RESULTS) scanResults.add(khz);
                    if (scanStarted && (state & 4) == 0) scanTerminal = true;
                }
            }
            return true; // OEM callback is one-way, with no reply parcel.
        }
    }

    private void completeScanLocked() {
        scanOwned = false;
        scanComplete = true;
        observationGeneration++;
    }

    private void invalidateScanLocked() {
        scanOwned = false;
        scanComplete = false;
        scanInvalidated = true;
        scanResults.clear();
        observationGeneration++;
    }

    private void clearScanLocked() {
        scanOwned = false;
        scanComplete = false;
        scanStarted = false;
        scanStopIssued = false;
        scanTerminal = false;
        scanInvalidated = false;
        scanBand = "";
        scanResults.clear();
        observationGeneration++;
    }

    void startScan() throws RemoteException {
        if (!startScan(() -> true)) throw new CommandRejectedException("Reglink scan was cancelled");
    }

    boolean startScan(BooleanSupplier stillCurrent) throws RemoteException {
        if (stockSession != null) SupportTestLog.event(SupportTestLog.Event.SCAN_REQUEST);
        synchronized (operationLock) {
            if (!canContinue(stillCurrent)) return false;
            // An unsuccessful new request must not expose last scan's results.
            synchronized (stateLock) { if (scanComplete) clearScanLocked(); }
            revalidateCapabilities();
            Snapshot current = requireReadyForMutation();
            if (stockSession != null && !current.powered) throw new CommandRejectedException("Resume the stock radio before scanning");
            if (!supportsBand(current.band)) throw new CommandRejectedException("Reglink band unavailable");
            if (!canContinue(stillCurrent)) return false;
            ensureObserver();
            // Subscription is IPC too; the OEM may have changed bands or
            // started a scan while it was being established.
            Snapshot afterRegistration = requireReadyForMutation();
            if (!current.rawBand.equals(afterRegistration.rawBand)) {
                throw new CommandRejectedException("Reglink band changed before scan");
            }
            revalidateCapabilities();
            if (!canContinue(stillCurrent)) return false;
            synchronized (stateLock) {
                clearScanLocked();
                scanBand = current.rawBand;
                scanOwned = true;
                scanRequestedAt = SystemClock.elapsedRealtime();
            }
            // Preserve requested state on a failed reply: the OEM may already
            // have accepted it. Never issue another scan as automatic recovery.
            call(12, null, null);
            return canContinue(stillCurrent);
        }
    }

    boolean stopScan(BooleanSupplier stillCurrent) throws RemoteException {
        synchronized (operationLock) {
            if (!canContinue(stillCurrent)) return false;
            synchronized (stateLock) {
                if (scanInvalidated) throw new CommandRejectedException("Reglink scan ownership changed");
                if (!scanOwned && !scanComplete) return false;
            }
            Snapshot current = snapshot();
            if (!canContinue(stillCurrent)) return false;
            revalidateCapabilities();
            if (!canContinue(stillCurrent)) return false;
            boolean sendStop;
            synchronized (stateLock) {
                if (scanInvalidated || !scanBand.equals(current.rawBand)) {
                    throw new CommandRejectedException("Reglink scan band changed; stop was not sent");
                }
                if (scanComplete) return true;
                // A terminal callback while the getter still says scanning may
                // describe a newer operation. Read again later, never retune it.
                if (scanTerminal) return false;
                sendStop = !scanStopIssued;
                scanStopIssued = true;
            }
            // A stop also retunes on MCU variants. Never replay an uncertain
            // stop when its reply/terminal callback has not arrived.
            if (sendStop) call(13, null, null);
            long deadline = SystemClock.elapsedRealtime() + SCAN_STOP_WAIT_MS;
            do {
                if (!canContinue(stillCurrent)) return false;
                requireStockRoute();
                Snapshot observed = snapshot();
                if (!observed.scanning) {
                    synchronized (stateLock) {
                        // Getter false may overtake queued one-way result
                        // callbacks. Only their terminal event closes a started
                        // scan, so late valid stations are not silently lost.
                        if (scanComplete) return true;
                    }
                }
                synchronized (stateLock) {
                    if (scanInvalidated) throw new CommandRejectedException("Reglink scan band changed");
                }
                SystemClock.sleep(50);
            } while (SystemClock.elapsedRealtime() < deadline);
            return false;
        }
    }

    int[] readScanPresets(int band, BooleanSupplier stillCurrent) throws RemoteException {
        synchronized (operationLock) {
            if (!canContinue(stillCurrent)) {
                throw new CommandRejectedException("Reglink scan result request was cancelled");
            }
            synchronized (stateLock) {
                if (scanInvalidated || !scanBand.equals(ReglinkTuningRules.nativeBand(band))) {
                    throw new CommandRejectedException("No Reglink scan for this band");
                }
            }
            if (!stopScan(stillCurrent) || !canContinue(stillCurrent)) {
                SupportTestLog.problem(SupportTestLog.Problem.SCAN_UNCONFIRMED);
                throw new CommandRejectedException("Reglink scan completion is not confirmed");
            }
            synchronized (stateLock) {
                if (!scanComplete || scanInvalidated
                        || !scanBand.equals(ReglinkTuningRules.nativeBand(band))) {
                    throw new CommandRejectedException("Reglink scan changed before results were copied");
                }
                int[] result = new int[scanResults.size()];
                int index = 0;
                for (Integer frequency : scanResults) result[index++] = frequency;
                if (stockSession != null) SupportTestLog.event(SupportTestLog.Event.SCAN_RESULTS, band, result.length);
                return result;
            }
        }
    }

    private boolean canContinue(BooleanSupplier current) {
        return !closed && !Thread.currentThread().isInterrupted()
                && current != null && current.getAsBoolean()
                && !closed && !Thread.currentThread().isInterrupted();
    }

    private void step(int direction) throws RemoteException {
        synchronized (operationLock) {
            Snapshot current = snapshot();
            int target = FrequencyRules.stepFrom(current.band, current.khz, direction);
            if (current.band == 3) {
                if (target < 531) target = 1602;
                if (target > 1602) target = 531;
            }
            if (!tuneToBand(current.band, target)) throw new CommandRejectedException("Reglink tune unconfirmed");
        }
    }

    @Override public IBinder asBinder() { return common; }
    @Override public int getCurrentBand() throws RemoteException { return snapshot().band; }
    @Override public int getCurrentFreq() throws RemoteException { return snapshot().khz; }
    @Override public void gotoFreq(int khz) throws RemoteException {
        synchronized (operationLock) {
            if (!tuneToBand(snapshot().band, khz)) throw new CommandRejectedException("Reglink tune unconfirmed");
        }
    }
    @Override public void gotoFreq2(String value) throws RemoteException {
        try { gotoFreq(Integer.parseInt(value)); }
        catch (NumberFormatException failure) { throw new CommandRejectedException("Invalid Reglink frequency"); }
    }
    @Override public void onBandEvent() throws RemoteException {
        synchronized (operationLock) {
            Snapshot current = snapshot();
            int band = current.band == 3 ? 0 : 3;
            if (s602External4754) {
                recallBandFrom(current.band, () -> true);
                return;
            }
            // Other verified tuners retain their explicit band-start frequency.
            if (!tuneToBand(band, band == 3 ? 531 : 87500)) {
                throw new CommandRejectedException("Reglink band switch unconfirmed");
            }
        }
    }

    void recallBandFrom(int displayedBand, BooleanSupplier stillCurrent) throws RemoteException {
        synchronized (operationLock) {
            if (!s602External4754 || (displayedBand != 0 && displayedBand != 3) || !canContinue(stillCurrent)) return;
            int band = displayedBand == 3 ? 0 : 3;
            String name = ReglinkTuningRules.nativeBand(band);
            if (snapshot().band != displayedBand) return;
            revalidateCapabilities();
            if (!supportsBand(band)) throw new CommandRejectedException("Unsupported Reglink band");
            Snapshot current = requireReadyForMutation(name, 0);
            // Two taps on a still-displayed FM band must not switch back to FM
            // when the second is dequeued after the first has already reached AM.
            if (current.band != displayedBand) return;
            if (!current.powered) throw new CommandRejectedException("Stock radio is not playing");
            // Only a preset/manual tune may fine-tune after the stock recall.
            SupportTestLog.event(SupportTestLog.Event.BAND_RECALL, band, 0);
            Snapshot recalled = sendAndObserve(name, 0, 0, current, stillCurrent);
            if (!canContinue(stillCurrent)) return;
            if (recalled == null) throw new CommandRejectedException("Reglink band switch unconfirmed");
            SupportTestLog.event(SupportTestLog.Event.TUNE_CONFIRMED, recalled.band, recalled.khz);
        }
    }
    // Transactions 10/11 are understood, but native seek completion has no
    // reliable shared readback. Do not overlap/replay a seek speculatively.
    @Override public void onSeekDownEvent() throws RemoteException { throw unavailable(); }
    @Override public void onSeekUpEvent() throws RemoteException { throw unavailable(); }
    // The app's HCN-facing Down means higher and Up means lower frequency.
    @Override public void onManualDownEvent() throws RemoteException { step(1); }
    @Override public void onManualUpEvent() throws RemoteException { step(-1); }
    @Override public void onASEvent() throws RemoteException {
        synchronized (operationLock) {
            boolean owned;
            synchronized (stateLock) { owned = scanOwned; }
            if (owned) {
                if (!stopScan(() -> true)) throw new CommandRejectedException("Reglink scan stop unconfirmed");
            } else startScan();
        }
    }
    @Override public void onScanEvent() throws RemoteException { onASEvent(); }
    @Override public boolean IsAS() throws RemoteException {
        Snapshot current = snapshot();
        synchronized (stateLock) {
            return current.scanning || (scanOwned && !scanStarted
                    && SystemClock.elapsedRealtime() - scanRequestedAt < SCAN_START_WAIT_MS);
        }
    }
    @Override public boolean IsScan() throws RemoteException { return snapshot().scanning; }
    @Override public boolean IsSeek() throws RemoteException { return snapshot().seeking; }
    @Override public boolean IsStereo() throws RemoteException { return snapshot().stereo; }
    @Override public boolean IsPS() { return false; }
    @Override public boolean IsDxLocal() { return false; } // Unknown, capability must remain disabled.
    @Override public String getCurrentFreqRdsPs() throws RemoteException {
        ReglinkRdsNames cache = rdsNames;
        if (closed || cache == null || !cache.isConnected()) return "";
        Snapshot tuned = snapshot();
        return cache.nameAt(tuned.band, tuned.khz);
    }
    @Override public boolean currentFreqIsFavorite() { return false; }
    @Override public boolean getFreqIsFavorite(int band, int khz) { return false; }
    @Override public boolean requestPlayAudio() throws RemoteException { return requestStockPlayback(); }
    @Override public void requestAudioFocus() throws RemoteException { throw unavailable(); }
    @Override public void releaseAudioFocus() throws RemoteException { throw unavailable(); }
    @Override public void onLocDxEvent() throws RemoteException { throw unavailable(); }
    @Override public void onPSEvent() throws RemoteException { throw unavailable(); }
    @Override public void gotoFreqIndex(int index) throws RemoteException { throw unavailable(); }
    @Override public void favoriteCurrentFreq() throws RemoteException { throw unavailable(); }
    @Override public void registerRadioClientBinder(IBinder binder) throws RemoteException { throw unavailable(); }
    @Override public void unRegisterRadioClientBinder() throws RemoteException { throw unavailable(); }
    @Override public void registerRadioCallback(IRadioCallBack callback) throws RemoteException { throw unavailable(); }
    @Override public void unRegisterRadioCallback(IRadioCallBack callback) throws RemoteException { throw unavailable(); }

    private static RemoteException unavailable() {
        return new CommandRejectedException("Reglink playback/optional feature is not verified; profile disabled");
    }

    @Override public void close() {
        synchronized (operationLock) {
            if (closed && pendingObserverCleanup == null) return;
            // Do not stop/power a shared tuner during lifecycle teardown. A scan
            // owner must explicitly stop while it still has ownership first.
            closed = true;
            if (rdsNames != null) rdsNames.close();
            rdsNames = null;
            RadioObserver previous;
            synchronized (stateLock) {
                previous = observer;
                observer = null;
                observerGeneration++;
                clearScanLocked();
            }
            if (previous != null) pendingObserverCleanup = previous;
            try { removePendingObserver(); }
            catch (RemoteException | RuntimeException ignored) { }
        }
    }
}
