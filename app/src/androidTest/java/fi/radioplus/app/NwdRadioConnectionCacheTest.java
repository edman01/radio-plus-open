package fi.radioplus.app;

import android.content.Intent;
import android.os.Binder;
import android.os.Parcel;
import android.os.RemoteException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic Binder endpoints; no vendor APK or hardware code is executed. */
public final class NwdRadioConnectionCacheTest {
    private static NwdAudioRouting audio() {
        return new NwdAudioRouting(new NwdAudioRouting.Transport() {
            @Override public int source() { return 4; }
            @Override public void send(Intent intent) { fail("Already-selected radio must not be reinitialized"); }
        });
    }

    private static final class Endpoint extends Binder {
        final List<Integer> calls = new ArrayList<>();
        boolean alive = true, reject, dieDuringValidation;
        int type = 2, state = 1;

        Endpoint() { attachInterface(null, NwdRadioApi.DESCRIPTOR); }
        @Override public boolean isBinderAlive() { return alive; }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            data.enforceInterface(NwdRadioApi.DESCRIPTOR);
            calls.add(code);
            if (reject) return false;
            reply.writeNoException();
            switch (code) {
                case 29:
                    reply.writeInt(type);
                    if (dieDuringValidation) alive = false;
                    break;
                case 23: reply.writeByte((byte) state); break;
                case 6: state = 2; break;
                case 27:
                    assertEquals(0, data.readByte());
                    assertEquals(0, data.readByte());
                    state = 1;
                    break;
                default: fail("Unexpected transaction " + code);
            }
            assertEquals(0, data.dataAvail());
            return true;
        }
    }

    @Test public void activityAndPlaybackBindingsSharePendingScanAndItsStop() throws Exception {
        NwdRadioConnectionCache cache = new NwdRadioConnectionCache();
        NwdAudioRouting routing = audio();
        Endpoint endpoint = new Endpoint();
        NwdRadioApi activity = cache.resolve(endpoint, routing);
        activity.onASEvent();
        NwdRadioApi playback = cache.resolve(endpoint, routing);
        assertSame(activity, playback);
        assertTrue(playback.IsAS());
        playback.onASEvent();
        assertFalse(activity.IsAS());
        assertEquals(1L, endpoint.calls.stream().filter(code -> code == 29).count());
        assertEquals(1L, endpoint.calls.stream().filter(code -> code == 6).count());
        assertEquals(1L, endpoint.calls.stream().filter(code -> code == 27).count());
    }

    @Test public void differentEndpointOrAudioDependencyCreatesNewState() throws Exception {
        NwdRadioConnectionCache cache = new NwdRadioConnectionCache();
        NwdAudioRouting routing = audio();
        Endpoint first = new Endpoint(), replacement = new Endpoint();
        NwdRadioApi original = cache.resolve(first, routing);
        original.onASEvent();
        NwdRadioApi next = cache.resolve(replacement, routing);
        assertNotSame(original, next);
        assertFalse(next.IsAS());
        assertSame(next, cache.resolve(replacement, routing));
        assertNotSame(next, cache.resolve(replacement, audio()));
    }

    @Test public void deadEndpointCannotReuseCachedState() throws Exception {
        NwdRadioConnectionCache cache = new NwdRadioConnectionCache();
        NwdAudioRouting routing = audio();
        Endpoint endpoint = new Endpoint();
        NwdRadioApi original = cache.resolve(endpoint, routing);
        endpoint.alive = false;
        assertThrows(RemoteException.class, () -> cache.resolve(endpoint, routing));
        endpoint.alive = true;
        assertNotSame(original, cache.resolve(endpoint, routing));
        assertEquals(2L, endpoint.calls.stream().filter(code -> code == 29).count());
    }

    @Test public void rejectedWrongTypeAndMidValidationDeathAreNeverCached() throws Exception {
        for (int scenario = 0; scenario < 3; scenario++) {
            NwdRadioConnectionCache cache = new NwdRadioConnectionCache();
            NwdAudioRouting routing = audio();
            Endpoint endpoint = new Endpoint();
            endpoint.reject = scenario == 0;
            endpoint.type = scenario == 1 ? 3 : 2;
            endpoint.dieDuringValidation = scenario == 2;
            assertThrows(RemoteException.class, () -> cache.resolve(endpoint, routing));
            endpoint.reject = false;
            endpoint.type = 2;
            endpoint.dieDuringValidation = false;
            endpoint.alive = true;
            NwdRadioApi accepted = cache.resolve(endpoint, routing);
            assertSame(accepted, cache.resolve(endpoint, routing));
            assertEquals(2L, endpoint.calls.stream().filter(code -> code == 29).count());
        }
    }

    @Test public void failedReplacementDoesNotReturnPreviouslyValidatedConnection() throws Exception {
        NwdRadioConnectionCache cache = new NwdRadioConnectionCache();
        NwdAudioRouting routing = audio();
        Endpoint originalEndpoint = new Endpoint(), rejected = new Endpoint();
        NwdRadioApi original = cache.resolve(originalEndpoint, routing);
        rejected.reject = true;
        assertThrows(RemoteException.class, () -> cache.resolve(rejected, routing));
        assertNotSame(original, cache.resolve(originalEndpoint, routing));
        assertThrows(RemoteException.class, () -> cache.resolve(null, routing));
    }
}
