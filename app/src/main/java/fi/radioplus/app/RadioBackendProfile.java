package fi.radioplus.app;

/** Profiles identify inspected APKs, not retail names, CPU models or Android labels. */
enum RadioBackendProfile {
    HCN_CURRENT_31("HCN / 31", false),
    HCN_LEGACY_25("HCN / 25 (experimental)", true),
    TS_AC8259_V115("TS / AC8259 V115 (experimental)", true),
    TS_825X_V27("TS / 825X UI02 V27 (experimental)", true),
    TS_8667Q_V23("TS / 8667Q UI02 V23 (experimental)", true),
    NWD_222("NWD / RadioService 2.2.2 (experimental)", true),
    NWD_230("K4811 / MCU RadioService 2.3.0 (experimental)", true),
    NWD_G5_242("G5 / MCU RadioService 2.4.2 (experimental)", true),
    REGLINK_S540("Reglink / S5.40 (control unavailable)", true),
    UNKNOWN("Unrecognized stock radio", false);

    // Inspected APK fingerprints only; no vendor binaries or implementations are bundled.
    static final String V7_APK_SHA256 =
            "6498fcbc187db80043b4a4eac305bee1d0b95af7634eaf89d8e5a564006e44e9";
    static final String MT8163_APK_SHA256 =
            "e1a505eb5f860382b3aee3e9f664013f92ce939c05942780b73c34c3ac5ce539";
    static final String AC8259_APK_SHA256 =
            "0c9af15504740594734b21e472abb9edbd6875feb50e1b98a8e97e1b6cbbc4cf";
    static final String TS_825X_APK_SHA256 =
            "ad6bb86af9ec4b660e2edf38febc51ed04b4c9865aea28f841cace01fdcc6abc";
    static final String TS_8667Q_APK_SHA256 =
            "9b164f151e7d74ac1b430372a7b75533a43753298d2ac1aaef145df50dfd7918";
    static final String NWD_RADIO_SHA256 =
            "0eb6df5e6b090c45895369bdee7112f2ec83042f8d5e37fc5e701ee24e0e7842";
    static final String NWD_KERNEL_SHA256 =
            "e2de2b8da9bfe96a308e38a86fac75d978b7496a795881b8a921fa930b4d8b47";
    static final String NWD_230_RADIO_SHA256 =
            "0bd82481535166987f37d1d876d2d697393afcef3b46f979ec1a60209ac1fcf8";
    static final String NWD_230_KERNEL_SHA256 =
            "429685bf6410ae04f6410a62a5c03be2fb57466a0d5d9615f522d1000da6280b";
    static final String NWD_G5_RADIO_SHA256 =
            "c25dae04c2819cc7e8267b44432928aa4594c131a28562d57cef7148b9262b07";
    static final String NWD_G5_KERNEL_SHA256 =
            "13dc810da813623bc38c98e1e69f907329da2906983f9f3d547fc4e681948ab1";
    static final String REGLINK_S540_SERVICE_SHA256 =
            "f66aac7196c1256888e46c54e16d50b3c3b5680a020af367a16bea60896a1a9c";
    static final String REGLINK_S540_RADIO_SHA256 =
            "3ec4895e2d1a8145f53c53b984f2cfb0e22e34d82f2745cc4fc54e10802a64a9";
    static final String REGLINK_S540_TUNER_SHA256 =
            "f9bba4d7785a5197dc8097a581ba09423d18af0db7801d3ffc5762e7cf020cb8";

    final String label;
    final boolean experimental;

    RadioBackendProfile(String label, boolean experimental) {
        this.label = label;
        this.experimental = experimental;
    }

    static RadioBackendProfile forApkSha256(String hash) {
        if (V7_APK_SHA256.equalsIgnoreCase(hash)) return HCN_CURRENT_31;
        if (MT8163_APK_SHA256.equalsIgnoreCase(hash)) return HCN_LEGACY_25;
        if (AC8259_APK_SHA256.equalsIgnoreCase(hash)) return TS_AC8259_V115;
        if (TS_825X_APK_SHA256.equalsIgnoreCase(hash)) return TS_825X_V27;
        if (TS_8667Q_APK_SHA256.equalsIgnoreCase(hash)) return TS_8667Q_V23;
        if (NWD_RADIO_SHA256.equalsIgnoreCase(hash)) return NWD_222;
        if (NWD_230_RADIO_SHA256.equalsIgnoreCase(hash)) return NWD_230;
        if (NWD_G5_RADIO_SHA256.equalsIgnoreCase(hash)) return NWD_G5_242;
        if (REGLINK_S540_SERVICE_SHA256.equalsIgnoreCase(hash)) return REGLINK_S540;
        return UNKNOWN;
    }

    String stockPackage() {
        if (isReglink()) return "com.reglink.services";
        if (isNwd()) return "com.nwd.radio.service";
        return isTs() ? "com.ts.MainUI" : RadioBackendContract.PACKAGE_NAME;
    }

    static boolean verifiedNwdPair(String radioHash, String kernelHash) {
        return (NWD_RADIO_SHA256.equalsIgnoreCase(radioHash)
                && NWD_KERNEL_SHA256.equalsIgnoreCase(kernelHash))
                || (NWD_230_RADIO_SHA256.equalsIgnoreCase(radioHash)
                && NWD_230_KERNEL_SHA256.equalsIgnoreCase(kernelHash))
                || (NWD_G5_RADIO_SHA256.equalsIgnoreCase(radioHash)
                && NWD_G5_KERNEL_SHA256.equalsIgnoreCase(kernelHash));
    }

    boolean isNwd() { return this == NWD_222 || isNwdMcu(); }

    // These independently inspected service/kernel pairs share the type-0 MCU
    // contract: one-way tuning and source-only audio. Their ARM/AW implementations
    // have different lifecycle requirements and are rejected by NwdRadioApi.
    boolean isNwdMcu() { return this == NWD_230 || this == NWD_G5_242; }
    boolean isReglink() { return this == REGLINK_S540; }

    static boolean verifiedReglinkTriplet(String services, String radio, String tuner) {
        return REGLINK_S540_SERVICE_SHA256.equalsIgnoreCase(services)
                && REGLINK_S540_RADIO_SHA256.equalsIgnoreCase(radio)
                && REGLINK_S540_TUNER_SHA256.equalsIgnoreCase(tuner);
    }

    boolean isEnabledForDeviceControl() {
        // An inspected ABI is not proof of safe audio/key ownership. Reglink's
        // stock teardown can mute another client; its native FM path also
        // requires the stock "radio" client name. Do not claim that singleton.
        return this != UNKNOWN && this != NWD_222 && !isReglink();
    }

    boolean isTs() {
        return this == TS_AC8259_V115 || this == TS_825X_V27 || this == TS_8667Q_V23;
    }
}
