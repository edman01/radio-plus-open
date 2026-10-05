package fi.radioplus.app;

/** Profiles identify inspected APKs, not retail names, CPU models or Android labels. */
enum RadioBackendProfile {
    HCN_CURRENT_31("HCN / 31", false),
    HCN_LEGACY_25("HCN / 25 (experimental)", true),
    UNKNOWN("Unrecognized stock radio", false);

    // Inspected APK fingerprints only; no vendor binaries or implementations are bundled.
    static final String V7_APK_SHA256 =
            "6498fcbc187db80043b4a4eac305bee1d0b95af7634eaf89d8e5a564006e44e9";
    static final String MT8163_APK_SHA256 =
            "e1a505eb5f860382b3aee3e9f664013f92ce939c05942780b73c34c3ac5ce539";

    final String label;
    final boolean experimental;

    RadioBackendProfile(String label, boolean experimental) {
        this.label = label;
        this.experimental = experimental;
    }

    static RadioBackendProfile forApkSha256(String hash) {
        if (V7_APK_SHA256.equalsIgnoreCase(hash)) return HCN_CURRENT_31;
        if (MT8163_APK_SHA256.equalsIgnoreCase(hash)) return HCN_LEGACY_25;
        return UNKNOWN;
    }
}
