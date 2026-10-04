package fi.radioplus.app;

import android.annotation.SuppressLint;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.regex.Pattern;

/**
 * Narrow bridge to the vendor API that exists in the Junsun boot class path.
 *
 * The public FMPlugService AIDL exposes RDS PS but not RDS RadioText, preset
 * enumeration or direct FM1/FM2/FM3 selection. Most calls are read-only. The
 * direct tune fallback is used only when the public band command cannot reach
 * the requested OEM band.
 */
@SuppressLint("PrivateApi")
final class RadioMetadataReader {
    private static final String TAG = "JunsunRadioMetadata";
    private static final String[] BAND_NAMES = {"FM1", "FM2", "FM3", "AM1"};
    private static final long INITIALIZATION_RETRY_MS = 2500L;
    private static final int MAX_METADATA_CODE_POINTS = 512;
    private static final Pattern CONTROL_PATTERN = Pattern.compile("\\p{Cntrl}");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    static final class Metadata {
        final int frequency;
        final String stationName;
        final String radioText;
        final String programType;
        final boolean rdsAvailable;

        Metadata(
                int frequency,
                String stationName,
                String radioText,
                String programType,
                boolean rdsAvailable
        ) {
            this.frequency = frequency;
            this.stationName = clean(stationName);
            this.radioText = clean(radioText);
            this.programType = clean(programType);
            this.rdsAvailable = rdsAvailable
                    || !this.stationName.isEmpty()
                    || !this.radioText.isEmpty();
        }
    }

    private boolean initialized;
    private boolean available;
    private boolean failureLogged;
    private long lastInitializationAttempt;
    private Object radioPlayer;
    private Method getRadioInfo;
    private Method getPreset;
    private Method setUibandIndexFreq;
    private Method setOnEventListener;
    private Class<?> bandClass;
    private Field frequencyField;
    private Field stationNameField;
    private Field radioTextField;
    private Field programTypeField;
    private Field rdsStateField;
    private Object radioEventListener;
    private Class<?> eventListenerClass;
    private long lastEventListenerAttempt;
    private boolean eventListenerFailureLogged;
    private final RdsEventCache eventCache = new RdsEventCache();

    synchronized Metadata read() {
        if (!ensureInitialized()) {
            return null;
        }
        try {
            Object info = getRadioInfo.invoke(radioPlayer);
            if (info == null) {
                return null;
            }
            int frequency = frequencyField.getInt(info);
            eventCache.onFrequencyObserved(frequency);
            String rdsState = stringValue(rdsStateField.get(info));
            String snapshotStationName = stringValue(stationNameField.get(info));
            String snapshotRadioText = stringValue(radioTextField.get(info));
            String eventStationName = eventCache.stationName(frequency);
            String eventRadioText = eventCache.radioText(frequency);
            return new Metadata(
                    frequency,
                    eventStationName.isEmpty()
                            ? snapshotStationName
                            : eventStationName,
                    eventRadioText.isEmpty()
                            ? snapshotRadioText
                            : eventRadioText,
                    stringValue(programTypeField.get(info)),
                    rdsEnabled(rdsState) || eventCache.rdsAvailable(frequency)
            );
        } catch (ReflectiveOperationException | RuntimeException error) {
            logFailure("RDS-metatietojen lukeminen epäonnistui", error);
            invalidate();
            return null;
        }
    }

    synchronized int[] readPresets(int band) {
        if (!ensureInitialized() || band < 0 || band >= BAND_NAMES.length) {
            return new int[0];
        }
        try {
            @SuppressWarnings({"rawtypes", "unchecked"})
            Object bandValue = Enum.valueOf((Class<? extends Enum>) bandClass, BAND_NAMES[band]);
            Object result = getPreset.invoke(radioPlayer, bandValue);
            if (result instanceof int[]) {
                return ((int[]) result).clone();
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            logFailure("Automaattihaun asemalistan lukeminen epäonnistui", error);
            invalidate();
        }
        return new int[0];
    }

    synchronized boolean tuneToBand(int band, int frequency) {
        if (!ensureInitialized()
                || setUibandIndexFreq == null
                || band < 0
                || band >= BAND_NAMES.length) {
            return false;
        }
        try {
            @SuppressWarnings({"rawtypes", "unchecked"})
            Object bandValue = Enum.valueOf((Class<? extends Enum>) bandClass, BAND_NAMES[band]);
            setUibandIndexFreq.invoke(radioPlayer, bandValue, -1, frequency);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            logFailure("Suora kaistan viritys epäonnistui", error);
            invalidate();
            return false;
        }
    }

    synchronized boolean isAvailable() {
        return ensureInitialized();
    }

    private synchronized boolean ensureInitialized() {
        if (available) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (radioEventListener == null
                    && eventListenerClass != null
                    && now - lastEventListenerAttempt >= INITIALIZATION_RETRY_MS) {
                registerEventListener(eventListenerClass);
            }
            return true;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (initialized && now - lastInitializationAttempt < INITIALIZATION_RETRY_MS) {
            return false;
        }
        initialized = true;
        lastInitializationAttempt = now;
        try {
            Class<?> playerClass = Class.forName("android.radio.RadioPlayer");
            Class<?> infoClass = Class.forName("android.radio.RadioInfo");
            bandClass = Class.forName("android.radio.RadioPlayer$BAND");

            Method factory = playerClass.getMethod("getRadioPlayer");
            getRadioInfo = playerClass.getMethod("getRadioInfo");
            getPreset = playerClass.getMethod("getPreset", bandClass);
            Class<?> listenerClass = Class.forName(
                    "android.radio.RadioPlayer$OnEventListener"
            );
            eventListenerClass = listenerClass;
            setOnEventListener = playerClass.getMethod(
                    "setOnEventListener",
                    listenerClass
            );
            try {
                setUibandIndexFreq = playerClass.getMethod(
                        "setUibandIndexFreq",
                        bandClass,
                        int.class,
                        int.class
                );
            } catch (NoSuchMethodException ignored) {
                setUibandIndexFreq = null;
            }
            frequencyField = infoClass.getField("mFreq");
            stationNameField = infoClass.getField("mPSname");
            radioTextField = infoClass.getField("mRTtype");
            programTypeField = infoClass.getField("mPTYtype");
            rdsStateField = infoClass.getField("mRDSstate");
            radioPlayer = factory.invoke(null);
            available = radioPlayer != null;
            if (available) {
                registerEventListener(listenerClass);
                failureLogged = false;
                Log.i(TAG, "Junsun RadioPlayer -metatietosilta käytettävissä");
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            available = false;
            logFailure("Junsun RadioPlayer -rajapintaa ei ole tässä laitteessa", error);
        }
        return available;
    }

    private synchronized void invalidate() {
        available = false;
        radioPlayer = null;
        radioEventListener = null;
        eventListenerClass = null;
        eventCache.clear();
    }

    private void registerEventListener(Class<?> listenerClass) {
        lastEventListenerAttempt = android.os.SystemClock.elapsedRealtime();
        try {
            radioEventListener = Proxy.newProxyInstance(
                    listenerClass.getClassLoader(),
                    new Class<?>[]{listenerClass},
                    (proxy, method, arguments) -> {
                        if ("onEvent".equals(method.getName())
                                && arguments != null
                                && arguments.length == 2
                                && arguments[0] instanceof Integer) {
                            if (arguments[1] instanceof String) {
                                recordStringEvent(
                                        (Integer) arguments[0],
                                        (String) arguments[1]
                                );
                            } else if (arguments[1] != null) {
                                recordRadioInfoEvent(arguments[1]);
                            }
                        }
                        return null;
                    }
            );
            setOnEventListener.invoke(radioPlayer, radioEventListener);
            eventListenerFailureLogged = false;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            radioEventListener = null;
            if (!eventListenerFailureLogged) {
                eventListenerFailureLogged = true;
                Log.w(
                        TAG,
                        "Asynkronista RDS-tapahtumavirtaa ei voitu avata",
                        error
                );
            }
        }
    }

    private synchronized void recordStringEvent(int event, String value) {
        if (radioPlayer == null || getRadioInfo == null || frequencyField == null) {
            return;
        }
        try {
            Object info = getRadioInfo.invoke(radioPlayer);
            if (info == null) {
                return;
            }
            eventCache.record(
                    event,
                    value,
                    frequencyField.getInt(info)
            );
        } catch (ReflectiveOperationException | RuntimeException error) {
            if (!eventListenerFailureLogged) {
                eventListenerFailureLogged = true;
                Log.w(TAG, "RDS-tapahtuman taajuutta ei voitu lukea", error);
            }
        }
    }

    private synchronized void recordRadioInfoEvent(Object info) {
        if (frequencyField == null
                || stationNameField == null
                || radioTextField == null
                || rdsStateField == null) {
            return;
        }
        try {
            eventCache.recordSnapshot(
                    frequencyField.getInt(info),
                    stringValue(stationNameField.get(info)),
                    stringValue(radioTextField.get(info)),
                    stringValue(rdsStateField.get(info))
            );
        } catch (ReflectiveOperationException | RuntimeException error) {
            if (!eventListenerFailureLogged) {
                eventListenerFailureLogged = true;
                Log.w(TAG, "RadioInfo-RDS-tapahtumaa ei voitu lukea", error);
            }
        }
    }

    private boolean rdsEnabled(String state) {
        try {
            return (Integer.parseInt(state) & 4) != 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private void logFailure(String message, Throwable error) {
        if (!failureLogged) {
            failureLogged = true;
            Log.w(TAG, message, error);
        }
    }

    static String clean(String value) {
        if (value == null) {
            return "";
        }
        String normalized = CONTROL_PATTERN.matcher(value.replace('\u0000', ' '))
                .replaceAll(" ");
        normalized = WHITESPACE_PATTERN.matcher(normalized)
                .replaceAll(" ")
                .trim();
        if ("none".equalsIgnoreCase(normalized)
                || "null".equalsIgnoreCase(normalized)
                || "--".equals(normalized)) {
            return "";
        }
        int count = normalized.codePointCount(0, normalized.length());
        if (count <= MAX_METADATA_CODE_POINTS) {
            return normalized;
        }
        return normalized.substring(
                0,
                normalized.offsetByCodePoints(0, MAX_METADATA_CODE_POINTS)
        );
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
