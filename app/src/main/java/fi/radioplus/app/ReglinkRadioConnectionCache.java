package fi.radioplus.app;

import android.os.IBinder;
import android.os.RemoteException;

/** Both app bindings share one observer, scan request and operation lock. */
final class ReglinkRadioConnectionCache {
    private ReglinkRadioApi api;
    private RadioBackendProfile profile;
    synchronized void clear() {
        if (api != null) api.close();
        api = null;
        profile = null;
    }
    synchronized ReglinkRadioApi resolve(IBinder binder, RadioBackendProfile inspected) throws RemoteException {
        if (api != null && profile == inspected && !api.isClosed() && api.asBinder() == binder && binder.isBinderAlive()) return api;
        clear();
        if (binder == null || !binder.isBinderAlive()) throw new RemoteException("Reglink disconnected");
        ReglinkRadioApi candidate = ReglinkRadioApi.stockAssisted(binder, inspected);
        if (!binder.isBinderAlive()) { candidate.close(); throw new RemoteException("Reglink disconnected"); }
        api = candidate;
        profile = inspected;
        return candidate;
    }
}
