package fi.radioplus.app;

import android.app.Instrumentation;
import android.os.Binder;
import android.os.Handler;
import android.os.Parcel;
import android.os.RemoteException;

import androidx.test.platform.app.InstrumentationRegistry;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import static org.junit.Assert.*;

/** Synthetic delayed OEM queue only; no original services or physical audio are used. */
public final class SpdAudioFailureTest {
    private static final Instrumentation INSTRUMENTATION = InstrumentationRegistry.getInstrumentation();
    private static final String MUSIC = "com.example.music";

    private static final class Endpoint extends Binder {
        final List<Integer> commands = new CopyOnWriteArrayList<>();
        volatile String source = MUSIC;
        volatile int playState;
        int applied;

        Endpoint() { attachInterface(null, SpdRadioProbe.DESCRIPTOR); }

        SpdRadioApi api() throws RemoteException {
            return new SpdRadioApi(this, new SpdAudioSourceReader(() -> source), 0L);
        }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            data.enforceInterface(SpdRadioProbe.DESCRIPTOR);
            assertEquals(0, flags);
            if (code == 20) {
                assertEquals("Only radio-specific play/pause, never a source setter", 0x2007, data.readInt());
                int value = data.readInt();
                assertTrue(value == 0 || value == 1);
                assertEquals(0, data.readInt());
                assertEquals(0, data.readInt());
                assertEquals(0, data.dataAvail());
                commands.add(value);
                // A valid Binder receipt does not mean the OEM handler ran yet.
                reply.writeNoException();
                return true;
            }
            String band = code == 6 ? data.readString() : "FM";
            assertEquals(0, data.dataAvail());
            reply.writeNoException();
            switch (code) {
                case 5:
                case 6:
                    assertEquals("FM", band);
                    reply.writeInt(1);
                    reply.writeString(band);
                    for (int value : new int[]{98_100, 87_500, 108_000, 50, 0, 40}) reply.writeInt(value);
                    reply.writeString("Synthetic");
                    break;
                case 13:
                    reply.writeInt(1);
                    for (int value : new int[]{40, 1, 0, 0, 0, 0, 0, 0, 0, playState}) reply.writeInt(value);
                    break;
                case 16: reply.writeStringArray(new String[]{"FM", "AM"}); break;
                default: fail("Unexpected transaction: " + code);
            }
            return true;
        }

        void drainOemQueue() {
            while (applied < commands.size()) {
                playState = commands.get(applied++);
                source = playState == 1 ? SpdRadioApi.RADIO_SOURCE : MUSIC;
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        RadioPlaybackService service;

        Fixture(SpdRadioApi api) {
            assertNull("Stop Radio+ before isolated service instrumentation",
                    read(RadioPlaybackService.class, "runningInstance", null));
            INSTRUMENTATION.runOnMainSync(() -> {
                service = new RadioPlaybackService();
                write("radio", service, api);
                write("playbackRequested", service, true);
            });
        }

        void drain() throws Exception {
            ((ExecutorService) read(RadioPlaybackService.class, "executor", service))
                    .submit(() -> { }).get(3, TimeUnit.SECONDS);
            INSTRUMENTATION.waitForIdleSync();
        }

        @Override public void close() {
            INSTRUMENTATION.runOnMainSync(() ->
                    ((Handler) read(RadioPlaybackService.class, "mainHandler", service))
                            .removeCallbacksAndMessages(null));
            ((ExecutorService) read(RadioPlaybackService.class, "executor", service)).shutdownNow();
        }
    }

    @Test public void acceptedPlayTimeoutQueuesOnePauseBeforeLateOemPlaybackCanRemainActive() throws Exception {
        Endpoint endpoint = new Endpoint();
        SpdRadioApi api = endpoint.api();
        AtomicBoolean requestRan = new AtomicBoolean();
        AtomicBoolean confirmed = new AtomicBoolean(true);
        try (Fixture fixture = new Fixture(api)) {
            Class<?> commandType = Class.forName("fi.radioplus.app.RadioPlaybackService$RadioCommand");
            Object command = Proxy.newProxyInstance(commandType.getClassLoader(), new Class<?>[]{commandType},
                    (proxy, method, arguments) -> {
                        if ("run".equals(method.getName())) {
                            requestRan.set(true);
                            confirmed.set(api.requestPlayAudio(() -> true, true));
                            throw new SpdRadioApi.CommandRejectedException("Synthetic accepted startup timeout");
                        }
                        return null;
                    });
            INSTRUMENTATION.runOnMainSync(() -> invoke(fixture.service, "executeRadioCommand",
                    new Class<?>[]{String.class, commandType}, "synthetic SPD timeout", command));
            fixture.drain();

            assertTrue(requestRan.get());
            assertFalse(confirmed.get());
            assertEquals(java.util.Arrays.asList(1, 0), endpoint.commands);
            assertFalse(api.hasPendingAudioStart());
            assertTrue("Unchanged initial paused state must not acknowledge the queued cleanup",
                    api.hasPendingCommand());
            assertEquals(false, read(RadioPlaybackService.class, "playbackRequested", fixture.service));
            assertSame(api, read(RadioPlaybackService.class, "radio", fixture.service));
            assertEquals(0, read(RadioPlaybackService.class, "backendGeneration", fixture.service));

            endpoint.drainOemQueue();
            assertEquals("Late PLAY is followed by the one queued source-specific PAUSE", 0, endpoint.playState);
            assertEquals(MUSIC, endpoint.source);
            invoke(fixture.service, "settleRejectedSpdCommand",
                    new Class<?>[]{SpdRadioApi.class, long.class, long.class}, api, 0L, 0L);
            fixture.drain();
            assertEquals("Stale settlement must not issue another command", 2, endpoint.commands.size());
        }
    }

    @Test public void oldEpochOrRequestRevisionCannotCancelANewerPendingPlay() throws Exception {
        for (boolean changedEpoch : new boolean[]{true, false}) {
            Endpoint endpoint = new Endpoint();
            SpdRadioApi api = endpoint.api();
            assertFalse(api.requestPlayAudio(() -> true, true));
            try (Fixture fixture = new Fixture(api)) {
                INSTRUMENTATION.runOnMainSync(() ->
                        write(changedEpoch ? "playbackEpoch" : "playbackRequestRevision", fixture.service, 1L));
                invoke(fixture.service, "settleRejectedSpdCommand",
                        new Class<?>[]{SpdRadioApi.class, long.class, long.class}, api, 0L, 0L);
                fixture.drain();
                assertEquals(java.util.Collections.singletonList(1), endpoint.commands);
                assertTrue(api.hasPendingAudioStart());
                assertEquals(true, read(RadioPlaybackService.class, "playbackRequested", fixture.service));
                assertEquals(MUSIC, endpoint.source);
            }
        }
    }

    @Test public void timeoutCleanupCannotPauseADifferentOrUnknownCurrentSource() throws Exception {
        for (String changedSource : new String[]{"com.example.other", null}) {
            Endpoint endpoint = new Endpoint();
            SpdRadioApi api = endpoint.api();
            assertFalse(api.requestPlayAudio(() -> true, true));
            endpoint.source = changedSource;
            try (Fixture fixture = new Fixture(api)) {
                invoke(fixture.service, "settleRejectedSpdCommand",
                        new Class<?>[]{SpdRadioApi.class, long.class, long.class}, api, 0L, 0L);
                fixture.drain();
                assertEquals(java.util.Collections.singletonList(1), endpoint.commands);
                assertTrue("A rejected cleanup is not a completed cancellation", api.hasPendingAudioStart());
                assertEquals(changedSource, endpoint.source);
                assertSame(api, read(RadioPlaybackService.class, "radio", fixture.service));
            }
        }
    }

    @Test public void implicitReconnectCannotAcquireAnExternalOrUnknownSourceOrResumePausedRadio() throws Exception {
        for (String owner : new String[]{MUSIC, null, SpdRadioApi.RADIO_SOURCE}) {
            Endpoint endpoint = new Endpoint();
            endpoint.source = owner;
            SpdRadioApi api = endpoint.api();
            try (Fixture fixture = new Fixture(api)) {
                Method activate = RadioPlaybackService.class.getDeclaredMethod("activateOemPlayback",
                        com.hcn.autoradio.IRadioServiceAPI.class, boolean.class, long.class);
                activate.setAccessible(true);
                try {
                    // Reconnection is not a fresh user Play/Take-over action.
                    activate.invoke(fixture.service, api, false, 0L);
                } catch (InvocationTargetException rejected) {
                    assertTrue("Passive recovery may reject, but not acquire or resume audio",
                            rejected.getCause() instanceof SpdRadioApi.CommandRejectedException);
                }
                assertTrue(endpoint.commands.isEmpty());
                assertEquals(owner, endpoint.source);
                assertEquals(0, endpoint.playState);
            }
        }
    }

    private static Object read(Class<?> type, String name, Object target) {
        try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException error) { throw new AssertionError(name, error); }
    }

    private static void write(String name, Object target, Object value) {
        try { Field field = RadioPlaybackService.class.getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
        catch (ReflectiveOperationException error) { throw new AssertionError(name, error); }
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... arguments) {
        try { Method method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(target, arguments); }
        catch (ReflectiveOperationException error) { throw new AssertionError(name, error); }
    }
}
