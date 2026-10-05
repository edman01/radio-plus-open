package fi.radioplus.app;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import java.util.function.BooleanSupplier;

import com.hcn.autoradio.IRadioCallBack;
import com.hcn.autoradio.IRadioServiceAPI;

/** Independent IPC adapter for the fingerprinted, inspected TS MainUI APKs only.
 * All vendor native code stays inside the installed stock process. */
final class TsRadioApi implements IRadioServiceAPI {
    static final String COMMON_DESCRIPTOR = "com.ts.main.common.ITsCommon";
    static final String RADIO_DESCRIPTOR = "com.ts.main.common.ITsRadioCommon";
    private final IBinder common;
    private final IBinder radio;

    private interface Writer { void write(Parcel data); }
    private interface Reader<T> { T read(Parcel reply); }

    TsRadioApi(IBinder common) throws RemoteException {
        if (common == null || !COMMON_DESCRIPTOR.equals(common.getInterfaceDescriptor())) {
            throw new RemoteException("Unexpected TS common Binder");
        }
        this.common = common;
        radio = transact(common, COMMON_DESCRIPTOR, 55,
                data -> data.writeString("Radio"), Parcel::readStrongBinder);
        if (radio == null || !RADIO_DESCRIPTOR.equals(radio.getInterfaceDescriptor())) {
            throw new RemoteException("Unexpected TS radio Binder");
        }
    }

    private static <T> T transact(IBinder binder, String descriptor, int code,
            Writer writer, Reader<T> reader) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            if (writer != null) writer.write(data);
            if (!binder.transact(code, data, reply, 0)) {
                throw new RemoteException("TS radio rejected transaction " + code);
            }
            reply.readException();
            return reader == null ? null : reader.read(reply);
        } finally {
            reply.recycle(); data.recycle();
        }
    }

    private int readInt(int code, Writer writer) throws RemoteException {
        return transact(radio, RADIO_DESCRIPTOR, code, writer, Parcel::readInt);
    }
    private void command(int code, Writer writer) throws RemoteException {
        transact(radio, RADIO_DESCRIPTOR, code, writer, null);
    }
    private int nativeBand() throws RemoteException { return readInt(3, null); }
    private int flags() throws RemoteException { return readInt(27, null); }
    private int frequency(int rawBand) throws RemoteException {
        int raw = readInt(25, data -> data.writeInt(1));
        // Stock UI displays FM as raw/100 MHz, AM as raw kHz.
        return rawBand < 4 ? Math.multiplyExact(raw, 10) : raw;
    }
    private static int appBand(int raw) throws RemoteException {
        if (raw >= 0 && raw <= 2) return raw;
        if (raw == 3) return 0; // OEM's combined FM/RDS bank ("OT").
        if (raw == 4 || raw == 5) return 3; // AM1/AM2 share app's AM list.
        throw new RemoteException("Unrecognized TS band " + raw);
    }

    @Override public IBinder asBinder() { return common; }
    @Override public int getCurrentBand() throws RemoteException { return appBand(nativeBand()); }
    @Override public int getCurrentFreq() throws RemoteException { return frequency(nativeBand()); }
    @Override public void gotoFreq(int khz) throws RemoteException {
        tuneFrequency(khz, () -> true);
    }
    private boolean tuneFrequency(int khz, BooleanSupplier stillCurrent) throws RemoteException {
        int rawBand = nativeBand();
        int band = appBand(rawBand);
        if (!FrequencyRules.isValid(band, khz)) throw new RemoteException("Invalid TS frequency");
        int rawFrequency = rawBand < 4 ? khz / 10 : khz;
        // TuneFset accepts a STEP INDEX, unlike GetDisp(1)'s absolute frequency.
        // Read the stock grid rather than assuming a regional minimum or spacing.
        int count = readInt(8, null);
        int first = readInt(9, data -> data.writeInt(0));
        int second = readInt(9, data -> data.writeInt(1));
        int index = TsTuningGrid.indexFor(rawFrequency, first, second, count);
        if (index < 0 || readInt(9, data -> data.writeInt(index)) != rawFrequency
                || nativeBand() != rawBand) {
            throw new RemoteException("TS tuning grid changed or does not contain this frequency");
        }
        if (!canContinue(stillCurrent)) return false;
        command(34, data -> data.writeInt(index));
        return true;
    }
    @Override public void gotoFreq2(String frequency) throws RemoteException {
        try { gotoFreq(Integer.parseInt(frequency)); }
        catch (NumberFormatException error) { throw new RemoteException("Invalid TS frequency"); }
    }
    @Override public void onBandEvent() throws RemoteException { command(19, null); }
    // Preserve the HCN-facing interface used by MainActivity: Down means higher
    // frequency and Up means lower. The TS wire direction is 1=higher, 0=lower.
    @Override public void onSeekDownEvent() throws RemoteException { command(20, data -> data.writeInt(1)); }
    @Override public void onSeekUpEvent() throws RemoteException { command(20, data -> data.writeInt(0)); }
    // The inspected OEM's TuneStep wrapper ignores its direction. Use validated direct tuning.
    private void step(int direction) throws RemoteException {
        int band = getCurrentBand();
        gotoFreq(FrequencyRules.stepFrom(band, getCurrentFreq(), direction));
    }
    @Override public void onManualUpEvent() throws RemoteException { step(-1); }
    @Override public void onManualDownEvent() throws RemoteException { step(1); }

    boolean tuneToBand(int band, int khz) throws RemoteException {
        return tuneToBand(band, khz, () -> true);
    }
    boolean tuneToBand(int band, int khz, BooleanSupplier stillCurrent) throws RemoteException {
        if (!FrequencyRules.isValid(band, khz)) throw new RemoteException("Invalid TS tuning target");
        if (!canContinue(stillCurrent)) return false;
        int rawBand = nativeBand();
        int observed = appBand(rawBand);
        if (FrequencyRules.isFm(observed) != FrequencyRules.isFm(band)) {
            if (!canContinue(stillCurrent)) return false;
            command(FrequencyRules.isFm(band) ? 14 : 15, null);
            int changed = waitForNativeBandChange(rawBand, stillCurrent);
            if (changed == rawBand) return false;
            rawBand = changed;
            observed = appBand(rawBand);
        }
        // Do not issue a frequency for the wrong bank. Some RDS configurations expose
        // only the combined FM bank; report failure instead of repeatedly retuning AM.
        for (int guard = 0; observed != band && guard < 6; guard++) {
            if (!canContinue(stillCurrent)) return false;
            command(19, null);
            int changed = waitForNativeBandChange(rawBand, stillCurrent);
            if (changed == rawBand) return false;
            rawBand = changed;
            observed = appBand(rawBand);
        }
        if (observed != band) return false;
        return tuneFrequency(khz, stillCurrent);
    }
    private static boolean canContinue(BooleanSupplier stillCurrent) {
        return !Thread.currentThread().isInterrupted() && stillCurrent.getAsBoolean();
    }
    private int waitForNativeBandChange(int before, BooleanSupplier stillCurrent) throws RemoteException {
        for (int attempt = 0; attempt < 8; attempt++) {
            if (!canContinue(stillCurrent)) return before;
            // AM1 and AM2 share one app list, but are distinct steps in the OEM cycle.
            int band = nativeBand();
            if (band != before) return band;
            SystemClock.sleep(100);
        }
        return before;
    }
    @Override public boolean requestPlayAudio() throws RemoteException {
        command(29, data -> data.writeInt(1)); // Same source selection as stock radio resume.
        return true; // Command accepted; NOT proof of audible audio.
    }
    RadioPlaybackHealthReader.Snapshot readHealth() throws RemoteException {
        int mode = transact(common, COMMON_DESCRIPTOR, 10, null, Parcel::readInt);
        return new RadioPlaybackHealthReader.Snapshot(mode >= 0,
                mode == 1 ? "com.ts.MainUI" : "ts.source/" + mode, true, (flags() & 8) != 0);
    }
    boolean pauseRadioSource() throws RemoteException {
        int mode = transact(common, COMMON_DESCRIPTOR, 10, null, Parcel::readInt);
        if (mode != 1) return false; // Never stop a source that replaced radio.
        command(29, data -> data.writeInt(0)); // Stock radio exit; no global mute toggle.
        return true;
    }
    @Override public String getCurrentFreqRdsPs() throws RemoteException {
        String text = transact(radio, RADIO_DESCRIPTOR, 30, null, Parcel::readString);
        return text == null ? "" : text.replace("\u0000", "").trim();
    }
    @Override public boolean IsStereo() throws RemoteException { return (flags() & 1) != 0; }
    @Override public boolean IsDxLocal() throws RemoteException { return (flags() & 4) != 0; }
    // Scan-state semantics and sensitivity control are not verified in this sample.
    // Their UI controls are disabled. Polling never invokes a command to infer state.
    @Override public boolean IsAS() { return false; }
    @Override public boolean IsPS() { return false; }
    @Override public boolean IsScan() { return false; }
    @Override public boolean IsSeek() { return false; }
    @Override public boolean currentFreqIsFavorite() { return false; }
    @Override public boolean getFreqIsFavorite(int band, int frequency) { return false; }
    @Override public void registerRadioClientBinder(IBinder binder) throws RemoteException { throw unavailable(); }
    @Override public void unRegisterRadioClientBinder() throws RemoteException { throw unavailable(); }
    @Override public void registerRadioCallback(IRadioCallBack callback) throws RemoteException { throw unavailable(); }
    @Override public void unRegisterRadioCallback(IRadioCallBack callback) throws RemoteException { throw unavailable(); }
    @Override public void onASEvent() throws RemoteException { throw unavailable(); }
    @Override public void onPSEvent() throws RemoteException { throw unavailable(); }
    @Override public void onScanEvent() throws RemoteException { throw unavailable(); }
    @Override public void onLocDxEvent() throws RemoteException { throw unavailable(); }
    @Override public void gotoFreqIndex(int index) throws RemoteException { throw unavailable(); }
    @Override public void favoriteCurrentFreq() throws RemoteException { throw unavailable(); }
    @Override public void requestAudioFocus() throws RemoteException { throw unavailable(); }
    @Override public void releaseAudioFocus() throws RemoteException { throw unavailable(); }
    private static RemoteException unavailable() {
        return new RemoteException("This feature is not verified for this TS profile");
    }
}
