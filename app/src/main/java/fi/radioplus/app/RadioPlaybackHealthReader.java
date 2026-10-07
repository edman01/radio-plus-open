package fi.radioplus.app;

import android.annotation.SuppressLint;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Bridge to the Junsun framework signals needed to distinguish a dropped
 * radio route from an intentional source change and to silence the analog
 * tuner for an explicit user pause.
 */
@SuppressLint("PrivateApi")
final class RadioPlaybackHealthReader {
    private static final String TAG = "RadioPlaybackHealth";
    private static final long INITIALIZATION_RETRY_MS = 5000L;

    static final class Snapshot {
        final boolean sourceKnown;
        final String sourcePackage;
        final boolean muteKnown;
        final boolean muted;

        Snapshot(
                boolean sourceKnown,
                String sourcePackage,
                boolean muteKnown,
                boolean muted
        ) {
            this.sourceKnown = sourceKnown;
            this.sourcePackage = sourcePackage == null ? "" : sourcePackage.trim();
            this.muteKnown = muteKnown;
            this.muted = muted;
        }

        boolean radioOwnsSource() {
            return RadioBackendContract.PACKAGE_NAME.equals(sourcePackage)
                    || sourcePackage.startsWith(RadioBackendContract.PACKAGE_NAME + "/")
                    || "com.ts.MainUI".equals(sourcePackage)
                    || "com.nwd.radio.service".equals(sourcePackage);
        }
    }

    private boolean sourceInitialized;
    private boolean sourceAvailable;
    private boolean sourceFailureLogged;
    private long lastSourceInitializationAttempt;
    private boolean playerInitialized;
    private boolean playerAvailable;
    private boolean playerFailureLogged;
    private long lastPlayerInitializationAttempt;
    private Object sourceInfo;
    private Object radioPlayer;
    private Method getSourcePackage;
    private Method getRadioMute;
    private Method setRadioMute;
    private Method setHardwareRadioMute;

    synchronized Snapshot read() {
        boolean sourceKnown = false;
        String sourcePackage = "";
        boolean muteKnown = false;
        boolean muted = false;
        if (ensureSourceInitialized()) {
            try {
                Object value = getSourcePackage.invoke(sourceInfo);
                sourcePackage = value == null ? "" : String.valueOf(value);
                sourceKnown = !sourcePackage.trim().isEmpty();
            } catch (ReflectiveOperationException | RuntimeException error) {
                invalidateSource("Junsun source state read failed", error);
            }
        }
        if (ensurePlayerInitialized() && getRadioMute != null) {
            try {
                Object value = getRadioMute.invoke(radioPlayer);
                if (value instanceof Boolean) {
                    muteKnown = true;
                    muted = (Boolean) value;
                }
            } catch (ReflectiveOperationException | RuntimeException error) {
                // Source ownership is still useful even on ROM builds that do
                // not expose the mute getter correctly.
                if (!playerFailureLogged) {
                    playerFailureLogged = true;
                    Log.w(TAG, "Junsun radio mute state read failed", error);
                }
            }
        }
        return new Snapshot(sourceKnown, sourcePackage, muteKnown, muted);
    }

    synchronized boolean setMuted(boolean muted) {
        // Muting the tuner must not depend on SourceInfo. Some Android 13 V7
        // ROMs hide SourceInfo from third-party packages even though their
        // RadioPlayer bridge is available. The old all-or-nothing
        // initialization silently skipped both setMute calls on those units.
        if (!ensurePlayerInitialized()) {
            return false;
        }
        boolean commandSent = false;
        if (setRadioMute != null) {
            commandSent |= invokeCommand(
                    setRadioMute,
                    radioPlayer,
                    muted,
                    "setMute"
            );
        }
        // V7 framework exposes both calls. Some tuner implementations use
        // setMute(), while others only silence the hardware path through
        // setRadioMute(). Sending both is idempotent.
        if (setHardwareRadioMute != null) {
            commandSent |= invokeCommand(
                    setHardwareRadioMute,
                    radioPlayer,
                    muted,
                    "setRadioMute"
            );
        }
        return commandSent;
    }

    private boolean invokeCommand(
            Method method,
            Object target,
            Object argument,
            String name
    ) {
        return invokeCommand(method, target, new Object[]{argument}, name);
    }

    private boolean invokeCommand(
            Method method,
            Object target,
            Object[] arguments,
            String name
    ) {
        try {
            method.invoke(target, arguments);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "Junsun " + name + " command failed", error);
            return false;
        }
    }

    private boolean ensureSourceInitialized() {
        if (sourceAvailable) {
            return true;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (sourceInitialized
                && now - lastSourceInitializationAttempt < INITIALIZATION_RETRY_MS) {
            return false;
        }
        sourceInitialized = true;
        lastSourceInitializationAttempt = now;
        try {
            Class<?> sourceClass = Class.forName("android.sourceservice.SourceInfo");
            Method sourceFactory = sourceClass.getMethod("getInstance");
            getSourcePackage = sourceClass.getMethod("getSourcePackage");
            sourceInfo = sourceFactory.invoke(null);
            sourceAvailable = sourceInfo != null;
            if (sourceAvailable) {
                sourceFailureLogged = false;
                Log.i(TAG, "Junsun radio source watchdog available");
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            invalidateSource("Junsun radio source watchdog unavailable", error);
        }
        return sourceAvailable;
    }

    private boolean ensurePlayerInitialized() {
        if (playerAvailable) {
            return true;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (playerInitialized
                && now - lastPlayerInitializationAttempt < INITIALIZATION_RETRY_MS) {
            return false;
        }
        playerInitialized = true;
        lastPlayerInitializationAttempt = now;
        try {
            Class<?> playerClass = Class.forName("android.radio.RadioPlayer");
            Method playerFactory = playerClass.getMethod("getRadioPlayer");
            configurePlayer(playerFactory.invoke(null));
            if (playerAvailable) {
                playerFailureLogged = false;
                Log.i(TAG, "Junsun radio mute bridge available");
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            invalidatePlayer("Junsun radio mute bridge unavailable", error);
        }
        return playerAvailable;
    }

    private void configurePlayer(Object player) {
        radioPlayer = player;
        Class<?> type = player == null ? null : player.getClass();
        // The inspected MT8163 framework provides setMute but no getRadioMute.
        // Absence of a health getter must not disable explicit pause/resume.
        getRadioMute = optionalMethod(type, "getRadioMute");
        setRadioMute = optionalMethod(type, "setMute", boolean.class);
        setHardwareRadioMute = optionalMethod(type, "setRadioMute", boolean.class);
        playerAvailable = player != null
                && (getRadioMute != null || setRadioMute != null || setHardwareRadioMute != null);
    }

    private static Method optionalMethod(Class<?> type, String name, Class<?>... arguments) {
        if (type == null) return null;
        try { return type.getMethod(name, arguments); }
        catch (NoSuchMethodException ignored) { return null; }
    }

    private void invalidateSource(String message, Throwable error) {
        sourceAvailable = false;
        sourceInfo = null;
        getSourcePackage = null;
        if (!sourceFailureLogged) {
            sourceFailureLogged = true;
            Log.w(TAG, message, error);
        }
    }

    private void invalidatePlayer(String message, Throwable error) {
        playerAvailable = false;
        radioPlayer = null;
        getRadioMute = null;
        setRadioMute = null;
        setHardwareRadioMute = null;
        if (!playerFailureLogged) {
            playerFailureLogged = true;
            Log.w(TAG, message, error);
        }
    }
}
