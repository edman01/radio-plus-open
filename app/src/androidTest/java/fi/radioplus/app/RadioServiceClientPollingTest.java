package fi.radioplus.app;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hcn.autoradio.IRadioServiceAPI;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic Binder scheduling only; no vendor application is installed or run. */
public final class RadioServiceClientPollingTest {
    private static final class Endpoint extends Binder {
        final CountDownLatch mutationEntered = new CountDownLatch(1);
        final CountDownLatch releaseMutation = new CountDownLatch(1);
        final CountDownLatch pollReady = new CountDownLatch(1);
        final AtomicInteger frequencyReads = new AtomicInteger();
        final AtomicInteger presetReadRequests = new AtomicInteger();
        volatile CountDownLatch frequencyEntered, releaseFrequency;
        volatile CountDownLatch presetStopEntered, releasePresetStop;
        volatile boolean rejectPresetRead;

        Endpoint() { attachInterface(null, NwdRadioApi.DESCRIPTOR); }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            data.enforceInterface(NwdRadioApi.DESCRIPTOR);
            reply.writeNoException();
            switch (code) {
                case 29: reply.writeInt(2); break;
                case 2:
                    int reads = frequencyReads.incrementAndGet();
                    if (frequencyEntered != null && reads == 1) {
                        frequencyEntered.countDown();
                        await(releaseFrequency);
                    }
                    reply.writeInt(1); reply.writeByte((byte) 0);
                    reply.writeString("Synthetic station"); reply.writeInt(9810);
                    if (reads == 2) pollReady.countDown();
                    break;
                case 8:
                    assertEquals(1, data.readInt());
                    mutationEntered.countDown();
                    await(releaseMutation);
                    break;
                case 9: reply.writeInt(0); break;
                case 10: reply.writeInt(1); break;
                case 23: reply.writeByte((byte) 1); break;
                case 27:
                    presetReadRequests.incrementAndGet();
                    if (rejectPresetRead) return false;
                    assertEquals(0, data.readByte()); assertEquals(0, data.readByte());
                    if (presetStopEntered != null) {
                        presetStopEntered.countDown(); await(releasePresetStop);
                    }
                    break;
                case 28: reply.writeString(""); break;
                default: fail("Unexpected NWD transaction " + code);
            }
            assertEquals(0, data.dataAvail());
            return true;
        }

        private static void await(CountDownLatch latch) {
            try { assertTrue("Test must release the synthetic Binder call", latch.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
        }
    }

    private static final class Fixture {
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        final List<RadioServiceClient.RadioState> states = new CopyOnWriteArrayList<>();
        final List<String> errors = new CopyOnWriteArrayList<>();
        final RadioServiceClient client = new RadioServiceClient(instrumentation.getTargetContext(),
                new RadioServiceClient.Listener() {
                    @Override public void onConnectionChanged(boolean connected, String message) { }
                    @Override public void onRadioError(String message) { errors.add(message); }
                    @Override public void onStateChanged(RadioServiceClient.RadioState state) { states.add(state); }
                });

        void close() { instrumentation.runOnMainSync(client::close); }
        void idle() { instrumentation.waitForIdleSync(); }
    }

    private static NwdAudioRouting audio() {
        return new NwdAudioRouting(new NwdAudioRouting.Transport() {
            @Override public int source() { return 4; }
            @Override public void send(Intent intent) { fail("Polling must not change the audio route"); }
        });
    }

    private static void setService(RadioServiceClient client, IRadioServiceAPI api) {
        try {
            Field field = RadioServiceClient.class.getDeclaredField("service");
            field.setAccessible(true); field.set(client, api);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void invoke(RadioServiceClient client, String methodName) {
        try {
            Method method = RadioServiceClient.class.getDeclaredMethod(methodName);
            method.setAccessible(true); method.invoke(client);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static ExecutorService actionExecutor(RadioServiceClient client) {
        try {
            Field field = RadioServiceClient.class.getDeclaredField("actionExecutor");
            field.setAccessible(true); return (ExecutorService) field.get(client);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void awaitActions(ExecutorService executor) {
        try { executor.submit(() -> { }).get(3, TimeUnit.SECONDS); }
        catch (Exception error) { throw new AssertionError(error); }
    }

    private static void changeBinding(Fixture f, boolean sameEndpoint, NwdRadioApi replacement) {
        if (sameEndpoint) invoke(f.client, "stopPolling");
        else setService(f.client, replacement);
    }

    @Test public void unbindReturnsWhilePollWaitsBehindSharedNwdMutation() throws Exception {
        checkResponsiveLifecycle(false);
    }

    @Test public void closeReturnsWhilePollWaitsBehindSharedNwdMutation() throws Exception {
        checkResponsiveLifecycle(true);
    }

    private static void checkResponsiveLifecycle(boolean close) throws Exception {
        Fixture f = new Fixture(); Endpoint endpoint = new Endpoint();
        NwdRadioConnectionCache cache = new NwdRadioConnectionCache(); NwdAudioRouting routing = audio();
        NwdRadioApi playback = cache.resolve(endpoint, routing);
        NwdRadioApi activity = cache.resolve(endpoint, routing); assertSame(playback, activity);
        setService(f.client, activity);
        FutureTask<Void> mutation = new FutureTask<>(() -> { playback.onLocDxEvent(); return null; });
        FutureTask<Void> poll = new FutureTask<>(() -> { invoke(f.client, "pollNow"); return null; });
        Thread mutationThread = new Thread(mutation, "qa-nwd-mutation");
        Thread pollThread = new Thread(poll, "qa-radio-poll");
        try {
            mutationThread.start(); assertTrue(endpoint.mutationEntered.await(3, TimeUnit.SECONDS));
            pollThread.start(); assertTrue(endpoint.pollReady.await(3, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (pollThread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals("Poll must actually be waiting for the shared API monitor", Thread.State.BLOCKED,
                    pollThread.getState());
            CountDownLatch lifecycleFinished = new CountDownLatch(1);
            new Handler(Looper.getMainLooper()).post(() -> {
                if (close) f.client.close(); else f.client.unbind();
                lifecycleFinished.countDown();
            });
            assertTrue("Main-thread lifecycle must not wait for a remote poll", lifecycleFinished.await(1, TimeUnit.SECONDS));
            assertFalse(f.client.isConnected());
            assertFalse("Synthetic mutator is still blocked", mutation.isDone());
            assertFalse("The old poll is still waiting, without blocking main", poll.isDone());
            endpoint.releaseMutation.countDown();
            mutation.get(3, TimeUnit.SECONDS); poll.get(3, TimeUnit.SECONDS); f.idle();
            assertTrue("Old binding must not deliver its completed poll", f.states.isEmpty());
            assertTrue(f.errors.isEmpty());
        } finally {
            endpoint.releaseMutation.countDown();
            mutationThread.join(3000); pollThread.join(3000); f.close();
        }
    }

    @Test public void sameEndpointRestartDropsQueuedStateAndResetsDeduplication() throws Exception {
        Fixture f = new Fixture(); Endpoint endpoint = new Endpoint();
        setService(f.client, new NwdRadioApi(endpoint, audio()));
        try {
            f.instrumentation.runOnMainSync(() -> {
                invoke(f.client, "pollNow");
                // Keep the same adapter, as happens when a new binding resolves
                // through the shared NWD connection cache.
                invoke(f.client, "stopPolling");
                invoke(f.client, "pollNow");
            });
            f.idle();
            assertEquals("Only the new polling generation may deliver", 1, f.states.size());
            assertEquals(98100, f.states.get(0).frequency);
            assertTrue(f.errors.isEmpty());
        } finally { f.close(); }
    }

    @Test public void concurrentScheduledAndActionPollsRemainSerialized() throws Exception {
        Fixture f = new Fixture(); Endpoint endpoint = new Endpoint();
        endpoint.frequencyEntered = new CountDownLatch(1); endpoint.releaseFrequency = new CountDownLatch(1);
        setService(f.client, new NwdRadioApi(endpoint, audio()));
        FutureTask<Void> first = new FutureTask<>(() -> { invoke(f.client, "pollNow"); return null; });
        FutureTask<Void> second = new FutureTask<>(() -> { invoke(f.client, "pollNow"); return null; });
        Thread firstThread = new Thread(first, "qa-first-poll"), secondThread = new Thread(second, "qa-second-poll");
        try {
            firstThread.start(); assertTrue(endpoint.frequencyEntered.await(3, TimeUnit.SECONDS));
            secondThread.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (secondThread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(Thread.State.BLOCKED, secondThread.getState());
            assertEquals("Only one poll may enter the remote-read sequence", 1, endpoint.frequencyReads.get());
            endpoint.releaseFrequency.countDown();
            first.get(3, TimeUnit.SECONDS); second.get(3, TimeUnit.SECONDS); f.idle();
            assertEquals(4, endpoint.frequencyReads.get());
            assertEquals("Identical polls remain deduplicated", 1, f.states.size());
        } finally {
            endpoint.releaseFrequency.countDown();
            firstThread.join(3000); secondThread.join(3000); f.close();
        }
    }

    @Test public void observedScanFallbackIsUnavailableForDisconnectedAndNwdClients() throws Exception {
        Fixture f = new Fixture();
        try {
            assertFalse(f.client.canUseObservedScanFallback());
            setService(f.client, new NwdRadioApi(new Endpoint(), audio()));
            assertFalse(f.client.canUseObservedScanFallback());
            Binder hcn = new Binder(); hcn.attachInterface(null, "com.hcn.autoradio.IRadioServiceAPI");
            setService(f.client, IRadioServiceAPI.Stub.asInterface(hcn));
            assertTrue(f.client.canUseObservedScanFallback());
        } finally { f.close(); }
    }

    @Test public void queuedPresetRequestDoesNotSwitchToANewerBindingOrGeneration() throws Exception {
        for (boolean sameEndpoint : new boolean[]{false, true}) {
            Fixture f = new Fixture(); Endpoint original = new Endpoint(), replacement = new Endpoint();
            original.rejectPresetRead = replacement.rejectPresetRead = true;
            setService(f.client, new NwdRadioApi(original, audio()));
            NwdRadioApi replacementApi = new NwdRadioApi(replacement, audio());
            ExecutorService executor = actionExecutor(f.client);
            CountDownLatch workerEntered = new CountDownLatch(1), releaseWorker = new CountDownLatch(1);
            AtomicInteger delivered = new AtomicInteger();
            Future<?> blocker = executor.submit(() -> { workerEntered.countDown(); Endpoint.await(releaseWorker); });
            try {
                assertTrue(workerEntered.await(3, TimeUnit.SECONDS));
                f.client.readPresetFrequencies(0, (band, values, complete) -> delivered.incrementAndGet());
                f.instrumentation.runOnMainSync(() -> changeBinding(f, sameEndpoint, replacementApi));
                releaseWorker.countDown(); blocker.get(3, TimeUnit.SECONDS); awaitActions(executor); f.idle();
                assertEquals("An obsolete request must not read either binding", 0,
                        original.presetReadRequests.get() + replacement.presetReadRequests.get());
                assertEquals(0, delivered.get());
            } finally { releaseWorker.countDown(); f.close(); }
        }
    }

    @Test public void queuedPresetResultIsDiscardedAfterBindingOrGenerationChanges() throws Exception {
        for (boolean sameEndpoint : new boolean[]{false, true}) {
            Fixture f = new Fixture(); Endpoint original = new Endpoint(), replacement = new Endpoint();
            original.rejectPresetRead = replacement.rejectPresetRead = true;
            setService(f.client, new NwdRadioApi(original, audio()));
            NwdRadioApi replacementApi = new NwdRadioApi(replacement, audio());
            ExecutorService executor = actionExecutor(f.client); AtomicInteger delivered = new AtomicInteger();
            RadioServiceClient.PresetResult callback = (band, values, complete) -> delivered.incrementAndGet();
            try {
                f.instrumentation.runOnMainSync(() -> {
                    f.client.readPresetFrequencies(0, callback);
                    // The worker finishes and queues its UI result while this
                    // main-thread turn prevents that result from running yet.
                    awaitActions(executor);
                    changeBinding(f, sameEndpoint, replacementApi);
                });
                f.idle();
                assertEquals(1, original.presetReadRequests.get());
                assertEquals("A queued result belongs only to its original binding", 0, delivered.get());
                f.client.readPresetFrequencies(0, callback); awaitActions(executor); f.idle();
                assertEquals("The new binding still receives its own result", 1, delivered.get());
            } finally { f.close(); }
        }
    }

    @Test public void inFlightNwdPresetReadStopsAfterSameEndpointGenerationChanges() throws Exception {
        Fixture f = new Fixture(); Endpoint endpoint = new Endpoint();
        endpoint.presetStopEntered = new CountDownLatch(1); endpoint.releasePresetStop = new CountDownLatch(1);
        setService(f.client, new NwdRadioApi(endpoint, audio()));
        ExecutorService executor = actionExecutor(f.client); AtomicInteger delivered = new AtomicInteger();
        try {
            f.client.readPresetFrequencies(0, (band, values, complete) -> delivered.incrementAndGet());
            assertTrue(endpoint.presetStopEntered.await(3, TimeUnit.SECONDS));
            f.instrumentation.runOnMainSync(() -> invoke(f.client, "stopPolling"));
            endpoint.releasePresetStop.countDown(); awaitActions(executor); f.idle();
            assertEquals("Canceled collection must not continue to read or switch banks", 0,
                    endpoint.frequencyReads.get());
            assertEquals(0, delivered.get());
        } finally { endpoint.releasePresetStop.countDown(); f.close(); }
    }
}
