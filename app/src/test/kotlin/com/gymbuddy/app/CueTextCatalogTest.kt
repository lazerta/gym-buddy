package com.gymbuddy.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CueTextCatalogTest {
    @Test fun knownCueIsShortAndStable() {
        assertEquals("Keep both sides moving together.", CueTextCatalog.text("bilateral_asymmetry"))
    }
    @Test fun unknownCueFallsBackSafely() {
        assertEquals("Adjust your form.", CueTextCatalog.text("future_rule"))
    }
}
