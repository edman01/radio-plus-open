package fi.radioplus.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Intent;

import org.junit.Test;

public final class AutoStartLaunchPolicyTest {
    @Test
    public void acceptsAndroidAndJunsunStartupSignals() {
        assertTrue(AutoStartLaunchPolicy.isStartupAction(Intent.ACTION_BOOT_COMPLETED));
        assertTrue(AutoStartLaunchPolicy.isStartupAction(
                "android.intent.action.QUICKBOOT_POWERON"
        ));
        assertTrue(AutoStartLaunchPolicy.isStartupAction("com.hcn.action.ACC_ON"));
        assertFalse(AutoStartLaunchPolicy.isStartupAction(Intent.ACTION_SCREEN_ON));
        assertFalse(AutoStartLaunchPolicy.isStartupAction(null));
    }

    @Test
    public void screenWakeRequiresARealSleepInterval() {
        long offAt = 10_000L;
        assertFalse(AutoStartLaunchPolicy.isWakeAfterSleep(offAt, 12_999L));
        assertTrue(AutoStartLaunchPolicy.isWakeAfterSleep(offAt, 13_000L));
        assertFalse(AutoStartLaunchPolicy.isWakeAfterSleep(-1L, 20_000L));
    }

    @Test
    public void duplicateStartupSignalsAreThrottled() {
        assertTrue(AutoStartLaunchPolicy.isOutsideLaunchCooldown(0L, 1_000L));
        assertFalse(AutoStartLaunchPolicy.isOutsideLaunchCooldown(10_000L, 14_999L));
        assertTrue(AutoStartLaunchPolicy.isOutsideLaunchCooldown(10_000L, 15_000L));
        assertTrue(AutoStartLaunchPolicy.isOutsideLaunchCooldown(10_000L, 100L));
    }
}
