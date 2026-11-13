package fi.radioplus.app;

import android.os.IBinder;
import android.os.RemoteException;

/** Both app bindings share one observer, scan request and operation lock. */
final class ReglinkRadioConnectionCache {
    private ReglinkRadioApi api;
    synchronized ReglinkRadioApi resolve(IBinder binder) throws RemoteException {
        if (api != null && !api.isClosed() && api.asBinder() == binder && binder.isBinderAlive()) return api;
        if (api != null) api.close();
        api = null;
        if (binder == null || !binder.isBinderAlive()) throw new RemoteException("Reglink disconnected");
        ReglinkRadioApi candidate = ReglinkRadioApi.stockAssisted(binder);
        if (!binder.isBinderAlive()) { candidate.close(); throw new RemoteException("Reglink disconnected"); }
        api = candidate;
        return candidate;
    }
}
