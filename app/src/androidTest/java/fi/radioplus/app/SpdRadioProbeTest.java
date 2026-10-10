package fi.radioplus.app;

import android.os.Binder;
import android.os.Parcel;
import android.os.RemoteException;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

/** Synthetic contract tests only; no OEM APK, RF tuner, source activation or audio. */
public final class SpdRadioProbeTest {
    private interface Body { void write(Parcel reply); }
    private interface Attempt { void run() throws Exception; }

    private static final class Endpoint extends Binder {
        final List<Integer> calls = new ArrayList<>();
        String[] bands = {"FM", "AM", "FM_FAVORITES", "AM_FAVORITES"};
        String band = "FM";
        int frequency = 98_100;
        int minimum = 87_500, maximum = 108_000, step = 50;
        String ps = "Synthetic";
        int pi = 123, signal = 45;
        int stereo = 1, tp, ta, seeking, preview, autoSearch, local, pty, playState = 1;
        int rejectCode = -1, denyCode = -1, malformedCode = -1;
        boolean appendUnknownField;
        Integer queriedFrequency;
        String queriedBand;
        Body malformed;

        Endpoint() { attachInterface(null, SpdRadioProbe.DESCRIPTOR); }

        @Override protected boolean onTransact(int code, Parcel input, Parcel reply, int flags) {
            assertTrue("Probe may never call setters, callbacks, source or commands",
                    code == 5 || code == 6 || code == 13 || code == 16);
            calls.add(code);
            input.enforceInterface(SpdRadioProbe.DESCRIPTOR);
            assertEquals(0, flags);
            assertNotNull(reply);
            String requested = code == 6 ? input.readString() : null;
            assertEquals(0, input.dataAvail());
            if (code == rejectCode) return false;
            if (code == denyCode) {
                reply.writeException(new SecurityException("private vendor detail"));
                return true;
            }
            reply.writeNoException();
            if (code == malformedCode) {
                if (malformed != null) malformed.write(reply);
                return true;
            }
            switch (code) {
                case 5:
                    writeFrequency(reply, band, frequency, minimum, maximum, step);
                    break;
                case 6:
                    String responseBand = queriedBand == null ? requested : queriedBand;
                    boolean am = requested.startsWith("AM");
                    int responseFrequency = queriedFrequency != null ? queriedFrequency
                            : requested.equals(band) ? frequency : am ? 900 : 98_100;
                    writeFrequency(reply, responseBand, responseFrequency,
                            requested.equals(band) ? minimum : am ? 522 : 87_500,
                            requested.equals(band) ? maximum : am ? 1620 : 108_000,
                            requested.equals(band) ? step : am ? 9 : 50);
                    break;
                case 13:
                    reply.writeInt(1);
                    for (int value : new int[]{signal, stereo, tp, ta, seeking, preview,
                            autoSearch, local, pty, playState}) reply.writeInt(value);
                    break;
                case 16:
                    reply.writeStringArray(bands);
                    break;
                default: fail("Unexpected probe transaction");
            }
            if (appendUnknownField) reply.writeInt(999);
            return true;
        }

        private void writeFrequency(Parcel reply, String name, int freq, int min, int max, int spacing) {
            reply.writeInt(1);
            reply.writeString(name);
            for (int value : new int[]{freq, min, max, spacing, pi, signal}) reply.writeInt(value);
            reply.writeString(ps);
        }
    }

    private static void rejected(Attempt attempt) throws Exception {
        try {
            attempt.run();
            fail("Expected a rejected read-only probe");
        } catch (RemoteException expected) {
            assertFalse(String.valueOf(expected.getMessage()).contains("private vendor detail"));
        }
    }

    @Test public void constructorChecksDescriptorWithoutRadioTransaction() throws Exception {
        Endpoint endpoint = new Endpoint();
        SpdRadioProbe probe = new SpdRadioProbe(endpoint);
        assertSame(endpoint, probe.asBinder());
        assertTrue(endpoint.calls.isEmpty());
        rejected(() -> new SpdRadioProbe(null));
        Binder wrong = new Binder();
        wrong.attachInterface(null, "other.radio.interface");
        rejected(() -> new SpdRadioProbe(wrong));
        assertTrue(endpoint.calls.isEmpty());
    }

    @Test public void exactOemReadMapAndCoherentSnapshotNeverUseSetters() throws Exception {
        Endpoint endpoint = new Endpoint();
        SpdRadioProbe.Snapshot snapshot = new SpdRadioProbe(endpoint).readSnapshot();
        assertEquals(Arrays.asList(16, 5, 6, 13), endpoint.calls);
        assertEquals(98_100, snapshot.frequency.grid.frequency);
        assertEquals(1, snapshot.frequency.grid.scale);
        assertEquals(123, snapshot.frequency.pi);
        assertEquals(45, snapshot.frequency.signal);
        assertEquals("Synthetic", snapshot.frequency.ps);
        assertTrue(snapshot.status.stereo);
        assertEquals(1, snapshot.status.playState);
        assertFalse(snapshot.status.seeking);
        assertFalse(snapshot.status.preview);
        assertFalse(snapshot.status.autoSearch);
        assertEquals(4, snapshot.bands.size());
        try { snapshot.bands.set(0, "AM"); fail("Snapshot bands must be immutable"); }
        catch (UnsupportedOperationException expected) { }
    }

    @Test public void amUsesKilohertzWithoutFmScaling() throws Exception {
        Endpoint endpoint = new Endpoint();
        endpoint.band = "AM";
        endpoint.frequency = 900;
        endpoint.minimum = 522;
        endpoint.maximum = 1620;
        endpoint.step = 9;
        SpdRadioProbe probe = new SpdRadioProbe(endpoint);
        SpdRadioProbe.Frequency state = probe.readFrequency();
        assertEquals(3, state.grid.band);
        assertEquals(1, state.grid.scale);
        assertEquals(900, state.grid.frequency);
        assertEquals(900, probe.readBand("AM").grid.frequency);
        assertEquals(Arrays.asList(5, 6), endpoint.calls);
    }

    @Test public void statusFieldsHaveIndependentPositions() throws Exception {
        Endpoint endpoint = new Endpoint();
        endpoint.stereo = 0;
        endpoint.tp = 1;
        endpoint.ta = 1;
        endpoint.seeking = 1;
        endpoint.preview = 1;
        endpoint.autoSearch = 1;
        endpoint.local = 1;
        endpoint.pty = 31;
        endpoint.playState = 0;
        SpdRadioProbe.Status status = new SpdRadioProbe(endpoint).readStatus();
        assertEquals(Arrays.asList(13), endpoint.calls);
        assertFalse(status.stereo);
        assertTrue(status.trafficProgram);
        assertTrue(status.trafficAnnouncement);
        assertTrue(status.seeking);
        assertTrue(status.preview);
        assertTrue(status.autoSearch);
        assertEquals(1, status.local);
        assertEquals(31, status.programType);
        assertEquals(0, status.playState);
    }

    @Test public void nullableStationTextIsPreservedAsUnknown() throws Exception {
        Endpoint endpoint = new Endpoint();
        endpoint.ps = null;
        assertNull(new SpdRadioProbe(endpoint).readFrequency().ps);
    }

    @Test public void rejectedAndDeniedTransactionsCannotBecomeEmptySuccess() throws Exception {
        Endpoint endpoint = new Endpoint();
        SpdRadioProbe probe = new SpdRadioProbe(endpoint);
        endpoint.rejectCode = 5;
        rejected(probe::readFrequency);
        endpoint.denyCode = 13;
        rejected(probe::readStatus);
        assertEquals(Arrays.asList(5, 13), endpoint.calls);
    }

    @Test public void nullAndTruncatedTypedObjectsAreRejected() throws Exception {
        for (int code : new int[]{5, 6, 13}) {
            Endpoint endpoint = new Endpoint();
            endpoint.malformedCode = code;
            SpdRadioProbe probe = new SpdRadioProbe(endpoint);
            Attempt read = code == 5 ? probe::readFrequency
                    : code == 6 ? () -> probe.readBand("FM") : probe::readStatus;
            endpoint.malformed = parcel -> parcel.writeInt(0);
            rejected(read);
            endpoint.malformed = parcel -> parcel.writeInt(1);
            rejected(read);
            endpoint.malformed = null;
            rejected(read);
        }
    }

    @Test public void unsupportedBandQueriesAreRejectedBeforeIpc() throws Exception {
        Endpoint endpoint = new Endpoint();
        SpdRadioProbe probe = new SpdRadioProbe(endpoint);
        for (String name : new String[]{null, "", "FM1", "AM1", "OIRT", " fm "}) {
            rejected(() -> probe.readBand(name));
        }
        assertTrue(endpoint.calls.isEmpty());
    }

    @Test public void inconsistentBandAndCurrentFrequencySnapshotsAreRejected() throws Exception {
        Endpoint endpoint = new Endpoint();
        SpdRadioProbe probe = new SpdRadioProbe(endpoint);
        endpoint.queriedBand = "AM";
        rejected(() -> probe.readBand("FM"));
        endpoint.queriedBand = null;
        endpoint.queriedFrequency = 98_200;
        rejected(probe::readSnapshot);
        endpoint.queriedFrequency = null;
        endpoint.bands = new String[]{"AM"};
        rejected(probe::readSnapshot);
    }

    @Test public void missingDuplicateUnknownAndOversizedBandListsAreRejected() throws Exception {
        Endpoint endpoint = new Endpoint();
        SpdRadioProbe probe = new SpdRadioProbe(endpoint);
        for (String[] value : new String[][]{null, {}, {"FM", "FM"}, {"OIRT"}, {null},
                {"FM", "AM", "FM_FAVORITES", "AM_FAVORITES", "FM"}}) {
            endpoint.bands = value;
            rejected(probe::readBands);
        }
    }

    @Test public void unknownReplyFieldsAreNotSilentlyIgnored() throws Exception {
        Endpoint endpoint = new Endpoint();
        endpoint.appendUnknownField = true;
        SpdRadioProbe probe = new SpdRadioProbe(endpoint);
        rejected(probe::readFrequency);
        rejected(probe::readStatus);
        rejected(probe::readBands);
    }

    @Test public void malformedFieldValuesAndStringLengthsAreRejected() throws Exception {
        Endpoint endpoint = new Endpoint();
        SpdRadioProbe probe = new SpdRadioProbe(endpoint);
        endpoint.stereo = 2;
        rejected(probe::readStatus);
        endpoint.stereo = 1;
        endpoint.playState = 9;
        rejected(probe::readStatus);
        endpoint.pi = -1;
        rejected(probe::readFrequency);
        endpoint.pi = 0;
        endpoint.ps = new String(new char[129]);
        rejected(probe::readFrequency);
        endpoint.malformedCode = 5;
        endpoint.malformed = parcel -> { parcel.writeInt(1); parcel.writeInt(Integer.MAX_VALUE); };
        rejected(probe::readFrequency);
        endpoint.malformed = parcel -> { parcel.writeInt(1); parcel.writeInt(12); };
        rejected(probe::readFrequency);
        endpoint.malformed = parcel -> parcel.writeByteArray(new byte[9000]);
        rejected(probe::readFrequency);
    }

    @Test public void differentUnitsAndUnrepresentableCurrentTargetsAreRejected() throws Exception {
        Endpoint endpoint = new Endpoint();
        SpdRadioProbe probe = new SpdRadioProbe(endpoint);
        endpoint.frequency = 9810;
        endpoint.minimum = 8750;
        endpoint.maximum = 10800;
        endpoint.step = 10;
        rejected(probe::readFrequency); // Same plausible RF grid, wrong OEM wire units.
        endpoint.frequency = 98_150;
        endpoint.minimum = 87_500;
        endpoint.maximum = 108_000;
        endpoint.step = 50;
        rejected(probe::readFrequency); // OEM50kHz target cannot be represented by Radio+100kHz.
        endpoint.frequency = 76_100;
        endpoint.minimum = 76_100;
        endpoint.maximum = 80_000;
        endpoint.step = 100;
        rejected(probe::readFrequency); // No shared app grid.
        endpoint.frequency = 87_550;
        endpoint.minimum = 87_550;
        endpoint.maximum = 107_950;
        endpoint.step = 100;
        rejected(probe::readFrequency); // Overlapping ranges, but disjoint frequency lattice.
    }
}
