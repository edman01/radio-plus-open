package fi.radioplus.app;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import com.hcn.autoradio.IRadioCallBack;
import com.hcn.autoradio.IRadioServiceAPI;

/**
 * Interoperability adapter for the inspected 25-transaction HCN contract.
 * Transactions 17 onward differ from the V7 API despite identical descriptors.
 * In particular legacy transaction 25 plays audio; it is NOT a scan-state read.
 */
final class LegacyHcnRadioApi implements IRadioServiceAPI {
    private final IBinder binder;

    LegacyHcnRadioApi(IBinder binder) { this.binder = binder; }

    @Override public IBinder asBinder() { return binder; }

    private interface Writer { void write(Parcel data); }

    private int call(int code, Writer writer, boolean returnsInteger) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(RadioApiFactory.DESCRIPTOR);
            if (writer != null) writer.write(data);
            if (!binder.transact(code, data, reply, 0)) {
                throw new RemoteException("Legacy radio rejected transaction " + code);
            }
            reply.readException();
            return returnsInteger ? reply.readInt() : 0;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void command(int code) throws RemoteException { call(code, null, false); }
    private boolean flag(int code) throws RemoteException { return call(code, null, true) != 0; }

    @Override public void registerRadioClientBinder(IBinder client) throws RemoteException {
        call(1, data -> data.writeStrongBinder(client), false);
    }
    @Override public void unRegisterRadioClientBinder() throws RemoteException { command(2); }
    @Override public void registerRadioCallback(IRadioCallBack callback) throws RemoteException {
        call(3, data -> data.writeStrongBinder(callback == null ? null : callback.asBinder()), false);
    }
    @Override public void unRegisterRadioCallback(IRadioCallBack callback) throws RemoteException {
        call(4, data -> data.writeStrongBinder(callback == null ? null : callback.asBinder()), false);
    }
    @Override public void onBandEvent() throws RemoteException { command(5); }
    @Override public void onASEvent() throws RemoteException { command(6); }
    @Override public void onPSEvent() throws RemoteException { command(7); }
    @Override public void onLocDxEvent() throws RemoteException { command(8); }
    @Override public void onSeekDownEvent() throws RemoteException { command(9); }
    @Override public void onSeekUpEvent() throws RemoteException { command(10); }
    @Override public void onManualUpEvent() throws RemoteException { command(11); }
    @Override public void onManualDownEvent() throws RemoteException { command(12); }
    @Override public void onScanEvent() throws RemoteException { command(13); }
    @Override public void gotoFreq(int frequency) throws RemoteException {
        call(14, data -> data.writeInt(frequency), false);
    }
    @Override public void gotoFreq2(String frequency) throws RemoteException {
        call(15, data -> data.writeString(frequency), false);
    }
    // The inspected service implements gotoFreqIndex as a no-op. Do not pretend it tunes.
    @Override public void gotoFreqIndex(int index) throws RemoteException {
        throw unavailable("preset-index tuning");
    }
    @Override public int getCurrentBand() throws RemoteException { return call(17, null, true); }
    @Override public int getCurrentFreq() throws RemoteException { return call(18, null, true); }
    @Override public boolean IsAS() throws RemoteException { return flag(19); }
    @Override public boolean IsPS() throws RemoteException { return flag(20); }
    @Override public boolean IsScan() throws RemoteException { return flag(21); }
    @Override public boolean IsSeek() throws RemoteException { return flag(22); }
    @Override public boolean IsStereo() throws RemoteException { return flag(23); }
    @Override public boolean IsDxLocal() throws RemoteException { return flag(24); }
    @Override public boolean requestPlayAudio() throws RemoteException { return flag(25); }

    // Optional state absent from this Binder contract. Framework metadata is read separately.
    @Override public String getCurrentFreqRdsPs() { return ""; }
    @Override public boolean getFreqIsFavorite(int band, int frequency) { return false; }
    @Override public boolean currentFreqIsFavorite() { return false; }
    @Override public void favoriteCurrentFreq() throws RemoteException {
        throw unavailable("OEM favorites");
    }
    @Override public void requestAudioFocus() throws RemoteException {
        throw unavailable("separate audio-focus request");
    }
    @Override public void releaseAudioFocus() throws RemoteException {
        throw unavailable("separate audio-focus release");
    }
    private static RemoteException unavailable(String feature) {
        return new RemoteException("Legacy HCN does not expose " + feature);
    }
}
