package fi.radioplus.app;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import java.util.function.BooleanSupplier;

/** S6.02: a per-client fallback, not a global key hook or an OEM focus owner. */
final class ReglinkWheelKeys extends Binder implements AutoCloseable {
    static final String KEY = "com.reglink.services.IKeyService";
    static final String CLIENT = "com.reglink.services.IKeyClient";
    static final String CALLBACK = "com.reglink.services.IKeyCallback";
    interface Skip { boolean post(boolean next); }
    private final IBinder common, keys, shared, hub;
    private final int serviceUid;
    private final BooleanSupplier live;
    private final Skip skip;
    private volatile boolean armed;
    private boolean registered;
    private long acceptedStamp;
    private int acceptedCode;
    private Signals signals;

    ReglinkWheelKeys(IBinder common, int serviceUid, BooleanSupplier live, Skip skip) throws RemoteException {
        this.serviceUid = serviceUid;
        this.common = common;
        this.live = live;
        this.skip = skip;
        keys = ReglinkStockSession.service(common, "Key", KEY);
        shared = ReglinkStockSession.service(common, "SharedVariable", ReglinkCapabilityReader.SHARED_DESCRIPTOR);
        hub = ReglinkStockSession.service(common, "MediaHub", ReglinkStockSession.HUB_DESCRIPTOR);
        attachInterface(null, CALLBACK);
    }

    // Open/observe/close run on one worker. The only remotely-callable operation
    // is the synchronous, pass-through-by-default callback below.
    void open() throws RemoteException {
        registered = true; // also clean up a partially successful transaction
        try {
            IBinder own = ReglinkRadioApi.transact(keys, KEY, 5, p -> p.writeStrongBinder(this), Parcel::readStrongBinder);
            if (own == null || !CLIENT.equals(own.getInterfaceDescriptor()))
                throw new RemoteException("Unexpected Reglink key client");
            ReglinkRadioApi.transact(own, CLIENT, 4, p -> p.writeInt(1), null);
            armed = true;
            SupportTestLog.event(SupportTestLog.Event.WHEEL_LISTENER, 1, 0);
        } catch (RemoteException | RuntimeException e) { close(); throw e; }
    }

    @Override public void close() {
        armed = false;
        stopObservation();
        if (!registered) return;
        registered = false;
        try { ReglinkRadioApi.transact(keys, KEY, 6, p -> p.writeStrongBinder(this), null); }
        catch (RemoteException | RuntimeException e) { SupportTestLog.failure(SupportTestLog.Event.WHEEL_LISTENER, e); }
    }

    static final class Arrow {
        final int code, handled;
        final long stamp;
        Arrow(int code, long stamp, int handled) { this.code = code; this.stamp = stamp; this.handled = handled; }
    }

    // Read only the two wheel arrows. Do not read, retain or export string keys.
    static Arrow arrow(Parcel data) {
        try {
            if (data.dataAvail() < 4 || data.readInt() != 1 || data.dataAvail() < 36) return null;
            int code = data.readInt(), action = data.readByte();
            long stamp = data.readLong();
            int handled = data.readInt(), from = data.readByte(), held = data.readInt();
            data.readInt(); // model key, not used
            int length = data.readInt(); // nullable UTF-16 string
            long bytes = length < 0 ? 0 : ((2L * (length + 1L) + 3L) & ~3L);
            if (length < -1 || bytes != data.dataAvail() || (code != 65 && code != 66)
                    || action != 3 || from != 1 || held != 0 || handled < 0 || stamp <= 0) return null;
            return new Arrow(code, stamp, handled);
        } finally { data.setDataPosition(data.dataSize()); }
    }

    private boolean outsideCallAndReverse() throws RemoteException {
        if (ReglinkCapabilityReader.bool(shared, "Env.IsReversing", true)) return false;
        String hfp = ReglinkRadioApi.transact(shared, ReglinkCapabilityReader.SHARED_DESCRIPTOR, 7,
                p -> { p.writeString("phone.hfp_state"); p.writeString(null); }, Parcel::readString);
        return "connected".equals(hfp) || "disconnected".equals(hfp)
                || (hfp == null && !ReglinkCapabilityReader.bool(shared, "bt.connected", true));
    }

    private synchronized boolean receive(Arrow a) throws RemoteException {
        if (a == null || a.handled != 0 || !armed || !live.getAsBoolean()) return false;
        if (a.stamp == acceptedStamp && a.code == acceptedCode) return true;
        if (!outsideCallAndReverse() || !"radio".equals(ReglinkRadioApi.transact(
                hub, ReglinkStockSession.HUB_DESCRIPTOR, 7, null, Parcel::readString))
                || !live.getAsBoolean()) {
            SupportTestLog.event(SupportTestLog.Event.WHEEL_ARROW, a.code, 0);
            return false;
        }
        boolean accepted = skip.post(a.code == 66);
        if (accepted) { acceptedStamp = a.stamp; acceptedCode = a.code; }
        SupportTestLog.event(SupportTestLog.Event.WHEEL_ARROW, a.code, accepted ? 1 : 0);
        return accepted;
    }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code != IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags);
        if (reply == null) return false;
        data.enforceInterface(CALLBACK);
        boolean accepted = false;
        if (Binder.getCallingUid() == serviceUid) {
            try { accepted = receive(arrow(data)); }
            catch (RuntimeException | RemoteException e) { SupportTestLog.failure(SupportTestLog.Event.WHEEL_ARROW, e); }
        }
        reply.writeNoException();
        reply.writeInt(accepted ? 1 : 0);
        return true;
    }

    // The key dispatcher announces an event before choosing the owner. Subscribe
    // to that single topic for diagnostics. Never initiate a tune from a notice.
    static final String MESSAGE = "com.reglink.services.IMessageService";
    static final String MESSAGE_CLIENT = "com.reglink.services.IMessageClient";
    static final String MESSAGE_CALLBACK = "com.reglink.services.IMessageCallback";
    static final int KEY_NOTICE = 430;
    void startObservation() throws RemoteException {
        if (!armed || signals != null) return;
        IBinder bus = ReglinkStockSession.service(common, "Message", MESSAGE);
        Signals s = new Signals(bus);
        signals = s;
        try {
            IBinder own = ReglinkRadioApi.transact(bus, MESSAGE, 3, p -> p.writeStrongBinder(s), Parcel::readStrongBinder);
            if (own == null || !MESSAGE_CLIENT.equals(own.getInterfaceDescriptor())) throw new RemoteException("Unexpected message client");
            ReglinkRadioApi.transact(own, MESSAGE_CLIENT, 3, p -> p.writeIntArray(new int[]{KEY_NOTICE}), null);
            s.active = true;
            SupportTestLog.event(SupportTestLog.Event.WHEEL_OBSERVED, 0, 1);
        } catch (RemoteException | RuntimeException e) { stopObservation(); throw e; }
    }

    void stopObservation() {
        Signals s = signals;
        if (s == null) return;
        s.active = false;
        signals = null;
        try { ReglinkRadioApi.transact(s.bus, MESSAGE, 4, p -> p.writeStrongBinder(s), null); }
        catch (RemoteException | RuntimeException e) { SupportTestLog.failure(SupportTestLog.Event.WHEEL_OBSERVED, e); }
    }

    private final class Signals extends Binder {
        final IBinder bus;
        volatile boolean active;
        int previous;
        long last;
        Signals(IBinder bus) { this.bus = bus; attachInterface(null, MESSAGE_CALLBACK); }
        @Override protected synchronized boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code != IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags);
            if (reply == null) return false;
            data.enforceInterface(MESSAGE_CALLBACK);
            if (active && armed && Binder.getCallingUid() == serviceUid && live.getAsBoolean()
                    && data.dataAvail() >= 12 && data.readInt() == 1 && data.readInt() == KEY_NOTICE) {
                int arrow = data.readInt();
                long now = android.os.SystemClock.elapsedRealtime();
                if ((arrow == 65 || arrow == 66) && (arrow != previous || now - last > 300)) {
                    previous = arrow; last = now;
                    SupportTestLog.event(SupportTestLog.Event.WHEEL_OBSERVED, arrow, 0);
                }
            }
            // Do not read the string or Bundle. No reply controls OEM dispatch.
            reply.writeNoException();
            return true;
        }
    }
}
