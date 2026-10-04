package fi.radioplus.app;

import android.content.Intent;

final class AutoStartLaunchPolicy {
    static final long MIN_SCREEN_OFF_MS = 3_000L;
    static final long LAUNCH_COOLDOWN_MS = 5_000L;

    private AutoStartLaunchPolicy() {
    }

    static boolean isStartupAction(String action) {
        return Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.hcn.action.ACC_ON".equals(action)
                || "com.hcn.action.ACCON".equals(action)
                || "com.hcn.intent.action.ACC_ON".equals(action)
                || "com.hcn.intent.action.ACCON".equals(action);
    }

    static boolean isWakeAfterSleep(long screenOffAt, long screenOnAt) {
        return screenOffAt > 0L
                && screenOnAt >= screenOffAt
                && screenOnAt - screenOffAt >= MIN_SCREEN_OFF_MS;
    }

    static boolean isOutsideLaunchCooldown(long previousLaunchAt, long now) {
        return previousLaunchAt <= 0L
                || now < previousLaunchAt
                || now - previousLaunchAt >= LAUNCH_COOLDOWN_MS;
    }
}
