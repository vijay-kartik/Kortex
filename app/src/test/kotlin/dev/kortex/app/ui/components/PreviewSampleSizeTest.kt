package dev.kortex.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Chat image previews decode at roughly screen size, never at the camera's full resolution. */
class PreviewSampleSizeTest {
    @Test
    fun `images no bigger than the screen are decoded at full size`() {
        assertEquals(1, previewSampleSize(1080, 720, 2400))
        assertEquals(1, previewSampleSize(2400, 1080, 2400))
    }

    @Test
    fun `a 50 MP photo is sampled down to under twice the screen`() {
        val sample = previewSampleSize(8160, 6120, 2400)
        assertEquals(2, sample)
        assertTrue(8160 / sample < 2400 * 2)
        assertTrue(8160 / sample >= 2400)
    }

    @Test
    fun `the longest side decides, whatever the orientation`() {
        assertEquals(previewSampleSize(12000, 3000, 1000), previewSampleSize(3000, 12000, 1000))
        assertEquals(8, previewSampleSize(12000, 3000, 1000))
    }

    @Test
    fun `a non-positive cap does not loop forever`() {
        assertTrue(previewSampleSize(4000, 3000, 0) >= 1)
    }
}
