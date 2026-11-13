package fi.radioplus.app;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/** Read-only source checks. Never registers, activates or replaces an OEM client. */
final class ReglinkStockSession {
    static final String HUB_DESCRIPTOR = "com.reglink.services.IMediaHubService";
    static final String MEDIA_DESCRIPTOR = "com.reglink.services.IMediaService";
    private final IBinder hub;
    private final IBinder media;
    private volatile int lastLoggedState = -1;

    private interface Writer { void write(Parcel data); }
    private interface Reader<T> { T read(Parcel reply); }

    static final class State {
        final boolean stockOwnsRoute;
        final boolean globallyMuted;
        State(boolean owns, boolean muted) { stockOwnsRoute = owns; globallyMuted = muted; }
    }

    ReglinkStockSession(IBinder common) throws RemoteException {
        hub = service(common, "MediaHub", HUB_DESCRIPTOR);
        media = service(common, "Media", MEDIA_DESCRIPTOR);
    }

    private static IBinder service(IBinder common, String name, String descriptor) throws RemoteException {
        IBinder binder = read(common, ReglinkRadioApi.COMMON_DESCRIPTOR, 2,
                data -> data.writeString(name), Parcel::readStrongBinder);
        if (binder == null || !descriptor.equals(binder.getInterfaceDescriptor())) {
            throw new RemoteException("Unexpected Reglink " + name + " endpoint");
        }
        return binder;
    }

    State read() throws RemoteException {
        // Double sampling rejects an inconsistent route; it is not an exclusive
        // lease. This test integration intentionally keeps the real OEM owner.
        String before = read(hub, HUB_DESCRIPTOR, 7, null, Parcel::readString);
        int[] source = source();
        boolean muted = read(media, MEDIA_DESCRIPTOR, 6, null, reply -> {
            int value = reply.readInt();
            if (value != 0 && value != 1) throw new IllegalArgumentException("Invalid mute flag");
            return value == 1;
        });
        int[] afterSource = source();
        String after = read(hub, HUB_DESCRIPTOR, 7, null, Parcel::readString);
        boolean same = java.util.Arrays.equals(source, afterSource);
        State state = new State("radio".equals(before) && "radio".equals(after) && same
                && source != null && source[0] == 2 && source[1] == 1
                && source[2] == 0 && source[3] == 0, muted);
        int flags = (state.stockOwnsRoute ? 1 : 0) | (muted ? 2 : 0);
        if (lastLoggedState != flags) {
            lastLoggedState = flags;
            SupportTestLog.event(SupportTestLog.Event.SOURCE, state.stockOwnsRoute ? 1 : 0, muted ? 1 : 0);
        }
        return state;
    }

    void requireRoute(boolean allowScanMute) throws RemoteException {
        State state = read();
        if (!state.stockOwnsRoute || (state.globallyMuted && !allowScanMute)) {
            SupportTestLog.problem(!state.stockOwnsRoute ? SupportTestLog.Problem.STOCK_NOT_OWNER : SupportTestLog.Problem.GLOBAL_MUTE);
            throw new ReglinkRadioApi.CommandRejectedException(
                    "Start the stock radio first and leave it running in the background");
        }
    }

    private int[] source() throws RemoteException {
        try { return read(media, MEDIA_DESCRIPTOR, 3, null, reply -> {
            int present = reply.readInt();
            if (present == 0) return null;
            if (present != 1 || reply.dataAvail() != 20) {
                throw new IllegalArgumentException("Unexpected AudioSource layout");
            }
            // platform, model, mix, flags, action: five primitive wire fields.
            return new int[]{reply.readInt(), reply.readInt(), reply.readInt(), reply.readInt(), reply.readInt()};
        }); } catch (RemoteException error) {
            SupportTestLog.problem(SupportTestLog.Problem.SOURCE_READ_FAILED);
            throw error;
        }
    }

    private static <T> T read(IBinder binder, String descriptor, int code, Writer writer, Reader<T> reader)
            throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            if (writer != null) writer.write(data);
            if (!binder.transact(code, data, reply, 0) || reply.dataAvail() < 4) {
                throw new RemoteException("Reglink source read rejected");
            }
            reply.readException();
            if (reply.dataAvail() < 4) throw new RemoteException("Truncated Reglink source reply");
            T result = reader.read(reply);
            if (reply.dataAvail() != 0) throw new RemoteException("Unexpected Reglink source reply");
            return result;
        } catch (RuntimeException failure) {
            throw new RemoteException("Malformed Reglink source reply");
        } finally { data.recycle(); reply.recycle(); }
    }
}
