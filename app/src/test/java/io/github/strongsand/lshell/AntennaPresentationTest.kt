package io.github.strongsand.lshell

import org.junit.Assert.*
import org.junit.Test

class AntennaPresentationTest {
    @Test fun measuredZeroIsAvailable() {
        assertEquals(0f, antennaObstructionPercent(0f, true)!!, 0f)
    }
    @Test fun missingOfflineAndInvalidReadingsStayAbsent() {
        assertNull(antennaObstructionPercent(null, true))
        assertNull(antennaObstructionPercent(0f, false))
        assertNull(antennaObstructionPercent(Float.NaN, true))
        assertNull(antennaObstructionPercent(Float.POSITIVE_INFINITY, true))
    }
    @Test fun finiteValuesUseTheExistingFractionToPercentScale() {
        assertEquals(25f, antennaObstructionPercent(.25f, true)!!, 0f)
        assertEquals(100f, antennaObstructionPercent(1f, true)!!, 0f)
        assertEquals(100f, antennaObstructionPercent(2f, true)!!, 0f)
        assertEquals(0f, antennaObstructionPercent(-1f, true)!!, 0f)
    }
}
