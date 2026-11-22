package fi.radioplus.app;

import android.content.Context;
import android.os.SystemClock;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Lifetime is limited to the opt-in, focused Activity, not the playback service. */
final class ReglinkWheelSession implements AutoCloseable {
    private final Context context;
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1);
    private Visit current; // UI thread
    ReglinkWheelSession(Context context) {
        this.context = context.getApplicationContext();
        worker.setRemoveOnCancelPolicy(true);
    }

    void visible(boolean enabled) {
        if (enabled == (current != null) || worker.isShutdown()) return;
        if (enabled) {
            Visit v = new Visit();
            current = v;
            v.task = worker.scheduleWithFixedDelay(v::tick, 0, 450, TimeUnit.MILLISECONDS);
        } else {
            Visit old = current;
            current = null;
            old.visible = false; // invalidate already-queued commands before any async cleanup
            old.task.cancel(false);
            worker.execute(old::detach);
        }
    }

    @Override public void close() { visible(false); worker.shutdown(); }

    private final class Visit {
        volatile boolean visible = true;
        ScheduledFuture<?> task;
        ReglinkRadioApi last;
        ReglinkWheelKeys keys;
        AtomicBoolean lease;
        long observeUntil;
        void detach() {
            if (lease != null) lease.set(false);
            if (keys != null) keys.close();
            keys = null; last = null;
        }
        void tick() {
            ReglinkRadioApi api = visible ? RadioPlaybackService.activeWheelRadio() : null;
            if (api != last) {
                detach();
                last = api;
                if (api != null) {
                    AtomicBoolean generation = new AtomicBoolean(true);
                    lease = generation;
                    BooleanSupplier live = () -> visible && generation.get()
                            && RadioPlaybackService.activeWheelRadio() == api;
                    try {
                        int uid = context.getPackageManager().getApplicationInfo("com.reglink.services", 0).uid;
                        keys = new ReglinkWheelKeys(api.asBinder(), uid, live,
                                next -> RadioPlaybackService.postWheelSkip(api, next, live));
                        if (!live.getAsBoolean()) return;
                        keys.open();
                        observeUntil = SystemClock.elapsedRealtime() + 45_000;
                        try { keys.startObservation(); }
                        catch (Exception e) { SupportTestLog.failure(SupportTestLog.Event.WHEEL_OBSERVED, e); }
                    } catch (Exception e) {
                        generation.set(false);
                        if (keys != null) keys.close();
                        keys = null;
                        SupportTestLog.failure(SupportTestLog.Event.WHEEL_LISTENER, e);
                    }
                }
            }
            if (keys != null && observeUntil != 0 && SystemClock.elapsedRealtime() >= observeUntil) {
                keys.stopObservation(); observeUntil = 0;
            }
        }
    }
}
