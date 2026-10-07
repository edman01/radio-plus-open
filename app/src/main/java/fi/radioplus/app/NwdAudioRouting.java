package fi.radioplus.app;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.os.SystemClock;
import java.util.function.LongSupplier;

/** Audio-source protocol of the fingerprinted NWD KernelService 2.2.6 only. */
final class NwdAudioRouting {
    static final String KERNEL_PACKAGE = "com.nwd.kernel";
    static final String RADIO_PACKAGE = "com.nwd.radio.service";
    static final String CHANGE_SOURCE = "com.nwd.action.ACTION_REQUEST_CHANGE_SOURCE";
    static final String APP_IN = "com.nwd.action.ACTION_APP_IN_OUT";
    static final String EXIT_RADIO = "com.nwd.android.ACTION_EXIT_ARM_FM_RAIDO";
    static final String SOURCE_KEY = "mcu_current_source";

    interface Transport {
        int source(); // -1 means unavailable, never infer radio ownership from it.
        void send(Intent intent);
    }
    private final Transport transport;
    private final LongSupplier clock;
    private boolean requested;
    private int requestedFrom;
    private long requestedAt;
    static final long PENDING_ROUTE_MS = 3000L;

    NwdAudioRouting(Context context) {
        this(new Transport() {
            @Override public int source() {
                try { return Settings.System.getInt(context.getContentResolver(), SOURCE_KEY, -1); }
                catch (RuntimeException error) { return -1; }
            }
            @Override public void send(Intent intent) { context.sendBroadcast(intent); }
        });
    }
    NwdAudioRouting(Transport transport) { this(transport, SystemClock::elapsedRealtime); }
    NwdAudioRouting(Transport transport, LongSupplier clock) {
        this.transport = transport;
        this.clock = clock;
    }

    synchronized boolean play() {
        int source = transport.source();
        long now = clock.getAsLong();
        if (source == 4) {
            requested = false;
            return true;
        }
        // Both our UI client and media service may request the same route.
        // Repeating APP_IN while the kernel is switching can restart vendor
        // initialization (and its temporary music mute). Retry only on a later
        // explicit request, after a bounded grace period; never run a retry loop.
        if (requested && requestedFrom == source && now - requestedAt < PENDING_ROUTE_MS) return true;
        // Kernel's direct-source rule changes audio without launching the stock UI.
        // The byte extra is essential: getByteExtra does not accept an Integer.
        transport.send(new Intent(CHANGE_SOURCE).setPackage(KERNEL_PACKAGE)
                .putExtra("extra_source_id", (byte) 4));
        // ARM tuners also need initialization, sent ONLY to the radio service.
        // Never broadcast the stock app-enter event globally or imitate its UI.
        transport.send(new Intent(APP_IN).setPackage(RADIO_PACKAGE)
                .putExtra("extra_app_id", 8).putExtra("extra_app_operation", 1)
                .putExtra("extra_app_event", 0));
        requested = true;
        requestedFrom = source;
        requestedAt = now;
        return true; // Request sent, not proof of audible playback.
    }

    synchronized boolean pause() {
        boolean pendingFromArm = requested && requestedFrom == 0
                && clock.getAsLong() - requestedAt < PENDING_ROUTE_MS;
        requested = false;
        // Do not select a different source or mute another app after a handoff.
        int source = transport.source();
        // A quick pause may precede the asynchronous source=4 acknowledgement.
        // Queue source=0 after our pending request only if it still reports ARM;
        // this also leaves any music already using ARM on that same source.
        if (source != 4 && !(source == 0 && pendingFromArm)) return false;
        transport.send(new Intent(CHANGE_SOURCE).setPackage(KERNEL_PACKAGE)
                .putExtra("extra_source_id", (byte) 0));
        transport.send(new Intent(EXIT_RADIO).setPackage(RADIO_PACKAGE));
        return true;
    }

    synchronized RadioPlaybackHealthReader.Snapshot readHealth() {
        int source = transport.source();
        // Once acknowledged, the request is no longer pending. A later explicit
        // Play after another app takes ARM must not be lost in the grace period.
        // Observing a source never sends any command or reclaims playback.
        if (source == 4) requested = false;
        return new RadioPlaybackHealthReader.Snapshot(source >= 0,
                source == 4 ? RADIO_PACKAGE : "nwd.source/" + source, false, false);
    }
}
