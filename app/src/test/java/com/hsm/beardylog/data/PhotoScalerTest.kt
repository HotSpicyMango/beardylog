package com.hsm.beardylog.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoScalerTest {
    @Test fun `already small enough stays at 1`() {
        assertEquals(1, PhotoScaler.sampleSizeFor(1024, 768, 2048))
        assertEquals(1, PhotoScaler.sampleSizeFor(2048, 2048, 2048))
    }

    @Test fun `scales the long edge down to the limit`() {
        assertEquals(2, PhotoScaler.sampleSizeFor(4096, 2048, 2048))
        assertEquals(8, PhotoScaler.sampleSizeFor(4032, 3024, 1000))
    }

    @Test fun `result never exceeds the limit on either edge`() {
        listOf(4032 to 3024, 8000 to 6000, 6000 to 300, 300 to 6000).forEach { (w, h) ->
            val sample = PhotoScaler.sampleSizeFor(w, h, PhotoScaler.MAX_DIMENSION)
            assertTrue("$w x $h / $sample", w / sample <= PhotoScaler.MAX_DIMENSION)
            assertTrue("$w x $h / $sample", h / sample <= PhotoScaler.MAX_DIMENSION)
        }
    }
}
