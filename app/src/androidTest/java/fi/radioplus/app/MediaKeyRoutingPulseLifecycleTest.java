package fi.radioplus.app;

import android.app.Instrumentation;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeFalse;

/** Resource/precondition checks; actual system routing is tested separately. */
public final class MediaKeyRoutingPulseLifecycleTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test
    public void mainThreadIsRejectedBeforeAllocatingAudioTrack() {
        AtomicReference<MediaKeyRoutingPulse.Result> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> result.set(MediaKeyRoutingPulse.run(() -> false)));
        assertEquals(MediaKeyRoutingPulse.Status.MAIN_THREAD_REJECTED, result.get().status);
        assertFalse(result.get().trackCreated);
        assertFalse(result.get().trackReleased);
    }

    @Test
    public void alreadyCancelledRequestDoesNotAllocateAudioTrack() {
        MediaKeyRoutingPulse.Result result = MediaKeyRoutingPulse.run(() -> true);
        assertEquals(MediaKeyRoutingPulse.Status.CANCELLED, result.status);
        assertFalse(result.trackCreated);
        assertFalse(result.trackReleased);
    }

    @Test
    public void interruptedWorkerDoesNotAllocateAndKeepsInterruptFlag() throws Exception {
        AtomicReference<MediaKeyRoutingPulse.Result> result = new AtomicReference<>();
        AtomicReference<Boolean> interrupted = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            Thread.currentThread().interrupt();
            result.set(MediaKeyRoutingPulse.run(() -> false));
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        worker.start();
        worker.join(2_000L);
        assertFalse("Worker did not finish", worker.isAlive());
        assertNotNull(result.get());
        assertEquals(MediaKeyRoutingPulse.Status.CANCELLED, result.get().status);
        assertFalse(result.get().trackCreated);
        assertEquals(Boolean.TRUE, interrupted.get());
    }

    @Test
    public void cancellationImmediatelyAfterPlayStillReleasesTrack() {
        assumeFalse("Real PCM fixture must not run on a Junsun head unit", hasVendorFramework());
        AtomicInteger checks = new AtomicInteger();
        // Checks are before creation, before write, before play, then in the bounded wait.
        MediaKeyRoutingPulse.Result result = MediaKeyRoutingPulse.run(
                () -> checks.incrementAndGet() >= 4);
        assertEquals(MediaKeyRoutingPulse.Status.CANCELLED, result.status);
        assertTrue(result.trackCreated);
        assertTrue(result.trackReleased);
        assertTrue(result.diagnostic().contains("track_released=true"));
    }

    private static boolean hasVendorFramework() {
        try {
            Class.forName("android.radio.RadioPlayer");
            return true;
        } catch (ClassNotFoundException absent) {
            return false;
        }
    }
}
