package fi.radioplus.app;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** Synthetic read-only endpoints. No OEM component is started by these tests. */
public final class ReglinkCapabilityInteropTest {
    private static final class Shared extends Binder {
        final List<Integer> calls = new ArrayList<>();
        String module = "4754";
        boolean initialized = true;
        boolean onboard;
        boolean missingInitialized;
        boolean missingOnboard;
        boolean changingModule;
        boolean changingInitialized;
        boolean changingOnboard;
        int moduleReads;
        int initializedReads;
        int onboardReads;
        int rejectCode = -1;
        int trailingCode = -1;
        int truncatedCode = -1;
        boolean invalidBoolean;

        Shared() { attachInterface(null, ReglinkCapabilityReader.SHARED_DESCRIPTOR); }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            data.enforceInterface(ReglinkCapabilityReader.SHARED_DESCRIPTOR);
            calls.add(code);
            assertEquals(0, flags);
            assertTrue("Only read-only SharedVariable RPCs allowed", code == 7 || code == 9);
            String key = data.readString();
            String stringValue = null;
            int booleanValue = 0;
            if (code == 7) {
                assertEquals("Env.RadioHwModule", key);
                assertNull(data.readString());
                moduleReads++;
                stringValue = changingModule && moduleReads % 2 == 0 ? "mtk_radio" : module;
            } else {
                int defaultValue = data.readInt();
                assertTrue(defaultValue == 0 || defaultValue == 1);
                if ("Env.Init_AllCompleted".equals(key)) {
                    initializedReads++;
                    booleanValue = missingInitialized ? defaultValue : initialized ? 1 : 0;
                    if (changingInitialized && initializedReads % 2 == 0) booleanValue ^= 1;
                } else {
                    assertEquals("Env.OnboardRadio", key);
                    onboardReads++;
                    booleanValue = missingOnboard ? defaultValue : onboard ? 1 : 0;
                    if (changingOnboard && onboardReads % 2 == 0) booleanValue ^= 1;
                }
            }
            assertEquals(0, data.dataAvail());
            if (rejectCode == code) return false;
            reply.writeNoException();
            if (truncatedCode == code) return true;
            if (code == 7) reply.writeString(stringValue);
            else reply.writeInt(invalidBoolean ? 2 : booleanValue);
            if (trailingCode == code) reply.writeInt(42);
            return true;
        }
    }

    private static final class Common extends Binder {
        final IBinder shared;
        final List<Integer> calls = new ArrayList<>();
        boolean trailing;
        boolean truncated;
        boolean reject;

        Common(IBinder shared) {
            this.shared = shared;
            attachInterface(null, ReglinkCapabilityReader.COMMON_DESCRIPTOR);
        }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            data.enforceInterface(ReglinkCapabilityReader.COMMON_DESCRIPTOR);
            calls.add(code);
            assertEquals(2, code);
            assertEquals(0, flags);
            assertEquals("SharedVariable", data.readString());
            assertEquals(0, data.dataAvail());
            if (reject) return false;
            reply.writeNoException();
            if (!truncated) reply.writeStrongBinder(shared);
            if (trailing) reply.writeInt(42);
            return true;
        }
    }

    private static ReglinkCapabilityReader.Snapshot read(Shared shared) throws RemoteException {
        return ReglinkCapabilityReader.read(new Common(shared));
    }

    private static void assertUnsupported(ReglinkCapabilityReader.Snapshot snapshot) {
        assertEquals(ReglinkCapabilityReader.Kind.UNKNOWN, snapshot.kind);
        for (int band = -1; band <= 5; band++) assertFalse(snapshot.supportsBand(band));
    }

    @Test public void externalModulesReadOnlyAndSupportFmAm() throws Exception {
        for (String module : Arrays.asList("4754", "4755", "7786")) {
            Shared shared = new Shared(); shared.module = module;
            Common common = new Common(shared);
            ReglinkCapabilityReader.Snapshot snapshot = ReglinkCapabilityReader.read(common);
            assertEquals(module, snapshot.module);
            assertTrue(snapshot.initialized);
            assertFalse(snapshot.onboardRadio);
            assertTrue(snapshot.stable);
            assertEquals(ReglinkCapabilityReader.Kind.EXTERNAL_FM_AM, snapshot.kind);
            for (int band = 0; band <= 3; band++) assertTrue(snapshot.supportsBand(band));
            assertFalse(snapshot.supportsBand(-1)); assertFalse(snapshot.supportsBand(4));
            assertEquals(Arrays.asList(2), common.calls);
            assertEquals(Arrays.asList(9, 7, 9, 9, 7, 9), shared.calls);
        }
    }

    @Test public void nativeMtkOnlySupportsFm() throws Exception {
        Shared shared = new Shared(); shared.module = "mtk_radio"; shared.onboard = true;
        ReglinkCapabilityReader.Snapshot snapshot = read(shared);
        assertEquals(ReglinkCapabilityReader.Kind.NATIVE_FM, snapshot.kind);
        assertTrue(snapshot.supportsBand(0)); assertTrue(snapshot.supportsBand(2));
        assertFalse(snapshot.supportsBand(3));
    }

    @Test public void unknownAndUninspectedModulesRemainUnsupported() throws Exception {
        for (String module : Arrays.asList(null, "", "sprd_radio", "none", "4754 ", "MTK_RADIO")) {
            Shared shared = new Shared(); shared.module = module;
            assertUnsupported(read(shared));
        }
    }

    @Test public void initializedFlagIsRequired() throws Exception {
        Shared shared = new Shared(); shared.initialized = false;
        ReglinkCapabilityReader.Snapshot snapshot = read(shared);
        assertTrue(snapshot.stable); assertFalse(snapshot.initialized);
        assertUnsupported(snapshot);
    }

    @Test public void moduleAndOnboardMustAgree() throws Exception {
        Shared shared = new Shared(); shared.onboard = true;
        assertUnsupported(read(shared));
        shared.module = "mtk_radio"; shared.onboard = false;
        assertUnsupported(read(shared));
    }

    @Test public void missingBooleanFieldsDoNotBecomeKnownFalse() throws Exception {
        Shared shared = new Shared(); shared.missingOnboard = true;
        ReglinkCapabilityReader.Snapshot snapshot = read(shared);
        assertFalse(snapshot.stable); assertUnsupported(snapshot);
        assertEquals(18, shared.calls.size());
        shared = new Shared(); shared.missingInitialized = true;
        snapshot = read(shared);
        assertFalse(snapshot.stable); assertUnsupported(snapshot);
    }

    @Test public void changingStateHasBoundedRetriesAndRemainsUnsupported() throws Exception {
        for (int field = 0; field < 3; field++) {
            Shared shared = new Shared();
            shared.changingModule = field == 0;
            shared.changingInitialized = field == 1;
            shared.changingOnboard = field == 2;
            ReglinkCapabilityReader.Snapshot snapshot = read(shared);
            assertFalse(snapshot.stable); assertUnsupported(snapshot);
            assertEquals(18, shared.calls.size());
        }
    }

    @Test public void endpointDescriptorsAreVerifiedBeforeQueries() throws Exception {
        Binder wrong = new Binder(); wrong.attachInterface(null, "unrelated");
        assertThrows(RemoteException.class, () -> ReglinkCapabilityReader.read(null));
        assertThrows(RemoteException.class, () -> ReglinkCapabilityReader.read(wrong));
        assertThrows(RemoteException.class, () -> ReglinkCapabilityReader.read(new Common(null)));
        assertThrows(RemoteException.class, () -> ReglinkCapabilityReader.read(new Common(wrong)));
    }

    @Test public void rejectedAndMalformedCommonRepliesFailClosed() throws Exception {
        Shared shared = new Shared();
        Common rejected = new Common(shared); rejected.reject = true;
        Common truncated = new Common(shared); truncated.truncated = true;
        Common trailing = new Common(shared); trailing.trailing = true;
        assertThrows(RemoteException.class, () -> ReglinkCapabilityReader.read(rejected));
        assertThrows(RemoteException.class, () -> ReglinkCapabilityReader.read(truncated));
        assertThrows(RemoteException.class, () -> ReglinkCapabilityReader.read(trailing));
        assertTrue(shared.calls.isEmpty());
    }

    @Test public void rejectedAndMalformedSharedRepliesFailClosed() {
        for (int code : Arrays.asList(7, 9)) {
            Shared rejected = new Shared(); rejected.rejectCode = code;
            Shared truncated = new Shared(); truncated.truncatedCode = code;
            Shared trailing = new Shared(); trailing.trailingCode = code;
            assertThrows(RemoteException.class, () -> read(rejected));
            assertThrows(RemoteException.class, () -> read(truncated));
            assertThrows(RemoteException.class, () -> read(trailing));
        }
        Shared invalid = new Shared(); invalid.invalidBoolean = true;
        assertThrows(RemoteException.class, () -> read(invalid));
    }
}
