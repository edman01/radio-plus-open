package fi.radioplus.app;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Read-only IPC for the separately identified SPD OEM contract. No source or tuner writes. */
final class SpdRadioProbe {
    static final String DESCRIPTOR = "com.spd.radio.IRadioAidlInterface";
    private static final int MAX_REPLY_BYTES = 8192;
    private static final int MAX_BANDS = 4;
    private final IBinder binder;

    static final class Frequency {
        final SpdTuningGrid grid;
        final String ps;
        final int pi;
        final int signal;

        private Frequency(SpdTuningGrid grid, String ps, int pi, int signal) {
            this.grid = grid;
            this.ps = ps;
            this.pi = pi;
            this.signal = signal;
        }
    }

    static final class Status {
        final int signal;
        final boolean stereo;
        final boolean trafficProgram;
        final boolean trafficAnnouncement;
        final boolean seeking;
        final boolean preview;
        final boolean autoSearch;
        final int local;
        final int programType;
        final int playState;

        private Status(int signal, boolean stereo, boolean trafficProgram,
                boolean trafficAnnouncement, boolean seeking, boolean preview,
                boolean autoSearch, int local, int programType, int playState) {
            this.signal = signal;
            this.stereo = stereo;
            this.trafficProgram = trafficProgram;
            this.trafficAnnouncement = trafficAnnouncement;
            this.seeking = seeking;
            this.preview = preview;
            this.autoSearch = autoSearch;
            this.local = local;
            this.programType = programType;
            this.playState = playState;
        }
    }

    static final class Snapshot {
        final Frequency frequency;
        final Status status;
        final List<String> bands;

        private Snapshot(Frequency frequency, Status status, String[] bands) {
            this.frequency = frequency;
            this.status = status;
            this.bands = Collections.unmodifiableList(Arrays.asList(bands.clone()));
        }
    }

    private interface Request { void write(Parcel parcel); }
    private interface Response<T> { T read(Parcel parcel) throws RemoteException; }

    SpdRadioProbe(IBinder binder) throws RemoteException {
        if (binder == null) throw problem("Missing SPD endpoint");
        try {
            if (!DESCRIPTOR.equals(binder.getInterfaceDescriptor())) {
                throw problem("Unexpected SPD endpoint descriptor");
            }
        } catch (RuntimeException error) {
            throw problem("SPD endpoint descriptor unavailable");
        }
        this.binder = binder;
    }

    IBinder asBinder() { return binder; }

    Frequency readFrequency() throws RemoteException {
        return read(5, null, SpdRadioProbe::frequency);
    }

    Frequency readBand(String nativeBand) throws RemoteException {
        requireBand(nativeBand);
        Frequency result = read(6, request -> request.writeString(nativeBand),
                SpdRadioProbe::frequency);
        if (!nativeBand.equals(result.grid.nativeBand)) {
            throw problem("SPD band query returned another band");
        }
        return result;
    }

    Status readStatus() throws RemoteException {
        return read(13, null, reply -> {
            present(reply);
            int signal = boundedInt(reply, 0, 65535);
            boolean stereo = bool(reply);
            boolean trafficProgram = bool(reply);
            boolean trafficAnnouncement = bool(reply);
            boolean seeking = bool(reply);
            boolean preview = bool(reply);
            boolean autoSearch = bool(reply);
            int local = boundedInt(reply, 0, 1);
            int programType = boundedInt(reply, 0, 31);
            int playState = boundedInt(reply, 0, 1);
            return new Status(signal, stereo, trafficProgram, trafficAnnouncement,
                    seeking, preview, autoSearch, local, programType, playState);
        });
    }

    String[] readBands() throws RemoteException {
        return read(16, null, reply -> {
            int count = boundedInt(reply, 1, MAX_BANDS);
            String[] bands = new String[count];
            Set<String> unique = new HashSet<>();
            for (int i = 0; i < count; i++) {
                bands[i] = string(reply, 32, false);
                requireBand(bands[i]);
                if (!unique.add(bands[i])) throw problem("Duplicate SPD band");
            }
            return bands;
        });
    }

    /** Reject a torn current-band snapshot instead of fabricating a coherent state. */
    Snapshot readSnapshot() throws RemoteException {
        String[] bands = readBands();
        Frequency current = readFrequency();
        if (!Arrays.asList(bands).contains(current.grid.nativeBand)) {
            throw problem("SPD current band is not advertised");
        }
        Frequency byBand = readBand(current.grid.nativeBand);
        SpdTuningGrid a = current.grid;
        SpdTuningGrid b = byBand.grid;
        if (a.frequency != b.frequency || a.minimum != b.minimum
                || a.maximum != b.maximum || a.step != b.step) {
            throw problem("SPD current frequency changed during probe");
        }
        return new Snapshot(current, readStatus(), bands);
    }

    private <T> T read(int code, Request request, Response<T> response)
            throws RemoteException {
        // The same descriptor exists with incompatible method orders. These are
        // the only read transactions allowed by this exact-contract probe.
        if (code != 5 && code != 6 && code != 13 && code != 16) {
            throw problem("Disallowed SPD probe transaction");
        }
        Parcel input = Parcel.obtain();
        Parcel output = Parcel.obtain();
        try {
            input.writeInterfaceToken(DESCRIPTOR);
            if (request != null) request.write(input);
            if (!binder.transact(code, input, output, 0)) {
                throw problem("SPD endpoint rejected read");
            }
            if (output.dataSize() > MAX_REPLY_BYTES || output.dataAvail() < 4) {
                throw problem("Invalid SPD reply size");
            }
            // This OEM writes a plain writeNoException marker. Reject unknown
            // reply headers before interpreting their bytes as frequency data.
            if (integer(output) != 0) throw problem("SPD read failed");
            T value = response.read(output);
            if (output.dataAvail() != 0) throw problem("Unexpected SPD reply fields");
            return value;
        } catch (RuntimeException error) {
            // Do not leak arbitrary vendor exception text into public diagnostics.
            throw problem("Malformed or denied SPD read");
        } finally {
            input.recycle();
            output.recycle();
        }
    }

    private static Frequency frequency(Parcel reply) throws RemoteException {
        present(reply);
        String nativeBand = string(reply, 32, false);
        requireBand(nativeBand);
        int frequency = integer(reply);
        int minimum = integer(reply);
        int maximum = integer(reply);
        int step = integer(reply);
        int pi = boundedInt(reply, 0, 65535);
        int signal = boundedInt(reply, 0, 65535);
        // PS can genuinely be absent before reception; preserve null as unknown.
        String ps = string(reply, 128, true);
        try {
            SpdTuningGrid grid = SpdTuningGrid.fromSnapshot(nativeBand, frequency,
                    minimum, maximum, step);
            if (grid.scale != 1) throw problem("SPD OEM frequencies must be kHz");
            grid.requireRepresentableCurrentFrequency();
            return new Frequency(grid, ps, pi, signal);
        } catch (IllegalArgumentException error) {
            throw problem("Unsupported SPD frequency grid");
        }
    }

    private static void present(Parcel reply) throws RemoteException {
        if (integer(reply) != 1) throw problem("Missing SPD value");
    }

    private static int integer(Parcel reply) throws RemoteException {
        if (reply.dataAvail() < 4) throw problem("Truncated SPD reply");
        return reply.readInt();
    }

    private static int boundedInt(Parcel reply, int minimum, int maximum)
            throws RemoteException {
        int value = integer(reply);
        if (value < minimum || value > maximum) throw problem("Invalid SPD field");
        return value;
    }

    private static boolean bool(Parcel reply) throws RemoteException {
        return boundedInt(reply, 0, 1) == 1;
    }

    private static String string(Parcel reply, int limit, boolean nullable)
            throws RemoteException {
        int start = reply.dataPosition();
        int length = integer(reply);
        if (length == -1 && nullable) return null;
        if (length < 0 || length > limit) throw problem("Invalid SPD text length");
        long byteCount = (((long) length + 1) * 2 + 3) & ~3L;
        if (byteCount > reply.dataAvail()) throw problem("Truncated SPD text");
        reply.setDataPosition(start);
        String value = reply.readString();
        if (value == null || value.length() != length
                || reply.dataPosition() != start + 4 + byteCount) {
            throw problem("Invalid SPD text encoding");
        }
        return value;
    }

    private static void requireBand(String band) throws RemoteException {
        if (!"FM".equals(band) && !"AM".equals(band)
                && !"FM_FAVORITES".equals(band) && !"AM_FAVORITES".equals(band)) {
            throw problem("Unsupported SPD OEM band");
        }
    }

    private static RemoteException problem(String message) {
        return new RemoteException(message);
    }
}
