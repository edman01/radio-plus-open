package fi.radioplus.app;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Looper;
import android.os.SystemClock;

import java.util.function.BooleanSupplier;

/**
 * A finite, zero-valued PCM registration attempt for an explicit radio request.
 * This does not request audio focus, own a MediaSession, or guarantee media-key ownership.
 * The caller must restore its OEM radio route after this returns, only if its request is current.
 */
final class MediaKeyRoutingPulse {
    private static final int SAMPLE_RATE = 48_000;
    private static final int PULSE_MILLIS = 180;
    private static final int SAMPLE_COUNT = SAMPLE_RATE * PULSE_MILLIS / 1_000;
    private static final int CHECK_INTERVAL_MILLIS = 20;
    private static final int DEADLINE_MILLIS = 350;

    enum Status {
        COMPLETED,
        CANCELLED,
        DEADLINE_EXCEEDED,
        MAIN_THREAD_REJECTED,
        FAILED
    }

    static final class Result {
        final Status status;
        final long elapsedMillis;
        final boolean trackCreated;
        final boolean trackReleased;

        private Result(Status status, long elapsedMillis, boolean trackCreated,
                boolean trackReleased) {
            this.status = status;
            this.elapsedMillis = elapsedMillis;
            this.trackCreated = trackCreated;
            this.trackReleased = trackReleased;
        }

        String diagnostic() {
            return "status=" + status.name() + " elapsed_ms=" + elapsedMillis
                    + " track_created=" + trackCreated + " track_released=" + trackReleased;
        }
    }

    private MediaKeyRoutingPulse() { }

    /**
     * Run on the caller's serialized background executor. The cancellation supplier must be
     * fast and side-effect-free. Native build/play/release calls are not interruptible: the
     * 350 ms deadline bounds our own work/waits, not a malfunctioning vendor audio implementation.
     */
    static Result run(BooleanSupplier cancelled) {
        long startedAt = SystemClock.elapsedRealtime();
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return result(Status.MAIN_THREAD_REJECTED, startedAt, false, false);
        }
        if (cancelled == null) return result(Status.FAILED, startedAt, false, false);

        AudioTrack track = null;
        boolean created = false;
        boolean released = false;
        Status status = Status.FAILED;
        long deadline = startedAt + DEADLINE_MILLIS;
        try {
            Status stop = stopReason(cancelled, deadline);
            if (stop != null) {
                status = stop;
            } else {
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .setBufferSizeInBytes(SAMPLE_COUNT * 2)
                        .build();
                created = true;
                status = playFiniteBuffer(track, cancelled, deadline);
            }
        } catch (RuntimeException | LinkageError failure) {
            // No logs or exception messages: only a bounded technical result reaches diagnostics.
            status = Status.FAILED;
        } finally {
            if (track != null) {
                try {
                    // release() stops this track as well. Never retain or loop the PCM resource.
                    track.release();
                    released = track.getState() == AudioTrack.STATE_UNINITIALIZED;
                    if (!released) status = Status.FAILED;
                } catch (RuntimeException | LinkageError failure) {
                    status = Status.FAILED;
                }
            }
        }
        return result(status, startedAt, created, released);
    }

    private static Status playFiniteBuffer(AudioTrack track, BooleanSupplier cancelled,
            long deadline) {
        Status stop = stopReason(cancelled, deadline);
        if (stop != null) return stop;
        if (track.getState() == AudioTrack.STATE_UNINITIALIZED) return Status.FAILED;

        // Java guarantees zero initialization; there is no resource/file or generated sound.
        short[] silence = new short[SAMPLE_COUNT];
        int written = track.write(silence, 0, silence.length, AudioTrack.WRITE_NON_BLOCKING);
        if (written != silence.length || track.getState() != AudioTrack.STATE_INITIALIZED) {
            return Status.FAILED;
        }
        stop = stopReason(cancelled, deadline);
        if (stop != null) return stop;
        track.play();
        if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) return Status.FAILED;

        long end = SystemClock.elapsedRealtime() + PULSE_MILLIS;
        while (true) {
            stop = stopReason(cancelled, deadline);
            if (stop != null) return stop;
            long remaining = end - SystemClock.elapsedRealtime();
            if (remaining <= 0L) return Status.COMPLETED;
            long untilDeadline = deadline - SystemClock.elapsedRealtime();
            if (untilDeadline <= 0L) return Status.DEADLINE_EXCEEDED;
            try {
                Thread.sleep(Math.min(CHECK_INTERVAL_MILLIS, Math.min(remaining, untilDeadline)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Status.CANCELLED;
            }
        }
    }

    private static Status stopReason(BooleanSupplier cancelled, long deadline) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
            return Status.CANCELLED;
        }
        return SystemClock.elapsedRealtime() >= deadline ? Status.DEADLINE_EXCEEDED : null;
    }

    private static Result result(Status status, long startedAt, boolean created, boolean released) {
        return new Result(status, Math.max(0L, SystemClock.elapsedRealtime() - startedAt),
                created, released);
    }
}
