package fi.radioplus.app;

/** Normal input gain only; all user volume steps and mute belong to the ROM. */
final class RadioInputGainPolicy {
    static final int NORMAL_GAIN_PERCENT = 100;

    private RadioInputGainPolicy() { }

    static boolean shouldWriteGain(int desiredGain, int lastAppliedGain) {
        return desiredGain != lastAppliedGain;
    }
}
