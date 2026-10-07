package fi.radioplus.app;

/** Profiles identify inspected APKs, not retail names, CPU models or Android labels. */
enum RadioBackendProfile {
    HCN_CURRENT_31("HCN / 31", false),
    HCN_LEGACY_25("HCN / 25 (experimental)", true),
    TS_AC8259_V115("TS / AC8259 V115 (experimental)", true),
    TS_825X_V27("TS / 825X UI02 V27 (experimental)", true),
    TS_8667Q_V23("TS / 8667Q UI02 V23 (experimental)", true),
    NWD_222("NWD / RadioService 2.2.2 (experimental)", true),
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
        return UNKNOWN;
    }

    String stockPackage() {
        if (this == NWD_222) return "com.nwd.radio.service";
        return isTs() ? "com.ts.MainUI" : RadioBackendContract.PACKAGE_NAME;
    }

    static boolean verifiedNwdPair(String radioHash, String kernelHash) {
        return NWD_RADIO_SHA256.equalsIgnoreCase(radioHash)
                && NWD_KERNEL_SHA256.equalsIgnoreCase(kernelHash);
    }

    boolean isTs() {
        return this == TS_AC8259_V115 || this == TS_825X_V27 || this == TS_8667Q_V23;
    }
}
