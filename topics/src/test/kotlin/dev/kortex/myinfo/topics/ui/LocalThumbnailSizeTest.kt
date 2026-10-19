package dev.kortex.myinfo.topics.ui

import dev.kortex.myinfo.topics.ui.common.thumbnailCropSize
import dev.kortex.myinfo.topics.ui.common.thumbnailSampleSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalThumbnailSizeTest {
    private fun thumbnail(width: Int, height: Int): Pair<Int, Int> {
        val sample = thumbnailSampleSize(width, height)
        return thumbnailCropSize(width / sample, height / sample)
    }

    @Test
    fun `camera photos keep their whole frame`() {
        assertEquals(8, thumbnailSampleSize(4000, 3000))
        assertEquals(500 to 375, thumbnail(4000, 3000))
        assertEquals(375 to 500, thumbnail(3000, 4000))
    }

    @Test
    fun `small images are left alone`() {
        assertEquals(1, thumbnailSampleSize(200, 120))
        assertEquals(200 to 120, thumbnail(200, 120))
    }

    @Test
    fun `a long screenshot keeps only its centre`() {
        val (width, height) = thumbnail(1080, 20_000)
        assertEquals(270 to 810, width to height)
        assertTrue(width * height * 4 < 1024 * 1024)
    }

    @Test
    fun `a panorama is held to the side cap`() {
        assertEquals(1024 to 375, thumbnail(12_000, 3_000))
    }

    @Test
    fun `no side goes over the cap whatever the shape`() {
        listOf(1 to 50_000, 50_000 to 1, 1023 to 4000, 511 to 511, 2048 to 2048, 600 to 20_000).forEach { (w, h) ->
            val (width, height) = thumbnail(w, h)
            assertTrue("$w×$h gave $width×$height", width in 1..1024 && height in 1..1024)
        }
    }
}
