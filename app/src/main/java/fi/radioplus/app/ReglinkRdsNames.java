package fi.radioplus.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/** Optional read-only view of the stock FM station-name cache. Never starts the decoder. */
final class ReglinkRdsNames implements ServiceConnection, AutoCloseable {
    static final String PACKAGE = "yellow.android.rds_daemon";
    static final String APK_SHA256 = "63349e3b89f10ab09b8a7a64edcb17d2ee9defddd4ca15c25623ff28f40fdc54";
    static final ComponentName COMPONENT = new ComponentName(PACKAGE, "com.android.fmradio.RdsService");
    static final String DESCRIPTOR = "com.android.fmradio.IMtkRdsService";
    private final Context context;
    private volatile IBinder endpoint;
    private boolean needsUnbind, closed;

    ReglinkRdsNames(Context context) { this.context = context; }

    synchronized void bindIfRunning() {
        if (closed || needsUnbind) return;
        needsUnbind = true;
        try {
            // Deliberately omit BIND_AUTO_CREATE. Stock alone decides if the
            // hardware decoder should run; joining does not program AF or RF.
            if (context.bindService(new Intent().setComponent(COMPONENT), this, 0)) return;
        } catch (RuntimeException unavailable) { /* optional; never break tuning */ }
        // Android asks clients to release even a failed or denied binding.
        close();
    }

    @Override public synchronized void onServiceConnected(ComponentName name, IBinder service) {
        if (closed || !COMPONENT.equals(name) || service == null) return;
        try {
            endpoint = DESCRIPTOR.equals(service.getInterfaceDescriptor()) ? service : null;
        } catch (RemoteException | RuntimeException unavailable) { endpoint = null; }
    }
    @Override public synchronized void onServiceDisconnected(ComponentName name) { endpoint = null; }
    @Override public void onBindingDied(ComponentName name) { close(); }

    boolean isConnected() { return endpoint != null; }

    String nameAt(int band, int khz) {
        IBinder current = endpoint;
        int raw = khz / 10;
        if (current == null || !FrequencyRules.isFm(band)
                || khz <= 0 || ReglinkTuningRules.observedKhz("fm", raw) != khz) return "";
        try {
            String name = ReglinkRadioApi.transact(current, DESCRIPTOR, 4, data -> data.writeInt(raw),
                    reply -> readStationName(reply, raw));
            return current == endpoint ? name : "";
        } catch (RemoteException | RuntimeException unavailable) {
            // An absent/incompatible cache is not a radio connection failure.
            synchronized (this) { if (current == endpoint) endpoint = null; }
            return "";
        }
    }

    private static String readStationName(Parcel p, int raw) throws RemoteException {
        if (!flag(p)) return "";
        int frequency = integer(p), fields = integer(p);
        integer(p); integer(p); // PI and programme type
        flag(p); flag(p); // traffic flags
        String name = flag(p) ? p.readString() : null;
        if (flag(p)) p.readString(); // radio text is not a station name
        if (flag(p)) {
            int count = integer(p); // skip AF entries without allocating from a remote count
            if (count < 0 || count > p.dataAvail() / 4) throw new RemoteException("Incomplete AF list");
            p.setDataPosition(p.dataPosition() + count * 4);
        }
        return frequency == raw && (fields & 16) != 0 ? RadioMetadataReader.clean(name) : "";
    }

    private static int integer(Parcel p) throws RemoteException {
        if (p.dataAvail() < 4) throw new RemoteException("Incomplete station name reply");
        return p.readInt();
    }
    private static boolean flag(Parcel p) throws RemoteException {
        int value = integer(p);
        if (value != 0 && value != 1) throw new RemoteException("Invalid station name flag");
        return value == 1;
    }

    @Override public synchronized void close() {
        closed = true;
        endpoint = null;
        if (needsUnbind) {
            needsUnbind = false;
            try { context.unbindService(this); } catch (IllegalArgumentException | SecurityException ignored) { }
        }
    }
}
