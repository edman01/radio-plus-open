package fi.radioplus.app;

import android.content.ComponentName;
import android.content.Intent;

/**
 * Identifies the stock radio service already present in the Junsun ROM.
 *
 * Radio+ only binds its exported tuner API. It does not launch, replace or
 * modify the stock radio user interface. Keeping the service in its original
 * package is important because the ROM's analog FM audio route is owned by
 * that process.
 */
final class RadioBackendContract {
    static final String PACKAGE_NAME = "com.hcn.autoradio";
    static final String SERVICE_CLASS =
            "com.hcn.autoradio.service.FMPlugService";
    static final String SERVICE_ACTION =
            "com.hcn.autoradio.FM_PLUG_SERVICE";
    static final ComponentName SERVICE_COMPONENT = new ComponentName(
            PACKAGE_NAME,
            SERVICE_CLASS
    );

    private RadioBackendContract() {
    }

    static Intent serviceIntent(RadioBackendProfile profile) {
        if (profile.isNwd()) {
            return new Intent("com.nwd.radio.service.ACTION_RADIO_SERVICE").setComponent(
                    new ComponentName("com.nwd.radio.service", "com.nwd.radio.service.RadioService"));
        }
        if (profile.isTs()) {
            return new Intent("android.intent.action.MAIN_UI").setComponent(
                    new ComponentName("com.ts.MainUI", "com.ts.main.common.MainUI"));
        }
        if (profile == RadioBackendProfile.HCN_CURRENT_31
                || profile == RadioBackendProfile.HCN_LEGACY_25) {
            return new Intent(SERVICE_ACTION).setComponent(SERVICE_COMPONENT);
        }
        throw new IllegalArgumentException("Unrecognized radio endpoint");
    }
}
