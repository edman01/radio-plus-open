package fi.radioplus.app;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.view.KeyEvent;

import androidx.test.platform.app.InstrumentationRegistry;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import static org.junit.Assert.*;

/** Synthetic integration only: no vendor APK, RF reception or real audio is exercised. */
public final class SpdIntegrationTest {
    private static final Instrumentation INSTRUMENTATION = InstrumentationRegistry.getInstrumentation();

    private static final class Endpoint extends Binder {
        final List<Integer> calls = new CopyOnWriteArrayList<>();
        final List<Integer> commands = new CopyOnWriteArrayList<>();
        final List<Integer> arguments = new CopyOnWriteArrayList<>();
        final List<Runnable> queuedCommands = new CopyOnWriteArrayList<>();
        volatile String owner = SpdRadioApi.RADIO_SOURCE;
        final SpdAudioSourceReader source = new SpdAudioSourceReader(() -> owner);
        volatile boolean alive = true, rejectCommand;
        volatile boolean applyCommands = true;
        volatile Runnable afterCommand, afterStatus;
        volatile String band = "FM";
        volatile int fm = 98_100, am = 900, play = 1;
        int appliedCommands;

        Endpoint() { this(SpdRadioProbe.DESCRIPTOR); }
        Endpoint(String descriptor) { attachInterface(null, descriptor); }
        @Override public boolean isBinderAlive() { return alive; }
        SpdRadioApi api() throws RemoteException { return new SpdRadioApi(this, source, 0L); }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            data.enforceInterface(SpdRadioProbe.DESCRIPTOR);
            assertEquals(0, flags);
            calls.add(code);
            if (code == 20) {
                int command = data.readInt(), value = data.readInt();
                assertEquals(0, data.readInt());
                int present = data.readInt();
                assertTrue(present == 0 || present == 1);
                Bundle extra = present == 0 ? null : Bundle.CREATOR.createFromParcel(data);
                assertEquals(0, data.dataAvail());
                commands.add(command);
                arguments.add(value);
                if (rejectCommand) return false;
                if (command == 0x2000) {
                    assertNotNull(extra);
                    assertEquals(1, extra.size());
                    String requestedBand = extra.getString("string");
                    assertTrue("FM".equals(requestedBand) || "AM".equals(requestedBand));
                    queuedCommands.add(() -> {
                        band = requestedBand;
                        if ("FM".equals(band)) fm = value; else am = value;
                        owner = SpdRadioApi.RADIO_SOURCE;
                    });
                } else {
                    assertEquals(0x2007, command);
                    assertNull(extra);
                    assertTrue(value == 0 || value == 1);
                    queuedCommands.add(() -> {
                        play = value;
                        owner = value == 1 ? SpdRadioApi.RADIO_SOURCE : "com.example.music";
                    });
                }
                if (applyCommands) drainOemQueue();
                if (afterCommand != null) afterCommand.run();
                reply.writeNoException();
                return true;
            }
            String requested = code == 6 ? data.readString() : band;
            assertEquals(0, data.dataAvail());
            reply.writeNoException();
            switch (code) {
                case 5:
                case 6:
                    assertTrue("FM".equals(requested) || "AM".equals(requested));
                    boolean isAm = "AM".equals(requested);
                    reply.writeInt(1);
                    reply.writeString(requested);
                    for (int number : new int[]{isAm ? am : fm, isAm ? 522 : 87_500,
                            isAm ? 1620 : 108_000, isAm ? 9 : 50, 0, 40}) reply.writeInt(number);
                    reply.writeString(isAm ? "Synthetic AM" : "Synthetic FM");
                    break;
                case 13:
                    reply.writeInt(1);
                    for (int number : new int[]{40, 1, 0, 0, 0, 0, 0, 0, 0, play}) reply.writeInt(number);
                    if (afterStatus != null) afterStatus.run();
                    break;
                case 16: reply.writeStringArray(new String[]{"FM", "AM"}); break;
                default: fail("Unexpected transaction, including any settings setter: " + code);
            }
            return true;
        }

        void drainOemQueue() {
            while (appliedCommands < queuedCommands.size()) queuedCommands.get(appliedCommands++).run();
        }
    }

    private static final class ClientFixture implements AutoCloseable {
        final List<RadioServiceClient.RadioState> states = new CopyOnWriteArrayList<>();
        final List<String> errors = new CopyOnWriteArrayList<>();
        final RadioServiceClient client = new RadioServiceClient(INSTRUMENTATION.getTargetContext(),
                new RadioServiceClient.Listener() {
                    @Override public void onConnectionChanged(boolean connected, String message) { }
                    @Override public void onRadioError(String message) { errors.add(message); }
                    @Override public void onStateChanged(RadioServiceClient.RadioState state) { states.add(state); }
                });
        ClientFixture(SpdRadioApi api) { write(RadioServiceClient.class, "service", client, api); }
        void poll() { invoke(client, "pollNow", new Class<?>[0]); INSTRUMENTATION.waitForIdleSync(); }
        void drain() throws Exception { drainExecutor(client, "actionExecutor"); }
        @Override public void close() { INSTRUMENTATION.runOnMainSync(client::close); }
    }

    private static final class PlaybackFixture implements AutoCloseable {
        final Object previousRunning = read(RadioPlaybackService.class, "runningInstance", null);
        final Object previousDetection = read(RadioApiFactory.class, "latest", null);
        RadioPlaybackService service;
        PlaybackFixture(SpdRadioApi api) {
            assertNull("Stop Radio+ before synthetic service instrumentation", previousRunning);
            INSTRUMENTATION.runOnMainSync(() -> {
                service = new RadioPlaybackService();
                write(RadioPlaybackService.class, "runningInstance", null, service);
                write(RadioPlaybackService.class, "radio", service, api);
                write(RadioPlaybackService.class, "playbackRequested", service, true);
                write(RadioApiFactory.class, "latest", null,
                        new RadioApiFactory.Detection(RadioBackendProfile.SPD_V9_34, "14", "", ""));
            });
        }
        @Override public void close() {
            INSTRUMENTATION.runOnMainSync(() -> {
                ((Handler) read(RadioPlaybackService.class, "mainHandler", service)).removeCallbacksAndMessages(null);
                write(RadioPlaybackService.class, "runningInstance", null, previousRunning);
                write(RadioApiFactory.class, "latest", null, previousDetection);
            });
            ((ExecutorService) read(RadioPlaybackService.class, "executor", service)).shutdownNow();
        }
    }

    @Test public void factoryBypassCannotResolveSpdWithoutItsAuthenticatedFramework() {
        Endpoint endpoint = new Endpoint();
        assertThrows(RemoteException.class, () -> RadioApiFactory.create(RadioBackendProfile.SPD_V9_34, endpoint));
        assertTrue(endpoint.calls.isEmpty());
        assertTrue(endpoint.commands.isEmpty());
    }

    @Test public void serviceIntentUsesOnlyTheExplicitInspectedComponent() {
        Intent intent = RadioBackendContract.serviceIntent(RadioBackendProfile.SPD_V9_34);
        assertEquals("com.spd.radio.service", intent.getAction());
        assertEquals(new ComponentName("com.spd.radio", "com.spd.radio.service.RadioService"), intent.getComponent());
    }

    @Test public void secondBindingRetainsFirstBindingsUnconfirmedCommandWithoutReplay() throws Exception {
        Endpoint endpoint = new Endpoint();
        endpoint.rejectCommand = true;
        SpdRadioConnectionCache cache = new SpdRadioConnectionCache();
        SpdRadioApi first = cache.resolve(endpoint, endpoint.source);
        assertTrue(endpoint.calls.isEmpty());
        assertThrows(SpdRadioApi.CommandRejectedException.class, () -> first.tuneToBand(0, 99_500));
        assertTrue(first.hasPendingCommand());
        SpdRadioApi second = cache.resolve(endpoint, endpoint.source);
        assertSame(first, second);
        assertTrue(second.hasPendingCommand());
        assertThrows(SpdRadioApi.CommandRejectedException.class, second::requestPlayAudio);
        assertEquals(1, endpoint.commands.size());
    }

    @Test public void cacheRejectsDeadNullAndWrongDescriptorInsteadOfReturningPreviousApi() throws Exception {
        Endpoint endpoint = new Endpoint();
        SpdRadioConnectionCache cache = new SpdRadioConnectionCache();
        SpdRadioApi original = cache.resolve(endpoint, endpoint.source);
        endpoint.alive = false;
        assertThrows(RemoteException.class, () -> cache.resolve(endpoint, endpoint.source));
        endpoint.alive = true;
        SpdRadioApi fresh = cache.resolve(endpoint, endpoint.source);
        assertNotSame(original, fresh);
        Endpoint wrong = new Endpoint("unrecognized.radio.Interface");
        assertThrows(RemoteException.class, () -> cache.resolve(wrong, endpoint.source));
        assertNotSame(fresh, cache.resolve(endpoint, endpoint.source));
        assertThrows(RemoteException.class, () -> cache.resolve(null, endpoint.source));
        assertTrue(endpoint.calls.isEmpty());
        assertTrue(wrong.calls.isEmpty());
    }

    @Test public void differentBinderOrSourceReaderGetsIndependentCacheState() throws Exception {
        SpdRadioConnectionCache cache = new SpdRadioConnectionCache();
        Endpoint first = new Endpoint(), next = new Endpoint();
        SpdRadioApi initial = cache.resolve(first, first.source);
        SpdRadioApi replacement = cache.resolve(next, first.source);
        assertNotSame(initial, replacement);
        assertSame(replacement, cache.resolve(next, first.source));
        assertNotSame(replacement, cache.resolve(next, next.source));
        assertTrue(first.calls.isEmpty());
        assertTrue(next.calls.isEmpty());
    }

    @Test public void clientPollReadsCoherentAm900AndObservesExternalBandChangeWithoutWriting() throws Exception {
        Endpoint endpoint = new Endpoint(); endpoint.band = "AM";
        try (ClientFixture fixture = new ClientFixture(endpoint.api())) {
            fixture.poll();
            assertEquals(1, fixture.states.size());
            assertEquals(3, fixture.states.get(0).band);
            assertEquals(900, fixture.states.get(0).frequency);
            assertEquals("Synthetic AM", fixture.states.get(0).rdsName);
            assertEquals("Band/frequency come from one typed response", 1L,
                    endpoint.calls.stream().filter(code -> code == 5).count());
            endpoint.band = "FM"; endpoint.fm = 101_700;
            fixture.poll();
            assertEquals(2, fixture.states.size());
            assertEquals(0, fixture.states.get(1).band);
            assertEquals(101_700, fixture.states.get(1).frequency);
            assertTrue(endpoint.commands.isEmpty());
            assertTrue(fixture.errors.isEmpty());
            assertFalse(fixture.client.canUseObservedScanFallback());
        }
    }

    @Test public void unchangedManualTuneGetsFreshStateWhilePeriodicPollsStayDeduplicated() throws Exception {
        Endpoint endpoint = new Endpoint(); endpoint.band = "AM";
        try (ClientFixture fixture = new ClientFixture(endpoint.api())) {
            fixture.poll();
            fixture.client.tuneTo(3, 900); fixture.drain();
            assertEquals(2, fixture.states.size());
            assertTrue(fixture.states.get(0).hasSameContent(fixture.states.get(1)));
            assertNotSame(fixture.states.get(0), fixture.states.get(1));
            fixture.poll(); fixture.poll();
            assertEquals(2, fixture.states.size());
            assertTrue(endpoint.commands.isEmpty());
            assertTrue(fixture.errors.isEmpty());
        }
    }

    @Test public void manualTuneStartupTimeoutQueuesOnePauseAndNeverTunesTheUnconfirmedSource() throws Exception {
        Endpoint endpoint = new Endpoint();
        endpoint.owner = "com.example.music";
        endpoint.play = 0;
        endpoint.applyCommands = false;
        SpdRadioApi api = endpoint.api();
        try (ClientFixture fixture = new ClientFixture(api)) {
            fixture.client.tuneTo(3, 900);
            fixture.drain();
            assertEquals(java.util.Arrays.asList(0x2007, 0x2007), endpoint.commands);
            assertEquals(java.util.Arrays.asList(1, 0), endpoint.arguments);
            assertFalse(api.hasPendingAudioStart());
            assertTrue("Initial paused state cannot acknowledge the still-delayed cleanup", api.hasPendingCommand());
            assertEquals(1, fixture.errors.size());
            assertEquals("FM", endpoint.band);
            assertEquals(98_100, endpoint.fm);
            endpoint.drainOemQueue();
            assertEquals("Late accepted PLAY must not remain active after its cleanup", 0, endpoint.play);
            assertEquals("com.example.music", endpoint.owner);
            fixture.poll();
            assertEquals("Observation must not replay the failed manual command", 2, endpoint.commands.size());
            assertSame(api, read(RadioServiceClient.class, "service", fixture.client));
        }
    }

    @Test public void manualTuneTimeoutDoesNotSendCleanupAgainstANewOrUnknownSource() throws Exception {
        for (String newOwner : new String[]{"com.example.other", null}) {
            Endpoint endpoint = new Endpoint();
            endpoint.owner = "com.example.music";
            endpoint.play = 0;
            endpoint.applyCommands = false;
            endpoint.afterCommand = () -> endpoint.owner = newOwner;
            SpdRadioApi api = endpoint.api();
            try (ClientFixture fixture = new ClientFixture(api)) {
                fixture.client.tuneTo(3, 900);
                fixture.drain();
                assertEquals(java.util.Collections.singletonList(0x2007), endpoint.commands);
                assertEquals(java.util.Collections.singletonList(1), endpoint.arguments);
                assertTrue("OEM queued work remains unresolved, not physically canceled", api.hasPendingAudioStart());
                assertEquals(newOwner, endpoint.owner);
                assertEquals("FM", endpoint.band);
                assertEquals(1, fixture.errors.size());
                assertSame(api, read(RadioServiceClient.class, "service", fixture.client));
            }
        }
    }

    @Test public void manualTunePreflightFailureCannotCancelAnEarlierClientsPendingPlay() throws Exception {
        Endpoint endpoint = new Endpoint();
        endpoint.owner = "com.example.music";
        endpoint.play = 0;
        endpoint.applyCommands = false;
        SpdRadioApi api = endpoint.api();
        assertFalse(api.requestPlayAudio(() -> true, true));
        assertTrue(api.hasPendingAudioStart());

        // The new activation never passes source preflight. Its cleanup must
        // not use a newly readable source to cancel somebody else's old PLAY.
        endpoint.owner = null;
        AtomicInteger reads = new AtomicInteger();
        endpoint.afterStatus = () -> {
            if (reads.incrementAndGet() == 2) endpoint.owner = "com.example.music";
        };
        try (ClientFixture fixture = new ClientFixture(api)) {
            fixture.client.tuneTo(3, 900);
            fixture.drain();
            assertEquals("com.example.music", endpoint.owner);
            assertEquals(java.util.Collections.singletonList(0x2007), endpoint.commands);
            assertEquals(java.util.Collections.singletonList(1), endpoint.arguments);
            assertTrue(api.hasPendingAudioStart());
            assertEquals(1, fixture.errors.size());
            assertEquals("FM", endpoint.band);
        }
    }

    @Test public void confirmedManualClientReadbackCanBeSavedWithoutReplacingExistingStations() throws Exception {
        // This exercises client readback + the production confirmation/store contracts,
        // not the MainActivity dialog, a process restart, or actual RF confirmation.
        Context context = INSTRUMENTATION.getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("radio_plus_station_catalog", Context.MODE_PRIVATE);
        boolean existed = preferences.contains("stations");
        String previous = preferences.getString("stations", null);
        Endpoint endpoint = new Endpoint();
        try (ClientFixture fixture = new ClientFixture(endpoint.api())) {
            assertTrue(preferences.edit().putString("stations", "[]").commit());
            StationStore catalog = new StationStore(context);
            FavoriteStation existing = new FavoriteStation(0, 98_300, "My station", "synthetic-token");
            catalog.save(existing);
            fixture.client.tuneTo(3, 900); fixture.drain();
            assertTrue(fixture.errors.isEmpty());
            assertEquals(1, fixture.states.size());
            RadioServiceClient.RadioState confirmed = fixture.states.get(0);
            assertTrue(ManualTuneConfirmation.isConfirmed(3, 900, confirmed.band, confirmed.frequency,
                    confirmed.seeking || confirmed.scanning || confirmed.autoScanning));
            FavoriteStation saved = new FavoriteStation(confirmed.band, confirmed.frequency, confirmed.rdsName);
            catalog.save(saved); catalog.save(saved);
            StationStore reopened = new StationStore(context);
            assertEquals(2, reopened.load().size());
            assertNotNull(reopened.find(3, 900));
            assertNull(reopened.find(0, 90_000));
            assertEquals(existing.name, reopened.find(0, 98_300).name);
            assertEquals(existing.logo, reopened.find(0, 98_300).logo);
            assertEquals(1, endpoint.commands.size());
        } finally {
            SharedPreferences.Editor editor = preferences.edit();
            if (existed) editor.putString("stations", previous); else editor.remove("stations");
            assertTrue(editor.commit());
            StationWidgetProvider.requestDataRefresh(context);
        }
    }

    @Test public void playbackDoesNotDuplicateNativeRawNextPreviousOrPauseKeys() throws Exception {
        Endpoint endpoint = new Endpoint();
        try (PlaybackFixture fixture = new PlaybackFixture(endpoint.api())) {
            assertTrue(RadioPlaybackService.shouldIgnoreRawMediaKeys());
            INSTRUMENTATION.runOnMainSync(() -> {
                for (int code : new int[]{KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_VOLUME_MUTE}) {
                    long now = SystemClock.uptimeMillis();
                    for (int action : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
                        fixture.service.onStartCommand(new Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(
                                Intent.EXTRA_KEY_EVENT, new KeyEvent(now, now + action, action, code, 0)), 0, 1);
                    }
                }
            });
            drainExecutor(fixture.service, "executor");
            assertEquals(true, read(RadioPlaybackService.class, "playbackRequested", fixture.service));
            assertNull(read(RadioPlaybackService.class, "pendingRadioCommand", fixture.service));
            assertTrue(endpoint.calls.isEmpty());
        }
    }

    @Test public void staleFailureCannotDisconnectNewEpochUsingTheSameCachedSpdApi() throws Exception {
        Endpoint endpoint = new Endpoint(); SpdRadioApi api = endpoint.api();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (PlaybackFixture fixture = new PlaybackFixture(api)) {
            Class<?> commandType = Class.forName("fi.radioplus.app.RadioPlaybackService$RadioCommand");
            Object command = Proxy.newProxyInstance(commandType.getClassLoader(), new Class<?>[]{commandType},
                    (proxy, method, arguments) -> {
                        if ("run".equals(method.getName())) {
                            assertEquals(0L, arguments[1]);
                            entered.countDown();
                            assertTrue(release.await(3, TimeUnit.SECONDS));
                            throw new RemoteException("Synthetic failure from obsolete SPD connection");
                        }
                        return null;
                    });
            INSTRUMENTATION.runOnMainSync(() -> invoke(fixture.service, "executeRadioCommand",
                    new Class<?>[]{String.class, commandType}, "synthetic failure", command));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            INSTRUMENTATION.runOnMainSync(() -> {
                invoke(fixture.service, "handleOemConnectionLoss", new Class<?>[]{String.class}, "synthetic rebind");
                write(RadioPlaybackService.class, "radio", fixture.service, api);
            });
            release.countDown(); drainExecutor(fixture.service, "executor");
            assertSame(api, read(RadioPlaybackService.class, "radio", fixture.service));
            assertEquals(1L, read(RadioPlaybackService.class, "playbackEpoch", fixture.service));
            assertEquals(1, read(RadioPlaybackService.class, "backendGeneration", fixture.service));
            assertTrue(endpoint.calls.isEmpty());
        } finally { release.countDown(); }
    }

    private static void drainExecutor(Object target, String field) throws Exception {
        ((ExecutorService) read(target.getClass(), field, target)).submit(() -> { }).get(3, TimeUnit.SECONDS);
        INSTRUMENTATION.waitForIdleSync();
    }

    private static Object read(Class<?> type, String name, Object target) {
        try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException error) { throw new AssertionError(name, error); }
    }

    private static void write(Class<?> type, String name, Object target, Object value) {
        try { Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
        catch (ReflectiveOperationException error) { throw new AssertionError(name, error); }
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... arguments) {
        try { Method method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(target, arguments); }
        catch (ReflectiveOperationException error) { throw new AssertionError(name, error); }
    }
}
