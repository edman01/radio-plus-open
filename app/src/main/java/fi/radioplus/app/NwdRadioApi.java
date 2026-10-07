package fi.radioplus.app;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import com.hcn.autoradio.IRadioCallBack;
import com.hcn.autoradio.IRadioServiceAPI;
import java.util.function.BooleanSupplier;
import java.util.ArrayList;
import java.util.List;

/** Independent IPC adapter. Vendor code stays in the verified installed service. */
final class NwdRadioApi implements IRadioServiceAPI {
    static final String DESCRIPTOR = "com.nwd.radio.service.RadioFeature";
    private final IBinder binder;
    private final NwdAudioRouting audio;
    private long lastBandCommand;
    private volatile boolean autoScanRequested;
    private volatile boolean autoScanObserved;
    private volatile long autoScanStarted;
    private interface Writer { void write(Parcel data); }
    private interface Reader<T> { T read(Parcel reply) throws RemoteException; }

    static final class Frequency {
        final int rawBand;
        final int khz;
        final String name;
        Frequency(int rawBand, int khz, String name) {
            this.rawBand = rawBand; this.khz = khz; this.name = name == null ? "" : name;
        }
        int band() throws RemoteException { return appBand(rawBand); }
    }

    NwdRadioApi(IBinder binder, NwdAudioRouting audio) throws RemoteException {
        if (binder == null || !DESCRIPTOR.equals(binder.getInterfaceDescriptor()) || audio == null) {
            throw new RemoteException("Unexpected NWD radio endpoint");
        }
        this.binder = binder;
        this.audio = audio;
        int type = call(29, null, Parcel::readInt);
        // This APK includes several hardware implementations. The inspected
        // Allwinner path reports 2; e.g. the SPRD path reports 3 but its LOCAL
        // setter only updates a field. Do not claim their runtime behavior by ABI.
        if (type != 2) throw new RemoteException("Unverified NWD tuner implementation");
    }

    private <T> T call(int code, Writer writer, Reader<T> reader) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (writer != null) writer.write(data);
            if (!binder.transact(code, data, reply, 0)) {
                throw new RemoteException("NWD radio rejected transaction " + code);
            }
            reply.readException();
            if (reader != null && reply.dataAvail() < 4) throw new RemoteException("Truncated NWD reply");
            T result = reader == null ? null : reader.read(reply);
            if (reply.dataAvail() != 0) throw new RemoteException("Unexpected NWD reply layout");
            return result;
        } finally { reply.recycle(); data.recycle(); }
    }
    private void command(int code, Writer writer) throws RemoteException { call(code, writer, null); }
    private boolean flag(int code) throws RemoteException { return call(code, null, p -> p.readInt() != 0); }

    Frequency frequency() throws RemoteException {
        return call(2, null, p -> {
            if (p.readInt() != 1) throw new RemoteException("NWD frequency unavailable");
            if (p.dataAvail() < 12) throw new RemoteException("Truncated NWD frequency");
            // OEM Parcelable order is byte band, String PS, int frequency.
            int band = p.readByte();
            appBand(band);
            String name = p.readString();
            if (p.dataAvail() < 4) throw new RemoteException("Truncated NWD frequency value");
            int raw = p.readInt();
            long khz = band < 3 ? (long) raw * 10 : raw;
            if (khz < 0 || khz > Integer.MAX_VALUE) throw new RemoteException("Invalid NWD frequency");
            return new Frequency(band, (int) khz, name);
        });
    }

    private static int appBand(int raw) throws RemoteException {
        if (raw >= 0 && raw < 3) return raw;
        if (raw == 3 || raw == 4) return 3;
        throw new RemoteException("Unsupported NWD radio band " + raw);
    }

    private void verifyGrid(int band, int khz) throws RemoteException {
        int raw = band < 3 ? khz / 10 : khz;
        boolean valid = call(22, null, p -> {
            int count = p.readInt();
            if (count < 0) return false;
            if (count > 3) throw new RemoteException("Invalid NWD frequency-grid array");
            boolean found = false;
            for (int i = 0; i < count; i++) {
                int present = p.readInt();
                if (present == 0) continue;
                if (present != 1 || p.dataAvail() < 12) throw new RemoteException("Invalid NWD grid parcel");
                int min = p.readInt(), max = p.readInt(), step = p.readInt();
                if (i == (band < 3 ? 0 : 1)) {
                    found = min > 0 && max >= min && step > 0 && raw >= min && raw <= max
                            && ((long) raw - min) % step == 0;
                }
            }
            return found;
        });
        if (!valid) throw new RemoteException("NWD frequency grid unavailable or target unsupported");
    }

    synchronized boolean tuneToBand(int band, int khz, BooleanSupplier current) throws RemoteException {
        if (!FrequencyRules.isValid(band, khz)) throw new RemoteException("Invalid NWD tuning target");
        if (!canContinue(current)) return false;
        int state = radioState();
        if (state == 2 || state == 3) {
            stopSearch(current);
            // A canceled AMS posts its final preset tune after reporting normal.
            // Do not let that queued tune overwrite the user's selection.
            for (int i = 0; i < 8; i++) {
                if (!canContinue(current)) return false;
                SystemClock.sleep(100);
            }
        }
        verifyGrid(band, khz);
        Frequency observed = frequency();
        // The Allwinner implementation ignores band when presetIndex=0. Cycle
        // and confirm first; never use a preset index that could overwrite a slot.
        for (int guard = 0; observed.band() != band && guard < 5; guard++) {
            if (!canContinue(current)) return false;
            int before = observed.rawBand;
            observed = nextBand(current);
            if (observed.rawBand == before) return false;
        }
        if (observed.band() != band || !canContinue(current)) return false;
        int rawBand = observed.rawBand;
        verifyGrid(band, khz);
        if (frequency().rawBand != rawBand || !canContinue(current)) return false;
        command(1, p -> { p.writeInt(band < 3 ? khz / 10 : khz); p.writeByte((byte) rawBand); p.writeInt(0); });
        return awaitFrequency(rawBand, khz, current);
    }
    private Frequency nextBand(BooleanSupplier current) throws RemoteException {
        // The inspected AW implementation discards band changes less than
        // 500 ms apart, even when the previous frequency is already reported.
        long wait = 550 - (SystemClock.elapsedRealtime() - lastBandCommand);
        while (wait > 0 && canContinue(current)) {
            SystemClock.sleep(Math.min(50, wait));
            wait = 550 - (SystemClock.elapsedRealtime() - lastBandCommand);
        }
        Frequency before = frequency();
        if (!canContinue(current)) return before;
        lastBandCommand = SystemClock.elapsedRealtime();
        command(5, null);
        for (int attempt = 0; attempt < 20; attempt++) {
            if (!canContinue(current)) return before;
            SystemClock.sleep(100);
            Frequency after = frequency();
            if (after.rawBand != before.rawBand) return after;
        }
        return before;
    }
    private int radioState() throws RemoteException { return call(23, null, p -> (int) p.readByte()); }
    private boolean awaitFrequency(int rawBand, int khz, BooleanSupplier current) throws RemoteException {
        for (int i = 0; i < 20; i++) {
            if (!canContinue(current)) return false;
            Frequency f = frequency();
            if (f.rawBand == rawBand && f.khz == khz) return true;
            SystemClock.sleep(100);
        }
        return false;
    }

    /** Reads every scan-result bank, then restores the post-scan station. */
    synchronized int[] readScanPresets(int band, BooleanSupplier current) throws RemoteException {
        BooleanSupplier caller = current;
        current = () -> caller.getAsBoolean() && audio.readHealth().radioOwnsSource();
        if (!canContinue(current)) throw new RemoteException("NWD scan read canceled");
        stopSearch(current);
        // The vendor posts its final bank update after state=normal. Let that
        // queued update and its final tune settle before selecting another bank.
        for (int i = 0; i < 8; i++) {
            if (!canContinue(current)) throw new RemoteException("NWD scan read canceled");
            SystemClock.sleep(100);
        }
        Frequency original = frequency();
        if ((original.rawBand < 3) != (band < 3)) throw new RemoteException("NWD scan band changed");
        List<Integer> result = new ArrayList<>();
        boolean[] seen = new boolean[5];
        Frequency observed = original;
        try {
            for (int guard = 0; guard < 5; guard++) {
                if (!canContinue(current) || radioState() != 1) throw new RemoteException("NWD scan read interrupted");
                if ((observed.rawBand < 3) == (band < 3)) {
                    for (int value : currentPresets(observed.rawBand)) result.add(value);
                    seen[observed.rawBand] = true;
                }
                boolean complete = band < 3 ? seen[0] && seen[1] && seen[2] : seen[3] && seen[4];
                if (complete) break;
                int before = observed.rawBand;
                observed = nextBand(current);
                if (observed.rawBand == before) throw new RemoteException("NWD preset bank could not be selected");
            }
            if (!(band < 3 ? seen[0] && seen[1] && seen[2] : seen[3] && seen[4])) {
                throw new RemoteException("NWD preset banks incomplete");
            }
        } finally {
            if (canContinue(current)) {
                Frequency restoring = frequency();
                for (int guard = 0; restoring.rawBand != original.rawBand && guard < 5; guard++) {
                    restoring = nextBand(current);
                }
                if (restoring.rawBand != original.rawBand || !canContinue(current)) {
                    throw new RemoteException("NWD station restoration interrupted");
                }
                command(1, p -> { p.writeInt(original.rawBand < 3 ? original.khz / 10 : original.khz);
                    p.writeByte((byte) original.rawBand); p.writeInt(0); });
                if (!awaitFrequency(original.rawBand, original.khz, current)) {
                    throw new RemoteException("NWD station restoration was not confirmed");
                }
            }
        }
        if (!canContinue(current)) throw new RemoteException("NWD scan read canceled");
        return result.stream().mapToInt(Integer::intValue).toArray();
    }
    private int[] currentPresets(int expectedBand) throws RemoteException {
        int[] values = call(21, null, p -> {
            int count = p.readInt();
            if (count != 6) throw new RemoteException("NWD preset bank unavailable");
            int[] entries = new int[count];
            for (int i = 0; i < count; i++) {
                if (p.dataAvail() < 16 || p.readInt() != 1) throw new RemoteException("Malformed NWD preset");
                int rawBand = p.readByte(); p.readString();
                if (rawBand != expectedBand || p.dataAvail() < 4) throw new RemoteException("NWD preset band mismatch");
                int raw = p.readInt();
                long khz = rawBand < 3 ? (long) raw * 10 : raw;
                if (khz > Integer.MAX_VALUE || khz < 0) throw new RemoteException("Invalid NWD preset value");
                // Zero/default padding is removed by the shared sanitizer.
                entries[i] = (int) khz;
            }
            return entries;
        });
        if (frequency().rawBand != expectedBand) throw new RemoteException("NWD preset band changed");
        return values;
    }
    private void stopSearch(BooleanSupplier current) throws RemoteException {
        autoScanRequested = false;
        // Command 27 with 0/0 cancels an active search/INTRO and is otherwise
        // a no-op on the inspected AW path. Unlike AMS, it cannot start a scan.
        command(27, p -> { p.writeByte((byte) 0); p.writeByte((byte) 0); });
        for (int i = 0; i < 30; i++) {
            if (!canContinue(current)) throw new RemoteException("NWD scan stop canceled");
            int state = radioState();
            if (state == 1) return;
            if (state != 2 && state != 3) throw new RemoteException("NWD tuner is not ready");
            SystemClock.sleep(100);
        }
        throw new RemoteException("NWD search did not stop");
    }
    private static boolean canContinue(BooleanSupplier current) {
        return !Thread.currentThread().isInterrupted() && current.getAsBoolean();
    }
    @Override public IBinder asBinder() { return binder; }
    @Override public int getCurrentBand() throws RemoteException { return frequency().band(); }
    @Override public int getCurrentFreq() throws RemoteException { return frequency().khz; }
    @Override public String getCurrentFreqRdsPs() throws RemoteException { return frequency().name; }
    String radioText() throws RemoteException { return call(28, null, Parcel::readString); }
    @Override public void gotoFreq(int khz) throws RemoteException {
        if (!tuneToBand(getCurrentBand(), khz, () -> true)) throw new RemoteException("NWD band changed during tuning");
    }
    @Override public void gotoFreq2(String value) throws RemoteException {
        try { gotoFreq(Integer.parseInt(value)); }
        catch (NumberFormatException error) { throw new RemoteException("Invalid NWD frequency"); }
    }
    @Override public synchronized void onBandEvent() throws RemoteException { nextBand(() -> true); }
    // NWD's search is station seek; its seek is a single tuning step.
    @Override public void onSeekDownEvent() throws RemoteException { command(4, p -> p.writeInt(1)); }
    @Override public void onSeekUpEvent() throws RemoteException { command(4, p -> p.writeInt(0)); }
    @Override public void onManualDownEvent() throws RemoteException { step(1); }
    @Override public void onManualUpEvent() throws RemoteException { step(-1); }
    private void step(int direction) throws RemoteException {
        Frequency f = frequency();
        if (!tuneToBand(f.band(), FrequencyRules.stepFrom(f.band(), f.khz, direction), () -> true)) {
            throw new RemoteException("NWD band changed during tuning");
        }
    }
    @Override public boolean IsStereo() throws RemoteException { return flag(10); }
    @Override public boolean IsDxLocal() throws RemoteException { return flag(9); }
    @Override public void onLocDxEvent() throws RemoteException { boolean near = !IsDxLocal(); command(8, p -> p.writeInt(near ? 1 : 0)); }
    @Override public boolean requestPlayAudio() { return audio.play(); }
    boolean pauseRadioSource() { return audio.pause(); }
    RadioPlaybackHealthReader.Snapshot readHealth() { return audio.readHealth(); }
    @Override public boolean IsAS() throws RemoteException {
        if (!autoScanRequested) return false;
        int state = radioState();
        if (state == 2) { autoScanObserved = true; return true; }
        if (!autoScanObserved && SystemClock.elapsedRealtime() - autoScanStarted < 3000) return true;
        autoScanRequested = false;
        return false;
    }
    @Override public boolean IsPS() throws RemoteException { return call(23, null, p -> p.readByte() == 3); }
    @Override public boolean IsScan() throws RemoteException { return call(23, null, p -> p.readByte() == 2); }
    @Override public boolean IsSeek() throws RemoteException { return IsScan(); }
    @Override public boolean currentFreqIsFavorite() { return false; }
    @Override public boolean getFreqIsFavorite(int band, int khz) { return false; }
    @Override public synchronized void onASEvent() throws RemoteException {
        if (autoScanRequested) {
            // AMS starts asynchronously. Wait for its pending start before
            // sending the non-starting stop command, not another AMS toggle.
            while (!autoScanObserved && radioState() == 1
                    && SystemClock.elapsedRealtime() - autoScanStarted < 3000) {
                if (Thread.currentThread().isInterrupted()) throw new RemoteException("NWD scan stop interrupted");
                SystemClock.sleep(50);
            }
            stopSearch(() -> true);
            return;
        }
        if (radioState() != 1) throw new RemoteException("NWD tuner is busy");
        requestPlayAudio();
        autoScanStarted = SystemClock.elapsedRealtime();
        autoScanObserved = false;
        autoScanRequested = true;
        try { command(6, null); }
        catch (RemoteException | RuntimeException error) { autoScanRequested = false; throw error; }
    }
    @Override public void onPSEvent() throws RemoteException { command(7, null); }
    @Override public void onScanEvent() throws RemoteException { onPSEvent(); }
    @Override public void gotoFreqIndex(int index) throws RemoteException { throw unavailable(); }
    @Override public void favoriteCurrentFreq() throws RemoteException { throw unavailable(); }
    @Override public void registerRadioClientBinder(IBinder client) throws RemoteException { throw unavailable(); }
    @Override public void unRegisterRadioClientBinder() throws RemoteException { throw unavailable(); }
    @Override public void registerRadioCallback(IRadioCallBack callback) throws RemoteException { throw unavailable(); }
    @Override public void unRegisterRadioCallback(IRadioCallBack callback) throws RemoteException { throw unavailable(); }
    @Override public void requestAudioFocus() throws RemoteException { throw unavailable(); }
    @Override public void releaseAudioFocus() throws RemoteException { throw unavailable(); }
    private static RemoteException unavailable() { return new RemoteException("Feature unavailable on this NWD profile"); }
}
