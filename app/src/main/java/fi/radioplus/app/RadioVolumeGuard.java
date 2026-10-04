package fi.radioplus.app;

import android.annotation.SuppressLint;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;

/** Restores normal FM input gain on activation; volume is owned by the ROM. */
@SuppressLint("PrivateApi")
final class RadioVolumeGuard {
    private static final String TAG = "RadioVolumeGuard";
    private static final int RADIO_INPUT_PATH = 0;
    private static final long VENDOR_METHOD_RETRY_MS = 500L;

    private boolean active;
    private Object sourceInfo;
    private Method setInputPathVolume;
    private int lastAppliedGain = -1;
    private long lastVendorMethodAttempt;
    private boolean vendorMethodFailureLogged;

    void setActive(boolean active) {
        boolean becomingActive = active && !this.active;
        this.active = active;
        if (active) {
            // beta6 could leave the vendor input path at zero after pause.
            // Restore normal gain when playback starts, including after an
            // upgrade from a version that attenuated volume step one,
            // even if the cached value already equals the desired value.
            if (becomingActive) {
                lastAppliedGain = -1;
            }
            applyNow();
        }
    }

    void applyNow() {
        if (!active) {
            return;
        }
        int gain = RadioInputGainPolicy.NORMAL_GAIN_PERCENT;
        // SourceInfo path zero is shared with the stock radio. Rewriting the
        // unchanged gain on every service health tick is unnecessary. Leave
        // volume adjustment and mute entirely to the ROM.
        if (RadioInputGainPolicy.shouldWriteGain(gain, lastAppliedGain)) {
            applyGain(gain);
        }
    }

    void reassertNow() {
        if (!active) {
            return;
        }
        // Use only after a known OEM focus/route transition. Invalidating the
        // cache makes this a single write; later health ticks remain read-only.
        lastAppliedGain = -1;
        applyNow();
    }

    void stop() {
        active = false;
    }

    private void applyGain(int gainPercent) {
        if (!ensureVendorMethod()) {
            return;
        }
        try {
            setInputPathVolume.invoke(
                    sourceInfo,
                    RADIO_INPUT_PATH,
                    gainPercent
            );
            lastAppliedGain = gainPercent;
            Log.i(TAG, "FM input gain set to " + gainPercent + "%");
        } catch (ReflectiveOperationException | RuntimeException error) {
            sourceInfo = null;
            setInputPathVolume = null;
            lastAppliedGain = -1;
            lastVendorMethodAttempt = SystemClock.elapsedRealtime();
            Log.w(TAG, "Junsun FM input gain is unavailable", error);
        }
    }

    private boolean ensureVendorMethod() {
        if (sourceInfo != null && setInputPathVolume != null) {
            return true;
        }
        long now = SystemClock.elapsedRealtime();
        if (lastVendorMethodAttempt > 0L
                && now - lastVendorMethodAttempt < VENDOR_METHOD_RETRY_MS) {
            return false;
        }
        lastVendorMethodAttempt = now;
        try {
            Class<?> sourceInfoClass = Class.forName(
                    "android.sourceservice.SourceInfo"
            );
            sourceInfo = sourceInfoClass.getMethod("getInstance").invoke(null);
            setInputPathVolume = sourceInfoClass.getMethod(
                    "setInputPathVolume",
                    int.class,
                    int.class
            );
            boolean available = sourceInfo != null;
            if (available) {
                vendorMethodFailureLogged = false;
            }
            return available;
        } catch (ReflectiveOperationException | RuntimeException error) {
            sourceInfo = null;
            setInputPathVolume = null;
            if (!vendorMethodFailureLogged) {
                Log.i(TAG, "Junsun SourceInfo is not ready; retrying");
                vendorMethodFailureLogged = true;
            }
            return false;
        }
    }
}
