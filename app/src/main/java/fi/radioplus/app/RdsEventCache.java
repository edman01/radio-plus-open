package fi.radioplus.app;

/**
 * Keeps the asynchronous RDS string events tied to the frequency on which
 * they were received.
 *
 * Some Junsun ROM builds update RDS PS/RT only through RadioPlayer string
 * events. Their getRadioInfo() snapshot can therefore keep an empty PS name
 * even while the stock radio UI already shows it.
 */
final class RdsEventCache {
    static final int EVENT_RDS_STATE = 4;
    static final int EVENT_PS_MESSAGE = 38;
    static final int EVENT_RT_MESSAGE = 41;

    private int frequency = -1;
    private String stationName = "";
    private String radioText = "";
    private boolean rdsAvailable;

    synchronized void onFrequencyObserved(int observedFrequency) {
        if (frequency == observedFrequency) {
            return;
        }
        frequency = observedFrequency;
        stationName = "";
        radioText = "";
        rdsAvailable = false;
    }

    synchronized void record(int event, String value, int eventFrequency) {
        if (eventFrequency <= 0) {
            return;
        }
        onFrequencyObserved(eventFrequency);
        String cleanValue = RadioMetadataReader.clean(value);
        if (event == EVENT_PS_MESSAGE) {
            stationName = cleanValue;
            rdsAvailable = rdsAvailable || !stationName.isEmpty();
        } else if (event == EVENT_RT_MESSAGE) {
            radioText = RdsTextQuality.preferMoreComplete(radioText, cleanValue);
            rdsAvailable = rdsAvailable || !radioText.isEmpty();
        } else if (event == EVENT_RDS_STATE) {
            try {
                rdsAvailable = (Integer.parseInt(cleanValue) & 4) != 0;
            } catch (NumberFormatException ignored) {
                rdsAvailable = false;
            }
            if (!rdsAvailable) {
                stationName = "";
                radioText = "";
            }
        }
    }

    synchronized void recordSnapshot(
            int eventFrequency,
            String stationNameValue,
            String radioTextValue,
            String rdsStateValue
    ) {
        if (eventFrequency <= 0) {
            return;
        }
        onFrequencyObserved(eventFrequency);

        String cleanState = RadioMetadataReader.clean(rdsStateValue);
        if (!cleanState.isEmpty()) {
            try {
                rdsAvailable = (Integer.parseInt(cleanState) & 4) != 0;
            } catch (NumberFormatException ignored) {
                // An unknown vendor state must not discard otherwise valid
                // PS/RT data carried by the same RadioInfo snapshot.
            }
            if (!rdsAvailable) {
                stationName = "";
                radioText = "";
            }
        }

        String cleanStationName = RadioMetadataReader.clean(stationNameValue);
        if (!cleanStationName.isEmpty()) {
            stationName = cleanStationName;
            rdsAvailable = true;
        }

        String cleanRadioText = RadioMetadataReader.clean(radioTextValue);
        if (!cleanRadioText.isEmpty()) {
            radioText = RdsTextQuality.preferMoreComplete(radioText, cleanRadioText);
            rdsAvailable = true;
        }
    }

    synchronized String stationName(int requestedFrequency) {
        return requestedFrequency == frequency ? stationName : "";
    }

    synchronized String radioText(int requestedFrequency) {
        return requestedFrequency == frequency ? radioText : "";
    }

    synchronized boolean rdsAvailable(int requestedFrequency) {
        return requestedFrequency == frequency && rdsAvailable;
    }

    synchronized void clear() {
        frequency = -1;
        stationName = "";
        radioText = "";
        rdsAvailable = false;
    }
}
