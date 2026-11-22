package fi.radioplus.app;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Simulated OEM dispatch only; not a test of the CAN decoder in a vehicle. */
public final class ReglinkWheelInteropTest {
    static final class Notices extends Binder {
        IBinder observer;
        int delivered;
        final Binder client = new Binder() {
            { attachInterface(null, ReglinkWheelKeys.MESSAGE_CLIENT); }
            @Override protected boolean onTransact(int c, Parcel d, Parcel r, int f) {
                d.enforceInterface(ReglinkWheelKeys.MESSAGE_CLIENT);
                assertEquals("Only subscribe to one exact topic", 3, c);
                assertArrayEquals(new int[]{430}, d.createIntArray()); r.writeNoException(); return true;
            }
        };
        Notices() { attachInterface(null, ReglinkWheelKeys.MESSAGE); }
        @Override protected boolean onTransact(int c, Parcel d, Parcel r, int f) {
            d.enforceInterface(ReglinkWheelKeys.MESSAGE); r.writeNoException();
            if (c == 3) { observer = d.readStrongBinder(); r.writeStrongBinder(client); }
            else { assertEquals(4, c); assertSame(observer, d.readStrongBinder()); observer = null; }
            return true;
        }
        void report(int topic, int code) throws RemoteException {
            if (observer == null) return;
            ReglinkRadioApi.transact(observer, ReglinkWheelKeys.MESSAGE_CALLBACK, 1, p -> {
                p.writeInt(1); p.writeInt(topic); p.writeInt(code); p.writeString(null);
                p.writeBundle(null); p.writeInt(0); p.writeLong(0);
            }, null);
            delivered++;
        }
    }
    static final class Keys extends Binder {
        IBinder callback;
        boolean enabled, rejectEnable;
        int nativeSeeks, removals;
        private final Binder client = new Binder() {
            { attachInterface(null, ReglinkWheelKeys.CLIENT); }
            @Override protected boolean onTransact(int c, Parcel d, Parcel r, int flags) {
                d.enforceInterface(ReglinkWheelKeys.CLIENT);
                assertEquals("Must never request global focus", 4, c);
                if (rejectEnable) return false;
                assertEquals(1, d.readInt()); enabled = true;
                r.writeNoException(); return true;
            }
        };
        Keys() { attachInterface(null, ReglinkWheelKeys.KEY); }
        @Override protected boolean onTransact(int c, Parcel d, Parcel r, int flags) {
            d.enforceInterface(ReglinkWheelKeys.KEY); r.writeNoException();
            switch (c) {
                case 5: assertNull(callback); callback = d.readStrongBinder(); r.writeStrongBinder(client); break;
                case 6: assertSame(callback, d.readStrongBinder()); callback = null; enabled = false; removals++; break;
                default: throw new AssertionError("Unexpected global key operation: " + c);
            }
            return true;
        }
        private static void wire(Parcel p, int code, int action, int from, int hold, int count, long stamp) {
            p.writeInt(1); p.writeInt(code); p.writeByte((byte)action); p.writeLong(stamp); p.writeInt(count);
            p.writeByte((byte)from); p.writeInt(hold); p.writeInt(0); p.writeString(null);
        }
        boolean press(int code, long stamp) throws RemoteException { return arrow(code, 3, 1, 0, 0, stamp); }
        boolean arrow(int code, int action, int from, int hold, int count, long stamp) throws RemoteException {
            if (callback == null || !enabled) return false;
            return ReglinkRadioApi.transact(callback, ReglinkWheelKeys.CALLBACK, 1,
                    p -> wire(p, code, action, from, hold, count, stamp), p -> p.readInt() == 1);
        }
        void dispatch(boolean stockFirst, int code, long stamp) throws RemoteException {
            if (stockFirst || !press(code, stamp)) nativeSeeks++;
        }
    }

    @Test public void unclaimedWheelArrowsOnlyAndPassiveObservationCannotTune() throws Exception {
        ReglinkRadioInteropTest.Common c = new ReglinkRadioInteropTest.Common(new Binder());
        List<Boolean> skips = new ArrayList<>();
        AtomicBoolean visible = new AtomicBoolean(true);
        long ts = System.currentTimeMillis();
        try (ReglinkWheelKeys keys = new ReglinkWheelKeys(c, android.os.Process.myUid(), visible::get, skips::add)) {
            assertFalse(c.keys.press(65, ts)); // before opt-in
            keys.open();
            keys.startObservation();
            c.keys.dispatch(true, 66, ++ts); // stock sought before the app
            c.notices.report(430, 66); c.notices.report(430, 66); c.notices.report(430, 1);
            assertEquals(3, c.notices.delivered);
            assertEquals(1, c.keys.nativeSeeks); assertTrue(skips.isEmpty());
            keys.stopObservation(); assertNull(c.notices.observer); // actual listener stays active
            c.keys.dispatch(false, 66, ++ts);
            assertTrue(c.keys.press(66, ts)); // replay, consumed but not queued again
            c.keys.dispatch(false, 65, ++ts);
            assertEquals(Arrays.asList(true, false), skips); assertEquals(1, c.keys.nativeSeeks);
            for (int[] v : new int[][]{{66,1,1,0,0},{66,5,1,600,0},{66,3,1,600,0},
                    {66,3,2,0,0},{66,3,1,0,1},{34,3,1,0,0},{60,3,1,0,0},{65,3,4,0,0}}) {
                assertFalse(c.keys.arrow(v[0],v[1],v[2],v[3],v[4], ++ts));
            }
            for (String state : new String[]{"incoming","talking","outgoing","unknown"}) {
                c.shared.hfp = state; assertFalse(c.keys.press(66, ++ts));
            }
            c.shared.hfp = null; c.shared.bt = true;
            assertFalse(c.keys.press(66, ++ts));
            c.shared.hfp = "disconnected"; c.shared.reverse = true;
            assertFalse(c.keys.press(66, ++ts)); c.shared.reverse = false;
            c.hub.owner = "bluetooth"; assertFalse(c.keys.press(66, ++ts)); c.hub.owner = "radio";
            c.shared.rejectReads = true; assertFalse(c.keys.press(65, ++ts)); c.shared.rejectReads = false;
            visible.set(false); assertFalse(c.keys.press(65, ++ts));
            assertEquals(Arrays.asList(true, false), skips);
        }
        assertNull(c.keys.callback); assertEquals(1, c.keys.removals);
    }

    @Test public void partialRegistrationWrongSenderAndMalformedPayloadDoNotSkip() throws Exception {
        ReglinkRadioInteropTest.Common c = new ReglinkRadioInteropTest.Common(new Binder());
        try (ReglinkWheelKeys keys = new ReglinkWheelKeys(c, android.os.Process.myUid(), () -> true,
                next -> { throw new AssertionError("Unexpected skip"); })) {
            c.keys.rejectEnable = true;
            try { keys.open(); fail("should reject"); } catch (RemoteException expected) { }
            assertNull(c.keys.callback); assertEquals(1, c.keys.removals);
            c.keys.rejectEnable = false; keys.open();
            assertFalse(ReglinkRadioApi.transact(c.keys.callback, ReglinkWheelKeys.CALLBACK, 1,
                    p -> { p.writeInt(1); p.writeInt(66); }, p -> p.readInt() == 1));
        }
        try (ReglinkWheelKeys keys = new ReglinkWheelKeys(c, -1, () -> true,
                next -> { throw new AssertionError("Wrong caller"); })) {
            keys.open(); assertFalse(c.keys.press(65, System.currentTimeMillis()));
        }
    }
}
