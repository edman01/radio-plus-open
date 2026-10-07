package fi.radioplus.app;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;

import com.hcn.autoradio.IRadioCallBack;
import com.hcn.autoradio.IRadioServiceAPI;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Dormant, independently authored protocol foundation for the inspected S5.40
 * Reglink services. NOT a usable playback backend: factory device control must
 * remain disabled until audio and steering ownership are verified. No vendor
 * implementation is loaded and no explicit power/shutdown, regional grid,
 * audio focus or source-switch commands are sent. OEM tune/scan commands may
 * implicitly power or unmute the tuner, so these protocol methods must remain
 * unreachable from device control without a future proven ownership gate.
 */
final class ReglinkRadioApi implements IRadioServiceAPI, AutoCloseable {
    static final String COMMON_DESCRIPTOR = "com.reglink.services.IDroidService";
    static final String RADIO_DESCRIPTOR = "com.reglink.services.IRadioService";
    static final String CALLBACK_DESCRIPTOR = "com.reglink.services.IRadioCallback";
    private static final long TUNE_WAIT_MS = 1600;
    private static final long SCAN_STOP_WAIT_MS = 1600;
    private static final long SCAN_START_WAIT_MS = 2500;
    private static final int MAX_SCAN_RESULTS = 512;

    /** A rejected target is not a broken binding and must not cause auto-replay. */
    static final class CommandRejectedException extends RemoteException {
        CommandRejectedException(String message) { super(message); }
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

    private interface Writer { void write(Parcel data); }
    private interface Reader<T> { T read(Parcel reply) throws RemoteException; }
    private final IBinder common;
    private final IBinder radio;
    private final Object operationLock = new Object();
    private final Object stateLock = new Object();
    private volatile boolean closed;
    private RadioObserver observer;
    private long observerGeneration;
    private String callbackBand = "";
    private int callbackFrequency;
    private int callbackState;
    private String pendingBand = "";
    private int pendingFrequency;
    private boolean scanOwned;
    private boolean scanStarted;
    private boolean scanComplete;
    private boolean scanStopIssued;
    private String scanBand = "";
    private long scanRequestedAt;
    private final Set<Integer> scanResults = new LinkedHashSet<>();

    ReglinkRadioApi(IBinder common) throws RemoteException {
        if (common == null || !COMMON_DESCRIPTOR.equals(common.getInterfaceDescriptor())) {
            throw new RemoteException("Unexpected Reglink service Binder");
        }
        this.common = common;
        radio = transact(common, COMMON_DESCRIPTOR, 2,
                data -> data.writeString("Radio"), Parcel::readStrongBinder);
        if (radio == null || !RADIO_DESCRIPTOR.equals(radio.getInterfaceDescriptor())) {
            throw new RemoteException("Unexpected Reglink radio Binder");
        }
        // This facade returns true for all bands before its tuner is connected.
        // A valid coherent readback must therefore also exist before accepting it.
        Snapshot initial = snapshot();
        if (!supportsBand(initial.band)) throw new RemoteException("Reglink tuner is not ready");
    }

    private static <T> T transact(IBinder binder, String descriptor, int code,
            Writer writer, Reader<T> reader) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            if (writer != null) writer.write(data);
            if (!binder.transact(code, data, reply, 0)) {
                throw new RemoteException("Reglink transaction rejected: " + code);
            }
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
        if (name.isEmpty()) return false;
        return call(20, data -> data.writeString(name), reply -> {
            int value = reply.readInt();
            if (value != 0 && value != 1) throw new RemoteException("Invalid Reglink capability");
            return value == 1;
        });
    }

    /** Read-only; never substitutes callback scan progress for the tuned frequency. */
    Snapshot snapshot() throws RemoteException {
        for (int attempt = 0; attempt < 3; attempt++) {
            String before = call(5, null, Parcel::readString);
            int frequency = call(7, null, Parcel::readInt);
            boolean scanning = flag(14);
            boolean powered = flag(15);
            int afterFrequency = call(7, null, Parcel::readInt);
            String after = call(5, null, Parcel::readString);
            if (before == null || !before.equals(after) || frequency != afterFrequency) continue;
            if (ReglinkTuningRules.observedKhz(before, frequency) < 0) {
                throw new RemoteException("Reglink frequency is not in the inspected range");
            }
            synchronized (stateLock) {
                if (pendingBand.equals(before) && pendingFrequency == frequency) pendingBand = "";
                if (scanOwned && scanBand.equals(before)) {
                    if (scanning) scanStarted = true;
                }
                boolean known = observer != null && callbackBand.equals(before)
                        && callbackFrequency == frequency;
                return new Snapshot(before, frequency, powered, scanning, known, callbackState);
            }
        }
        throw new RemoteException("Reglink frequency changed during inspection");
    }

    void validateTuningTarget(int band, int khz) throws RemoteException {
        if (!ReglinkTuningRules.validTarget(band, khz) || !supportsBand(band)) {
            throw new CommandRejectedException("Reglink tuning target is not supported");
        }
    }

    private void requireReadyForMutation() throws RemoteException {
        Snapshot current = snapshot();
        synchronized (stateLock) {
            if (!pendingBand.isEmpty()) {
                throw new CommandRejectedException("Previous Reglink tune has not been confirmed");
            }
            if (scanOwned || current.scanning) {
                throw new CommandRejectedException("Stop the existing Reglink scan first");
            }
        }
    }

    boolean tuneToBand(int band, int khz) throws RemoteException {
        return tuneToBand(band, khz, () -> true);
    }

    boolean tuneToBand(int band, int khz, BooleanSupplier stillCurrent) throws RemoteException {
        synchronized (operationLock) {
            validateTuningTarget(band, khz);
            if (!canContinue(stillCurrent)) return false;
            requireReadyForMutation();
            String name = ReglinkTuningRules.nativeBand(band);
            int raw = ReglinkTuningRules.rawFrequency(band, khz);
            if (!canContinue(stillCurrent)) return false;
            synchronized (stateLock) {
                pendingBand = name;
                pendingFrequency = raw;
                callbackBand = "";
            }
            // A failed reply can still follow a delivered mutation. Keep the
            // pending readback guard on failure/cancellation rather than replay.
            call(6, data -> { data.writeString(name); data.writeInt(raw); }, null);
            long deadline = SystemClock.elapsedRealtime() + TUNE_WAIT_MS;
            do {
                if (!canContinue(stillCurrent)) return false;
                Snapshot observed = snapshot();
                if (name.equals(observed.rawBand) && observed.rawFrequency == raw) return true;
                SystemClock.sleep(50);
            } while (SystemClock.elapsedRealtime() < deadline);
            return false;
        }
    }

    /** Explicit subscription, separate from construction and read-only polling. */
    void startObserving() throws RemoteException {
        synchronized (operationLock) { ensureObserver(); }
    }

    private void ensureObserver() throws RemoteException {
        if (observer != null) return;
        RadioObserver fresh;
        synchronized (stateLock) {
            fresh = new RadioObserver(++observerGeneration);
            observer = fresh;
            callbackBand = "";
        }
        try { call(1, data -> data.writeStrongBinder(fresh), null); }
        catch (RemoteException | RuntimeException failure) {
            synchronized (stateLock) { observer = null; observerGeneration++; }
            // Registration may have succeeded before its reply failed.
            try { call(2, data -> data.writeStrongBinder(fresh), null); }
            catch (RemoteException | RuntimeException ignored) { }
            throw failure;
        }
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
                // Invalid final scan callbacks are present in this OEM build;
                // their completion bit is useful but frequency must be rejected.
                if (khz >= 0) {
                    callbackBand = band;
                    callbackFrequency = raw;
                    callbackState = state;
                }
                if (scanOwned && scanBand.equals(band)) {
                    if ((state & 4) != 0) scanStarted = true;
                    if (scanStarted && (state & 8) != 0 && khz >= 0
                            && FrequencyRules.isValid(ReglinkTuningRules.appBand(band), khz)
                            && scanResults.size() < MAX_SCAN_RESULTS) scanResults.add(khz);
                    if (scanStarted && (state & 4) == 0) completeScanLocked();
                }
            }
            return true; // OEM callback is one-way, with no reply parcel.
        }
    }

    private void completeScanLocked() {
        scanOwned = false;
        scanComplete = true;
    }

    void startScan() throws RemoteException {
        synchronized (operationLock) {
            requireReadyForMutation();
            Snapshot current = snapshot();
            if (!supportsBand(current.band)) throw new CommandRejectedException("Reglink band unavailable");
            ensureObserver();
            synchronized (stateLock) {
                scanBand = current.rawBand;
                scanResults.clear();
                scanComplete = false;
                scanStopIssued = false;
                scanStarted = false;
                scanOwned = true;
                scanRequestedAt = SystemClock.elapsedRealtime();
            }
            try { call(12, null, null); }
            catch (RemoteException | RuntimeException failure) {
                // Preserve owned state: an accepted command with a lost reply
                // still needs an explicit stop, never a second scan command.
                throw failure;
            }
        }
    }

    boolean stopScan(BooleanSupplier stillCurrent) throws RemoteException {
        synchronized (operationLock) {
            synchronized (stateLock) { if (!scanOwned) return scanComplete; }
            if (!canContinue(stillCurrent)) return false;
            boolean sendStop;
            synchronized (stateLock) {
                sendStop = !scanStopIssued;
                scanStopIssued = true;
            }
            // A stop also retunes on MCU variants. Never replay an uncertain
            // stop when its reply/terminal callback has not arrived.
            if (sendStop) call(13, null, null);
            long deadline = SystemClock.elapsedRealtime() + SCAN_STOP_WAIT_MS;
            do {
                if (!canContinue(stillCurrent)) return false;
                if (!flag(14)) {
                    synchronized (stateLock) {
                        // Getter false may overtake queued one-way result
                        // callbacks. Only their terminal event closes a started
                        // scan, so late valid stations are not silently lost.
                        if (scanComplete) return true;
                    }
                }
                SystemClock.sleep(50);
            } while (SystemClock.elapsedRealtime() < deadline);
            return false;
        }
    }

    int[] readScanPresets(int band, BooleanSupplier stillCurrent) throws RemoteException {
        synchronized (operationLock) {
            synchronized (stateLock) {
                if (!scanBand.equals(ReglinkTuningRules.nativeBand(band))) {
                    throw new CommandRejectedException("No Reglink scan for this band");
                }
            }
            if (!stopScan(stillCurrent) || !canContinue(stillCurrent)) {
                throw new CommandRejectedException("Reglink scan completion is not confirmed");
            }
            synchronized (stateLock) {
                int[] result = new int[scanResults.size()];
                int index = 0;
                for (Integer frequency : scanResults) result[index++] = frequency;
                return result;
            }
        }
    }

    private boolean canContinue(BooleanSupplier current) {
        return !closed && !Thread.currentThread().isInterrupted()
                && current != null && current.getAsBoolean();
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
            // Explicit known safe starting frequency, never OEM magic 0/last-band
            // semantics. This method remains unreachable from the disabled profile.
            int band = current.band == 3 ? 0 : 3;
            if (!tuneToBand(band, band == 3 ? 531 : 87500)) {
                throw new CommandRejectedException("Reglink band switch unconfirmed");
            }
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
    @Override public String getCurrentFreqRdsPs() { return ""; } // Separate optional RDS daemon, not this ABI.
    @Override public boolean currentFreqIsFavorite() { return false; }
    @Override public boolean getFreqIsFavorite(int band, int khz) { return false; }
    @Override public boolean requestPlayAudio() { return false; }
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
            if (closed) return;
            // Do not stop/power a shared tuner during lifecycle teardown. A scan
            // owner must explicitly stop while it still has ownership first.
            closed = true;
            RadioObserver previous;
            synchronized (stateLock) {
                previous = observer;
                observer = null;
                observerGeneration++;
                scanOwned = false;
                scanComplete = false;
                scanResults.clear();
            }
            if (previous != null) {
                try { transact(radio, RADIO_DESCRIPTOR, 2,
                        data -> data.writeStrongBinder(previous), null); }
                catch (RemoteException | RuntimeException ignored) { }
            }
        }
    }
}
