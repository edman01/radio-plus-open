package fi.radioplus.app;

import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;

import com.hcn.autoradio.IRadioCallBack;
import com.hcn.autoradio.IRadioServiceAPI;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Commands for the separately authenticated SPD contract; no source spoofing or key forwarding. */
final class SpdRadioApi implements IRadioServiceAPI {
    static final String RADIO_SOURCE = "com.spd.radio";
    private static final int SET_BAND = 0x2000;
    private static final int PLAY_STATE = 0x2007;
    private static final int TUNE = 1, PLAY = 2, PAUSE = 3;
    private static final BooleanSupplier CURRENT = () -> true;
    private final SpdRadioProbe probe;
    private final SpdAudioSourceReader sourceReader;
    private final long confirmationTimeoutMs;
    private final AtomicLong cancellationRevision = new AtomicLong();
    private volatile Pending pending;

    static final class CommandRejectedException extends RemoteException {
        CommandRejectedException(String message) { super(message); }
    }

    private static final class Pending {
        final int operation;
        final String band;
        final int frequency;
        final String startingSource;
        final boolean needsPlayingBeforePause;
        boolean sourceLost;
        boolean sawPlaying;

        Pending(int operation, String band, int frequency, String startingSource) {
            this(operation, band, frequency, startingSource, false);
        }

        Pending(int operation, String band, int frequency, String startingSource,
                boolean needsPlayingBeforePause) {
            this.operation = operation;
            this.band = band;
            this.frequency = frequency;
            this.startingSource = startingSource;
            this.needsPlayingBeforePause = needsPlayingBeforePause;
        }
    }

    SpdRadioApi(IBinder binder, SpdAudioSourceReader sourceReader) throws RemoteException {
        this(binder, sourceReader, 1500L);
    }

    SpdRadioApi(IBinder binder, SpdAudioSourceReader sourceReader, long timeoutMs)
            throws RemoteException {
        if (sourceReader == null || timeoutMs < 0 || timeoutMs > 5000L) {
            throw rejected("Invalid SPD adapter configuration");
        }
        this.probe = new SpdRadioProbe(binder);
        this.sourceReader = sourceReader;
        this.confirmationTimeoutMs = timeoutMs;
    }

    @Override public IBinder asBinder() { return probe.asBinder(); }

    SpdRadioProbe.Frequency frequency() throws RemoteException { return probe.readFrequency(); }

    boolean hasPendingCommand() { return pending != null; }

    boolean hasPendingAudioConfirmation() {
        return pending != null;
    }

    boolean hasPendingAudioStart() {
        Pending current = pending;
        return current != null && current.operation == PLAY;
    }

    /** Main-thread-safe: cancels local waiting, not a command already queued by the OEM. */
    void cancelPendingAudioStart() { cancellationRevision.incrementAndGet(); }

    private BooleanSupplier guard(BooleanSupplier current) {
        long revision = cancellationRevision.get();
        return () -> revision == cancellationRevision.get() && isCurrent(current);
    }

    /** No hardware writes: both the app grid and the advertised native band must accept the target. */
    void validateTuningTarget(int band, int khz) throws RemoteException {
        validatedTarget(band, khz);
    }

    private SpdTuningGrid validatedTarget(int band, int khz) throws RemoteException {
        String nativeBand = nativeBand(band);
        if (!FrequencyRules.isValid(band, khz)) throw rejected("Unsupported SPD tuning target");
        try {
            if (!Arrays.asList(probe.readBands()).contains(nativeBand)) {
                throw rejected("SPD target band is not advertised");
            }
            SpdTuningGrid grid = probe.readBand(nativeBand).grid;
            if (grid.scale != 1 || !grid.contains(khz)) {
                throw rejected("SPD target does not fit the native frequency grid");
            }
            return grid;
        } catch (RemoteException error) {
            throw rejected("SPD target could not be verified");
        }
    }

    synchronized boolean tuneToBand(int band, int khz, BooleanSupplier current)
            throws RemoteException {
        current = guard(current);
        if (!isCurrent(current)) return false;
        requireResolvedCommand();
        SpdTuningGrid target = validatedTarget(band, khz);
        try {
            SpdRadioProbe.Frequency before = probe.readFrequency();
            String owner = sourceReader.currentSource();
            SpdRadioProbe.Status status = probe.readStatus();
            requireNoSearch(status);
            if (!RADIO_SOURCE.equals(owner) || status.playState != 1
                    || !owner.equals(sourceReader.currentSource())) {
                throw rejected("SPD tuning requires the active radio source");
            }
            if (target.nativeBand.equals(before.grid.nativeBand)
                    && khz == before.grid.frequency) return isCurrent(current);
            Bundle arguments = new Bundle();
            arguments.putString("string", target.nativeBand);
            // One atomic band+frequency request: never restore an old band first.
            if (!send(SET_BAND, khz, arguments,
                    new Pending(TUNE, target.nativeBand, khz, owner), current)) return false;
            return awaitConfirmation(current);
        } catch (RemoteException error) {
            throw rejected("SPD tuning was not confirmed; command is not replayed");
        }
    }

    boolean tuneToBand(int band, int khz) throws RemoteException {
        return tuneToBand(band, khz, CURRENT);
    }

    @Override public boolean requestPlayAudio() throws RemoteException {
        return requestPlayAudio(CURRENT);
    }

    boolean requestPlayAudio(BooleanSupplier current) throws RemoteException {
        return requestPlayAudio(current, false);
    }

    /** Explicit manual tuning owns only the activation that this invocation submitted. */
    synchronized boolean requestPlayAudioForTuning(BooleanSupplier current) throws RemoteException {
        Pending before = pending;
        long revision = cancellationRevision.get();
        BooleanSupplier actionCurrent = () -> revision == cancellationRevision.get() && isCurrent(current);
        boolean confirmed = false;
        try {
            confirmed = requestPlayAudio(actionCurrent, true);
            return confirmed;
        } finally {
            if (!confirmed) settleUnconfirmedTuningActivation(before, current, revision);
        }
    }

    private void settleUnconfirmedTuningActivation(Pending before, BooleanSupplier current,
            long revision) {
        Pending submitted = pending;
        if (submitted == null || submitted == before || submitted.operation != PLAY
                || revision != cancellationRevision.get() || !isCurrent(current)
                || !probe.asBinder().isBinderAlive()) return;
        RadioPlaybackHealthReader.Snapshot health = readHealth();
        if (!health.sourceKnown || !health.muteKnown
                || (health.radioOwnsSource() && !health.muted)) return;
        // Do not race a main-thread cancellation or another user's action. This
        // increments only our local wait revision; the accepted OEM PLAY remains
        // queued, so one absolute PAUSE may follow it on this same endpoint.
        if (!isCurrent(current) || !probe.asBinder().isBinderAlive()
                || !cancellationRevision.compareAndSet(revision, revision + 1)) return;
        try {
            pauseRadioSource(() -> cancellationRevision.get() == revision + 1 && isCurrent(current));
        } catch (RemoteException ignored) {
            // Preserve the original failure and the unresolved barrier. No retry.
        }
    }

    synchronized boolean requestPlayAudio(BooleanSupplier current, boolean explicitRequest)
            throws RemoteException {
        current = guard(current);
        if (!isCurrent(current)) return false;
        boolean superseding = false;
        try {
            requireResolvedCommand();
        } catch (CommandRejectedException unresolved) {
            // Only a new explicit user activation can supersede our prior audio
            // operation. Never replay or discard an unresolved tuning target.
            if (!explicitRequest || pending == null || pending.operation == TUNE) throw unresolved;
            superseding = true;
        }
        try {
            SpdRadioProbe.Frequency frequency = probe.readFrequency();
            if (!Arrays.asList(probe.readBands()).contains(frequency.grid.nativeBand)) {
                throw rejected("SPD current band is not advertised");
            }
            String owner = sourceReader.currentSource();
            SpdRadioProbe.Status status = probe.readStatus();
            requireNoSearch(status);
            if (owner == null || !owner.equals(sourceReader.currentSource())) {
                throw rejected("SPD source ownership is unknown or changing");
            }
            if (!superseding && RADIO_SOURCE.equals(owner) && status.playState == 1) {
                return isCurrent(current);
            }
            // A reconnect/background check may only observe a healthy route.
            // It must never acquire or resume one, even if an earlier outer
            // health snapshot reported that radio still owned the source.
            if (!explicitRequest) throw rejected("SPD playback requires an explicit user request");
            if (!send(PLAY_STATE, 1, null, new Pending(PLAY, null, 0, owner), current)) return false;
            return awaitConfirmation(current);
        } catch (RemoteException error) {
            throw rejected("SPD playback was not confirmed; command is not replayed");
        }
    }

    boolean pauseRadioSource() throws RemoteException { return pauseRadioSource(CURRENT); }

    synchronized boolean pauseRadioSource(BooleanSupplier current) throws RemoteException {
        current = guard(current);
        if (!isCurrent(current)) return false;
        try {
            Pending previous = pending;
            if (previous != null && previous.operation == PAUSE) {
                return confirmPending() && isCurrent(current);
            }
            String owner = sourceReader.currentSource();
            SpdRadioProbe.Status status = probe.readStatus();
            requireNoSearch(status);
            if (owner == null || !owner.equals(sourceReader.currentSource())) return false;
            boolean cancelsOwnQueuedPlay = previous != null && previous.operation == PLAY
                    && owner.equals(previous.startingSource);
            if (!RADIO_SOURCE.equals(owner) && !cancelsOwnQueuedPlay) return false;
            if (status.playState == 0 && pending == null) return isCurrent(current);
            // PLAY_STATE(0,0) releases only this radio service's audio focus.
            // A pause may follow our already accepted play/tune on the same
            // service queue, including before a queued PLAY acquires its source.
            // The captured unchanged owner is required for that narrow case.
            // It supersedes that pending operation, not replays it.
            Pending pause = new Pending(PAUSE, null, 0, owner,
                    previous != null && status.playState == 0);
            if (!send(PLAY_STATE, 0, null, pause, current)) return false;
            return awaitConfirmation(current);
        } catch (RemoteException error) {
            throw rejected("SPD pause was not confirmed; command is not replayed");
        }
    }

    RadioPlaybackHealthReader.Snapshot readHealth() {
        String owner = sourceReader.currentSource();
        boolean known = false, muted = false;
        try {
            SpdRadioProbe.Status status = probe.readStatus();
            known = true;
            muted = status.playState == 0;
        } catch (RemoteException ignored) { }
        String after = sourceReader.currentSource();
        if (owner == null || !owner.equals(after)) owner = null;
        // These are service/source observations, not physical audio measurements.
        return new RadioPlaybackHealthReader.Snapshot(owner != null, owner, known, muted);
    }

    private void requireResolvedCommand() throws RemoteException {
        try {
            if (pending != null && !confirmPending()) {
                throw rejected("Previous SPD command remains unconfirmed");
            }
        } catch (RemoteException error) {
            throw rejected("Previous SPD command remains unconfirmed");
        }
    }

    private boolean awaitConfirmation(BooleanSupplier current) throws RemoteException {
        long deadline = SystemClock.elapsedRealtime() + confirmationTimeoutMs;
        do {
            if (!isCurrent(current)) return false;
            if (confirmPending()) return isCurrent(current);
            if (pending.sourceLost || SystemClock.elapsedRealtime() >= deadline) return false;
            try {
                Thread.sleep(Math.min(40L, Math.max(1L, deadline - SystemClock.elapsedRealtime())));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (true);
    }

    private boolean confirmPending() throws RemoteException {
        if (pending == null) return true;
        String owner = sourceReader.currentSource();
        SpdRadioProbe.Status status = probe.readStatus();
        SpdRadioProbe.Frequency frequency = pending.operation == TUNE ? probe.readFrequency() : null;
        String after = sourceReader.currentSource();
        if (owner == null || !owner.equals(after)) {
            pending.sourceLost = true;
            return false;
        }
        if (pending.operation != PAUSE && !RADIO_SOURCE.equals(owner)
                && !(pending.operation == PLAY && pending.startingSource.equals(owner))) {
            pending.sourceLost = true;
            return false;
        }
        if (status.playState == 1) pending.sawPlaying = true;
        // A queued PLAY followed by PAUSE can initially still report paused.
        // Do not mistake that unchanged pre-command snapshot for completion.
        boolean matched = !searching(status) && (pending.operation == PAUSE
                ? status.playState == 0 && (!pending.needsPlayingBeforePause || pending.sawPlaying)
                : RADIO_SOURCE.equals(owner) && status.playState == 1);
        if (pending.operation == TUNE) {
            matched &= pending.band.equals(frequency.grid.nativeBand)
                    && pending.frequency == frequency.grid.frequency;
        }
        if (matched) pending = null;
        return matched;
    }

    private boolean send(int command, int argument, Bundle extras, Pending operation,
            BooleanSupplier current) throws RemoteException {
        Parcel input = Parcel.obtain();
        Parcel output = Parcel.obtain();
        try {
            input.writeInterfaceToken(SpdRadioProbe.DESCRIPTOR);
            input.writeInt(command);
            input.writeInt(argument);
            input.writeInt(0);
            input.writeInt(extras == null ? 0 : 1);
            if (extras != null) extras.writeToParcel(input, 0);
            if (!isCurrent(current)) return false;
            if (!operation.startingSource.equals(sourceReader.currentSource())) {
                throw rejected("SPD source changed before command submission");
            }
            if (!isCurrent(current)) return false;
            // Once submitted, an OEM queued command cannot be canceled by us.
            pending = operation;
            if (!probe.asBinder().transact(20, input, output, 0)
                    || output.dataAvail() != 4 || output.readInt() != 0) {
                throw rejected("SPD command acknowledgement unavailable");
            }
            return true;
        } catch (RuntimeException | RemoteException error) {
            throw rejected("SPD command rejected or unconfirmed");
        } finally {
            input.recycle();
            output.recycle();
        }
    }

    private static boolean isCurrent(BooleanSupplier current) {
        return current != null && current.getAsBoolean() && !Thread.currentThread().isInterrupted();
    }

    private static boolean searching(SpdRadioProbe.Status status) {
        return status.seeking || status.preview || status.autoSearch;
    }

    private static void requireNoSearch(SpdRadioProbe.Status status) throws RemoteException {
        if (searching(status)) throw rejected("SPD search is already active");
    }

    private static String nativeBand(int band) throws RemoteException {
        if (band == 0) return "FM";
        if (band == 3) return "AM";
        throw rejected("Unsupported SPD band");
    }

    private static CommandRejectedException rejected(String message) {
        return new CommandRejectedException(message);
    }

    private static CommandRejectedException unsupported() {
        return rejected("Operation is not verified for this SPD profile");
    }

    @Override public synchronized void onBandEvent() throws RemoteException {
        int band = frequency().grid.band == 0 ? 3 : 0;
        int target = probe.readBand(nativeBand(band)).grid.frequency;
        if (!tuneToBand(band, target)) throw rejected("SPD band change not confirmed");
    }

    private synchronized void step(int direction) throws RemoteException {
        SpdTuningGrid grid = frequency().grid;
        if (!tuneToBand(grid.band, grid.next(direction))) throw rejected("SPD step not confirmed");
    }

    @Override public void onManualUpEvent() throws RemoteException { step(-1); }
    @Override public void onManualDownEvent() throws RemoteException { step(1); }
    @Override public synchronized void gotoFreq(int frequency) throws RemoteException {
        if (!tuneToBand(frequency().grid.band, frequency)) throw rejected("SPD tune not confirmed");
    }
    @Override public void gotoFreq2(String frequency) throws RemoteException { throw unsupported(); }
    @Override public int getCurrentBand() throws RemoteException { return frequency().grid.band; }
    @Override public int getCurrentFreq() throws RemoteException { return frequency().grid.frequency; }
    @Override public String getCurrentFreqRdsPs() throws RemoteException { return frequency().ps; }
    @Override public boolean getFreqIsFavorite(int band, int frequency) { return false; }
    @Override public boolean currentFreqIsFavorite() { return false; }
    @Override public boolean IsAS() throws RemoteException { return probe.readStatus().autoSearch; }
    @Override public boolean IsPS() throws RemoteException { return probe.readStatus().preview; }
    @Override public boolean IsScan() throws RemoteException { return probe.readStatus().autoSearch; }
    @Override public boolean IsSeek() throws RemoteException { return probe.readStatus().seeking; }
    @Override public boolean IsStereo() throws RemoteException { return probe.readStatus().stereo; }
    @Override public boolean IsDxLocal() throws RemoteException { return probe.readStatus().local == 1; }
    @Override public void registerRadioClientBinder(IBinder binder) throws RemoteException { throw unsupported(); }
    @Override public void unRegisterRadioClientBinder() throws RemoteException { throw unsupported(); }
    @Override public void registerRadioCallback(IRadioCallBack callback) throws RemoteException { throw unsupported(); }
    @Override public void unRegisterRadioCallback(IRadioCallBack callback) throws RemoteException { throw unsupported(); }
    @Override public void onASEvent() throws RemoteException { throw unsupported(); }
    @Override public void onPSEvent() throws RemoteException { throw unsupported(); }
    @Override public void onLocDxEvent() throws RemoteException { throw unsupported(); }
    @Override public void onSeekDownEvent() throws RemoteException { throw unsupported(); }
    @Override public void onSeekUpEvent() throws RemoteException { throw unsupported(); }
    @Override public void onScanEvent() throws RemoteException { throw unsupported(); }
    @Override public void gotoFreqIndex(int index) throws RemoteException { throw unsupported(); }
    @Override public void favoriteCurrentFreq() throws RemoteException { throw unsupported(); }
    @Override public void requestAudioFocus() throws RemoteException { throw unsupported(); }
    @Override public void releaseAudioFocus() throws RemoteException { throw unsupported(); }
}
