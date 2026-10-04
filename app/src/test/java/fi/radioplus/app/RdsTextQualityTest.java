package fi.radioplus.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RdsTextQualityTest {
    @Test
    public void recognizesTextWithMissingMiddleCharactersAsIncomplete() {
        assertTrue(RdsTextQuality.isLikelyIncompleteVersion(
                "Radio Nova paras sekoitus klassikoita",
                "Radio Nova paras sek assikoita"
        ));
    }

    @Test
    public void keepsCompleteTextWhenNextDecodeLosesCharacters() {
        assertEquals(
                "BEHM - Frida",
                RdsTextQuality.preferMoreComplete("BEHM - Frida", "BEH - Frida")
        );
    }

    @Test
    public void unrelatedTrackTitleIsNotClassifiedAsIncomplete() {
        assertFalse(RdsTextQuality.isLikelyIncompleteVersion(
                "BEHM - Frida",
                "KUUMAA - Ylivoimainen"
        ));
        assertEquals(
                "KUUMAA - Ylivoimainen",
                RdsTextQuality.preferMoreComplete(
                        "BEHM - Frida",
                        "KUUMAA - Ylivoimainen"
                )
        );
    }
}
