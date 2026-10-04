package fi.radioplus.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RadioStateTest {

    @Test
    public void identicalStateCanBeDeduplicated() {
        RadioServiceClient.RadioState first = state(0, 98_100, "Suomipop");
        RadioServiceClient.RadioState second = state(0, 98_100, "Suomipop");

        assertTrue(first.hasSameContent(second));
        assertTrue(second.hasSameContent(first));
    }

    @Test
    public void bandFrequencyAndMetadataChangesAreNotDeduplicated() {
        RadioServiceClient.RadioState original = state(0, 98_100, "Suomipop");

        assertFalse(original.hasSameContent(state(1, 98_100, "Suomipop")));
        assertFalse(original.hasSameContent(state(0, 98_200, "Suomipop")));
        assertFalse(original.hasSameContent(state(0, 98_100, "YleX")));
        assertFalse(original.hasSameContent(null));
    }

    @Test
    public void metadataCleaningRemovesControlsAndBoundsPayload() {
        assertTrue(RadioMetadataReader.clean("none").isEmpty());
        assertTrue(RadioMetadataReader.clean("--").isEmpty());
        assertTrue(RadioMetadataReader.clean("\u0000 \n").isEmpty());

        String clean = RadioMetadataReader.clean("  Artist\u0000\n  Song  ");
        assertTrue(clean.equals("Artist Song"));

        String bounded = RadioMetadataReader.clean("X".repeat(700));
        assertTrue(bounded.codePointCount(0, bounded.length()) == 512);
    }

    private static RadioServiceClient.RadioState state(
            int band,
            int frequency,
            String name
    ) {
        return new RadioServiceClient.RadioState(
                band,
                frequency,
                name,
                "Artist - Song",
                "Pop",
                true,
                true,
                false,
                false,
                false,
                false,
                true
        );
    }
}
