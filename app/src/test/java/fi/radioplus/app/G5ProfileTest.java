package fi.radioplus.app;

import java.util.Locale;
import org.junit.Test;
import static org.junit.Assert.*;

/** Identity gates only; matching firmware is not evidence of physical radio operation. */
public final class G5ProfileTest {
    @Test public void inspectedServicePairSelectsSeparateExperimentalMcuProfile() {
        assertEquals("c25dae04c2819cc7e8267b44432928aa4594c131a28562d57cef7148b9262b07",
                RadioBackendProfile.NWD_G5_RADIO_SHA256);
        assertEquals("13dc810da813623bc38c98e1e69f907329da2906983f9f3d547fc4e681948ab1",
                RadioBackendProfile.NWD_G5_KERNEL_SHA256);
        RadioBackendProfile profile = RadioBackendProfile.forApkSha256(
                RadioBackendProfile.NWD_G5_RADIO_SHA256);
        assertEquals(RadioBackendProfile.NWD_G5_242, profile);
        assertTrue(profile.experimental);
        assertTrue(profile.isEnabledForDeviceControl());
        assertTrue(profile.isNwd());
        assertTrue(profile.isNwdMcu());
        assertFalse(profile.isTs());
        assertFalse(profile.isReglink());
        assertEquals("com.nwd.radio.service", profile.stockPackage());
    }

    @Test public void fingerprintsAreCaseInsensitiveButNotPrefixOrNameMatches() {
        String radio = RadioBackendProfile.NWD_G5_RADIO_SHA256;
        String kernel = RadioBackendProfile.NWD_G5_KERNEL_SHA256;
        assertEquals(RadioBackendProfile.NWD_G5_242,
                RadioBackendProfile.forApkSha256(radio.toUpperCase(Locale.ROOT)));
        assertTrue(RadioBackendProfile.verifiedNwdPair(
                radio.toUpperCase(Locale.ROOT), kernel.toUpperCase(Locale.ROOT)));
        for (String wrong : new String[]{null, "", "G5", "Hizpo QS6", "com.nwd.radio.service",
                "2.4.2", "2.6.2", " " + radio, radio + "0", "0".repeat(64)}) {
            assertEquals(RadioBackendProfile.UNKNOWN, RadioBackendProfile.forApkSha256(wrong));
            assertFalse(RadioBackendProfile.verifiedNwdPair(wrong, kernel));
            assertFalse(RadioBackendProfile.verifiedNwdPair(radio, wrong));
        }
    }

    @Test public void olderAndNewerServicePairsCannotBeMixed() {
        String[] radios = {RadioBackendProfile.NWD_RADIO_SHA256,
                RadioBackendProfile.NWD_230_RADIO_SHA256, RadioBackendProfile.NWD_G5_RADIO_SHA256};
        String[] kernels = {RadioBackendProfile.NWD_KERNEL_SHA256,
                RadioBackendProfile.NWD_230_KERNEL_SHA256, RadioBackendProfile.NWD_G5_KERNEL_SHA256};
        for (int radio = 0; radio < radios.length; radio++) {
            for (int kernel = 0; kernel < kernels.length; kernel++) {
                assertEquals("Only an inspected matching pair may be recognized",
                        radio == kernel, RadioBackendProfile.verifiedNwdPair(radios[radio], kernels[kernel]));
            }
        }
    }

    @Test public void stockUiAndKernelAloneNeverAuthorizeTunerCommands() {
        for (String nonService : new String[]{
                "d03e19eb9f6c31151b0b8417795672844ae4fdeb52ab5af8c299a0d132f218ea",
                "e62b75e1d5b24084bf3d59d70734040255d52f7fb218dc7b95655eff6e2de79d",
                RadioBackendProfile.NWD_G5_KERNEL_SHA256}) {
            assertEquals(RadioBackendProfile.UNKNOWN, RadioBackendProfile.forApkSha256(nonService));
            assertFalse(RadioBackendProfile.verifiedNwdPair(nonService,
                    RadioBackendProfile.NWD_G5_KERNEL_SHA256));
        }
        assertFalse(RadioBackendProfile.verifiedNwdPair(
                RadioBackendProfile.NWD_G5_RADIO_SHA256, RadioBackendProfile.NWD_G5_RADIO_SHA256));
    }

    @Test public void mcuClassificationDoesNotEnableLegacyOrUnknownFamilies() {
        assertTrue(RadioBackendProfile.NWD_230.isNwdMcu());
        assertTrue(RadioBackendProfile.NWD_G5_242.isNwdMcu());
        for (RadioBackendProfile profile : RadioBackendProfile.values()) {
            if (profile != RadioBackendProfile.NWD_230 && profile != RadioBackendProfile.NWD_G5_242) {
                assertFalse(profile.isNwdMcu());
            }
        }
        assertFalse(RadioBackendProfile.NWD_222.isEnabledForDeviceControl());
        assertFalse(RadioBackendProfile.REGLINK_S540.isEnabledForDeviceControl());
        assertFalse(RadioBackendProfile.UNKNOWN.isEnabledForDeviceControl());
    }
}
