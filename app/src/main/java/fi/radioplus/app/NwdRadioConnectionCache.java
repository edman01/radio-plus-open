package fi.radioplus.app;

import android.os.IBinder;
import android.os.RemoteException;

/** Shares tuner state only after the factory has verified the installed APK pair. */
final class NwdRadioConnectionCache {
    private NwdRadioApi api;
    private NwdAudioRouting audio;

    synchronized NwdRadioApi resolve(IBinder binder, NwdAudioRouting routing)
            throws RemoteException {
        if (api != null && api.asBinder() == binder && audio == routing
                && binder.isBinderAlive()) {
            return api;
        }
        // A replacement must pass descriptor/type validation independently. If
        // it fails, neither it nor the previous connection remains reusable.
        api = null;
        audio = null;
        if (binder == null || !binder.isBinderAlive()) {
            throw new RemoteException("NWD radio endpoint is not alive");
        }
        NwdRadioApi candidate = new NwdRadioApi(binder, routing);
        if (!binder.isBinderAlive()) {
            throw new RemoteException("NWD radio endpoint disconnected during validation");
        }
        api = candidate;
        audio = routing;
        return candidate;
    }
}
