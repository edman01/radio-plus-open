package fi.radioplus.app;

import android.os.IBinder;
import android.os.RemoteException;

/** One operation/confirmation state for both app bindings to a verified SPD endpoint. */
final class SpdRadioConnectionCache {
    private SpdRadioApi api;
    private SpdAudioSourceReader source;

    synchronized SpdRadioApi resolve(IBinder binder, SpdAudioSourceReader reader) throws RemoteException {
        if (api != null && api.asBinder() == binder && source == reader && binder.isBinderAlive()) return api;
        api = null;
        source = null;
        if (binder == null || !binder.isBinderAlive()) throw new RemoteException("SPD endpoint disconnected");
        SpdRadioApi candidate = new SpdRadioApi(binder, reader);
        if (!binder.isBinderAlive()) throw new RemoteException("SPD disconnected during validation");
        api = candidate;
        source = reader;
        return candidate;
    }
}
