package fi.radioplus.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class StationPageSelectionPolicyTest {
    @Test
    public void backgroundUpdateCannotOverrideManuallySelectedPage() {
        assertEquals(
                2,
                StationPageSelectionPolicy.targetPage(2, 0, 6, true, true)
        );
    }

    @Test
    public void initialAndExplicitSelectionCanFollowPlayingStation() {
        assertEquals(
                0,
                StationPageSelectionPolicy.targetPage(2, 0, 6, true, false)
        );
        assertEquals(
                2,
                StationPageSelectionPolicy.targetPage(0, 13, 6, true, false)
        );
    }

    @Test
    public void disabledFollowAndUnknownStationPreserveCurrentPage() {
        assertEquals(
                1,
                StationPageSelectionPolicy.targetPage(1, 0, 6, false, false)
        );
        assertEquals(
                1,
                StationPageSelectionPolicy.targetPage(1, -1, 6, true, false)
        );
    }
}
