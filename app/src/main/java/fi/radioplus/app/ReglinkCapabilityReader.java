package fi.radioplus.app;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * Read-only runtime facts for the independently inspected S5.40 protocol.
 * A recognized result is not permission to control hardware: package/component
 * identity, region, live tuner readiness and cooperative ownership still need
 * their own gates. No concrete tuner is bound and no setting is written here.
 */
final class ReglinkCapabilityReader {
    static final String COMMON_DESCRIPTOR = "com.reglink.services.IDroidService";
    static final String SHARED_DESCRIPTOR = "com.reglink.services.ISharedVariableService";
    private static final int MAX_ATTEMPTS = 3;

    enum Kind { UNKNOWN, NATIVE_FM, EXTERNAL_FM_AM }

    static final class Snapshot {
        final String module;
        final boolean initialized;
        final boolean onboardRadio;
        final boolean stable;
        final Kind kind;

        private Snapshot(Sample sample, boolean stable) {
            this.module = sample.module;
            this.initialized = sample.initialized;
            this.onboardRadio = sample.onboardRadio;
            this.stable = stable;
            if (!stable || !initialized) {
                kind = Kind.UNKNOWN;
            } else if (onboardRadio && "mtk_radio".equals(module)) {
                kind = Kind.NATIVE_FM;
            } else if (!onboardRadio && ("4754".equals(module)
                    || "4755".equals(module) || "7786".equals(module))) {
                kind = Kind.EXTERNAL_FM_AM;
            } else {
                kind = Kind.UNKNOWN;
            }
        }

        boolean supportsBand(int band) {
            return kind != Kind.UNKNOWN && (FrequencyRules.isFm(band)
                    || (kind == Kind.EXTERNAL_FM_AM && band == 3));
        }
    }

    private static final class Sample {
        final String module;
        final boolean initialized;
        final boolean onboardRadio;

        Sample(String module, boolean initialized, boolean onboardRadio) {
            this.module = module;
            this.initialized = initialized;
            this.onboardRadio = onboardRadio;
        }

        boolean sameAs(Sample other) {
            return initialized == other.initialized && onboardRadio == other.onboardRadio
                    && (module == null ? other.module == null : module.equals(other.module));
        }
    }

    private interface Writer { void write(Parcel data); }
    private interface Reader<T> { T read(Parcel reply) throws RemoteException; }

    private ReglinkCapabilityReader() { }

    static Snapshot read(IBinder common) throws RemoteException {
        verifyDescriptor(common, COMMON_DESCRIPTOR);
        IBinder shared = transact(common, COMMON_DESCRIPTOR, 2,
                data -> data.writeString("SharedVariable"), Parcel::readStrongBinder);
        verifyDescriptor(shared, SHARED_DESCRIPTOR);
        Sample last = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Sample before = sample(shared, false);
            last = sample(shared, true);
            // Opposite defaults also reject missing boolean fields. The OEM
            // exist() only checks its Bundle, not the reflected Env fields.
            if (before.sameAs(last)) return new Snapshot(last, true);
        }
        return new Snapshot(last, false);
    }

    private static Sample sample(IBinder shared, boolean defaultValue) throws RemoteException {
        boolean initialized = bool(shared, "Env.Init_AllCompleted", defaultValue);
        String module = transact(shared, SHARED_DESCRIPTOR, 7, data -> {
            data.writeString("Env.RadioHwModule");
            data.writeString(null);
        }, Parcel::readString);
        if (module != null && module.length() > 64) {
            throw new RemoteException("Invalid Reglink module reply");
        }
        boolean onboard = bool(shared, "Env.OnboardRadio", defaultValue);
        return new Sample(module, initialized, onboard);
    }

    private static boolean bool(IBinder shared, String key, boolean defaultValue)
            throws RemoteException {
        return transact(shared, SHARED_DESCRIPTOR, 9, data -> {
            data.writeString(key);
            data.writeInt(defaultValue ? 1 : 0);
        }, reply -> {
            int value = reply.readInt();
            if (value != 0 && value != 1) {
                throw new RemoteException("Invalid Reglink capability boolean");
            }
            return value == 1;
        });
    }

    private static void verifyDescriptor(IBinder binder, String expected) throws RemoteException {
        if (binder == null || !expected.equals(binder.getInterfaceDescriptor())) {
            throw new RemoteException("Unexpected Reglink capability Binder");
        }
    }

    private static <T> T transact(IBinder binder, String descriptor, int code,
            Writer writer, Reader<T> reader) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            writer.write(data);
            if (!binder.transact(code, data, reply, 0)) {
                throw new RemoteException("Reglink capability transaction rejected");
            }
            reply.readException();
            if (reply.dataAvail() < 4) throw new RemoteException("Truncated Reglink capability reply");
            T result = reader.read(reply);
            if (reply.dataAvail() != 0) throw new RemoteException("Unexpected Reglink capability layout");
            return result;
        } catch (RuntimeException failure) {
            throw new RemoteException("Malformed Reglink capability reply");
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
