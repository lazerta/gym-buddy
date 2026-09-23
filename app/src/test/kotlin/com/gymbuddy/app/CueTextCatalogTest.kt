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
    @Test fun unknownCueFallsBackSafely() {
        assertEquals("Adjust your form.", CueTextCatalog.text("future_rule"))
    }
}
