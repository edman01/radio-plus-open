package fi.radioplus.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class BandUiTest {
    @Test
    public void fmBanksUseSingleFmLabel() {
        assertEquals("FM", BandUi.labelForBand(0));
        assertEquals("FM", BandUi.labelForBand(1));
        assertEquals("FM", BandUi.labelForBand(2));
    }

    @Test
    public void amBankUsesSingleAmLabel() {
        assertEquals("AM", BandUi.labelForBand(3));
    }
}
