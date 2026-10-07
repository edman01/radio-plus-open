package fi.radioplus.app;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.os.SystemClock;
import java.util.function.LongSupplier;

/** Audio-source protocol for the independently verified NWD backend contracts. */
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
    private final boolean decoderLifecycle;
    private boolean requested;
    private int requestedFrom;
    private long requestedAt;
    private boolean pausePending;
    static final long PENDING_ROUTE_MS = 3000L;

    NwdAudioRouting(Context context) {
        this(context, true);
    }
    NwdAudioRouting(Context context, boolean decoderLifecycle) {
        this(new Transport() {
            @Override public int source() {
                try { return Settings.System.getInt(context.getContentResolver(), SOURCE_KEY, -1); }
                catch (RuntimeException error) { return -1; }
            }
            @Override public void send(Intent intent) { context.sendBroadcast(intent); }
        }, SystemClock::elapsedRealtime, decoderLifecycle);
    }
    NwdAudioRouting(Transport transport) { this(transport, SystemClock::elapsedRealtime); }
    NwdAudioRouting(Transport transport, LongSupplier clock) {
        this(transport, clock, true);
    }
    NwdAudioRouting(Transport transport, LongSupplier clock, boolean decoderLifecycle) {
        this.transport = transport;
        this.clock = clock;
        this.decoderLifecycle = decoderLifecycle;
    }

    boolean usesDecoderLifecycle() { return decoderLifecycle; }

    synchronized boolean play() {
        int source = transport.source();
        // MCU source-only routing has no verified cancellation that restores a
        // non-Android source. Do not enqueue an asynchronous takeover from one:
        // a quick Pause could otherwise leave source=4 arriving after the pause.
        // Start from Android (0) or the already selected radio (4) only. Unknown
        // ownership also fails closed; the legacy decoder contract is unchanged.
        if (!decoderLifecycle && source != 0 && source != 4) return false;
        long now = clock.getAsLong();
        boolean resumePendingPause = pausePending;
        if (source == 4 && !resumePendingPause) {
            // A compensating Play may still be queued behind source=0 even
            // though this is the old source=4 reading, not its acknowledgement.
            if (decoderLifecycle || !requested || requestedFrom != 4) requested = false;
            pausePending = false;
            return true;
        }
        // Both our UI client and media service may request the same route.
        // Repeating APP_IN while the kernel is switching can restart vendor
        // initialization (and its temporary music mute). Retry only on a later
        // explicit request, after a bounded grace period; never run a retry loop.
        if (!resumePendingPause && requested && requestedFrom == source
                && now - requestedAt < PENDING_ROUTE_MS) return true;
        // Pause may still be queued while the source setting reports 4.
        // This explicit Play must follow it with source=4 (and decoder init when
        // required), not mistake the old setting for acknowledgement. Decoder
        // mode addresses two OEM processes; neither mode proves audible playback.
        // Kernel's direct-source rule changes audio without launching the stock UI.
        // The byte extra is essential: getByteExtra does not accept an Integer.
        transport.send(new Intent(CHANGE_SOURCE).setPackage(KERNEL_PACKAGE)
                .putExtra("extra_source_id", (byte) 4));
        if (decoderLifecycle) {
            // The verified AW decoder needs initialization in the radio service.
            // The MCU branch has no such decoder lifecycle: source routing alone
            // selects its hardware audio. Never send APP_IN for that branch.
            transport.send(new Intent(APP_IN).setPackage(RADIO_PACKAGE)
                    .putExtra("extra_app_id", 8).putExtra("extra_app_operation", 1)
                    .putExtra("extra_app_event", 0));
        }
        requested = true;
        requestedFrom = source;
        requestedAt = now;
        pausePending = false;
        return true; // Request sent, not proof of audible playback.
    }

    synchronized boolean pause() {
        long now = clock.getAsLong();
        boolean pendingFromArm = requested && (requestedFrom == 0
                || (!decoderLifecycle && requestedFrom == 4))
                && (!decoderLifecycle || now - requestedAt < PENDING_ROUTE_MS);
        if (decoderLifecycle) requested = false;
        // Do not select a different source or mute another app after a handoff.
        int source = transport.source();
        // A missing MCU source reading cannot cancel or acknowledge our queued
        // start. Preserve it so a later known 0/4 reading can still be paused;
        // otherwise a delayed source=4 could start after the user pressed Pause.
        if (!decoderLifecycle && source < 0 && pendingFromArm) {
            pausePending = true;
            return false;
        }
        requested = false;
        // A quick pause may precede the asynchronous source=4 acknowledgement.
        // Queue source=0 after our pending request only if it still reports ARM;
        // this also leaves any music already using ARM on that same source.
        // MCU requests stay cancelable until acknowledged or counterqueued:
        // the retry grace period is not proof that the kernel discarded them.
        if (source != 4 && !(source == 0 && pendingFromArm)) {
            if (!decoderLifecycle && source >= 0 && source != 0) pausePending = false;
            return false;
        }
        pausePending = true;
        transport.send(new Intent(CHANGE_SOURCE).setPackage(KERNEL_PACKAGE)
                .putExtra("extra_source_id", (byte) 0));
        if (decoderLifecycle) {
            transport.send(new Intent(EXIT_RADIO).setPackage(RADIO_PACKAGE));
        }
        return true;
    }

    synchronized boolean hasPendingStart() {
        // A compensating Play behind a queued Pause can still observe source 4.
        // It is our own unresolved start too, not already settled radio playback.
        return !decoderLifecycle && requested && (requestedFrom == 0 || requestedFrom == 4);
    }

    synchronized boolean markPendingCancellation() {
        // Record explicit Pause before its worker is queued. An independent
        // health read must not acknowledge away the cancellation in that gap.
        // This marks only our own MCU request; no source read or command occurs.
        if (!hasPendingStart()) return false;
        pausePending = true;
        return true;
    }

    synchronized boolean cancelPendingStart() {
        // Semantic command rejection is not a request to pause arbitrary audio.
        // Only undo this MCU adapter's unresolved Android-to-radio request.
        if (!hasPendingStart()) return false;
        int source = transport.source();
        if (source != 0 && source != 4) {
            // A known handoff ends our authority over the pending route. An
            // unavailable reading proves neither ownership nor a handoff.
            if (source >= 0) {
                requested = false;
                pausePending = false;
            } else {
                pausePending = true;
            }
            return false;
        }
        // Source 4 can arrive between a caller's source-0 snapshot and this
        // cancellation. It is only eligible while our own request is unresolved;
        // readHealth()/settled Play already retire acknowledged requests.
        requested = false;
        pausePending = true;
        transport.send(new Intent(CHANGE_SOURCE).setPackage(KERNEL_PACKAGE)
                .putExtra("extra_source_id", (byte) 0));
        return true;
    }

    synchronized RadioPlaybackHealthReader.Snapshot readHealth() {
        int source = transport.source();
        // A settled start acknowledgement retires the request. A later explicit
        // Play after another app takes ARM must not be lost in the grace period.
        // Observing a source never sends any command or reclaims playback.
        if (source == 4 && (decoderLifecycle || (!pausePending && requestedFrom != 4))) requested = false;
        // Observing the earlier Pause at source 0 lets the following source 4
        // acknowledge a compensating Play. Until then a stale 4 proves nothing.
        if (!decoderLifecycle && requested && requestedFrom == 4 && source == 0) requestedFrom = 0;
        // Preserve an unresolved MCU cancellation even if another client's
        // health poll sees our late source=4. It is still our queued start to
        // cancel, not settled playback. Explicit Play supersedes pausePending.
        // A known external handoff instead ends that authority without a write.
        if (!decoderLifecycle && source >= 0 && source != 0 && source != 4) {
            requested = false;
            pausePending = false;
        }
        // A source snapshot cannot retire the latest pause intent, including an
        // optional decoder EXIT in another process. Explicit Play supersedes it.
        return new RadioPlaybackHealthReader.Snapshot(source >= 0,
                source == 4 ? RADIO_PACKAGE : "nwd.source/" + source, false, false);
    }
}
