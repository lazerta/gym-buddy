package com.gymbuddy.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CueTextCatalogTest {
    @Test fun knownCueIsShortAndStable() {
        assertEquals("Keep both sides moving together.", CueTextCatalog.text("bilateral_asymmetry"))
    }
    @Test fun pressElbowPathCueIsShortAndStable() {
        assertEquals("Keep your elbows slightly closer in.", CueTextCatalog.text("press_elbow_path_flare"))
    }
    @Test fun lateralRaiseElevationCueIsShortAndStable() {
        assertEquals("Stop around shoulder height.", CueTextCatalog.text("lateral_raise_over_elevation"))
    }
    @Test fun unknownCueFallsBackSafely() {
        assertEquals("Adjust your form.", CueTextCatalog.text("future_rule"))
    }
}
