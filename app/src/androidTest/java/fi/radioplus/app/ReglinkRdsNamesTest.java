package fi.radioplus.app;

import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public final class ReglinkRdsNamesTest {
    static final class Daemon extends ContextWrapper {
        final List<Integer> queries = new ArrayList<>();
        ServiceConnection connection;
        int binds, unbinds;
        boolean deny;
        Daemon() { super(InstrumentationRegistry.getInstrumentation().getTargetContext()); }
        final Binder cache = new Binder() {
            { attachInterface(null, ReglinkRdsNames.DESCRIPTOR); }
            @Override protected boolean onTransact(int code, Parcel data, Parcel out, int flags) {
                assertEquals("only a per-frequency cache read is allowed", 4, code);
                data.enforceInterface(ReglinkRdsNames.DESCRIPTOR);
                int raw = data.readInt(); assertEquals(0, data.dataAvail()); queries.add(raw);
                out.writeNoException();
                if (raw == 9000) { out.writeInt(0); return true; }
                out.writeInt(1); out.writeInt(raw == 9010 ? 9020 : raw);
                out.writeInt(raw == 9020 ? 32 : 16);
                out.writeInt(0); out.writeInt(0); out.writeInt(0); out.writeInt(0);
                out.writeInt(1); out.writeString(raw == 9030 ? "NULL" : "  FM TEST  ");
                out.writeInt(1); out.writeString("not the station name");
                out.writeInt(1);
                if (raw == 9040) out.writeInt(100);
                else out.writeIntArray(new int[]{9800, 9900});
                return true;
            }
        };
        @Override public boolean bindService(Intent i, ServiceConnection c, int flags) {
            assertEquals(ReglinkRdsNames.COMPONENT, i.getComponent());
            assertEquals("No auto-create/priority flags", 0, flags);
            binds++;
            connection = c;
            if (deny) throw new SecurityException("not exported");
            return true;
        }
        @Override public void unbindService(ServiceConnection c) {
            assertSame(connection, c); unbinds++;
        }
        void connect(IBinder endpoint) { connection.onServiceConnected(ReglinkRdsNames.COMPONENT, endpoint); }
    }

    @Test public void cacheRequiresExactFrequencyValidPsAndACurrentBinding() {
        Daemon d = new Daemon(); ReglinkRdsNames reader = new ReglinkRdsNames(d);
        reader.bindIfRunning(); reader.bindIfRunning(); assertEquals(1, d.binds);
        assertEquals("", reader.nameAt(0, 98100)); // not running
        d.connect(new Binder()); assertEquals("", reader.nameAt(0, 98100)); // wrong descriptor
        d.connect(d.cache);
        assertEquals("", reader.nameAt(3, 999)); assertEquals("", reader.nameAt(0, 98101));
        assertTrue(d.queries.isEmpty());
        assertEquals("FM TEST", reader.nameAt(0, 98100));
        for (int khz : new int[]{90000, 90100, 90200, 90300, 90400})
            assertEquals("absent, stale, RT-only, placeholder or malformed: " + khz, "", reader.nameAt(0, khz));
        assertFalse(reader.isConnected());
        d.connect(d.cache);
        d.connection.onServiceDisconnected(ReglinkRdsNames.COMPONENT);
        assertEquals("", reader.nameAt(0, 98100));
        reader.close(); reader.close(); d.connect(d.cache);
        assertEquals("", reader.nameAt(0, 98100)); assertEquals(1, d.unbinds);
    }

    @Test public void deniedOptionalBindingLeavesNamesEmpty() {
        Daemon d = new Daemon(); d.deny = true;
        try (ReglinkRdsNames reader = new ReglinkRdsNames(d)) {
            reader.bindIfRunning(); assertEquals("", reader.nameAt(0, 98100));
            assertTrue(d.queries.isEmpty());
        }
        assertEquals(1, d.unbinds);
    }
}
