package com.camlink.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraProfileRulesTest {
    @Test
    fun camera2CandidatesRemainWhenVendorProfilesExist() {
        val vendor = CameraProfile(1920, 1080, 30, highSpeed = false, source = ProfileSource.CAMCORDER_HINT)
        val camera2 = CameraProfile(1920, 1080, 60, highSpeed = false, source = ProfileSource.CAMERA2)

        val merged = CameraProfileRules.mergeNormalProfiles(listOf(vendor), listOf(camera2))

        assertEquals(setOf(30, 60), merged.map { it.fps }.toSet())
        assertTrue(merged.any { it.fps == 60 && it.source == ProfileSource.CAMERA2 })
    }

    @Test
    fun camera2FourKCandidateIsNotDiscardedByA1080pRecorderHint() {
        val vendor = CameraProfile(1920, 1080, 30, highSpeed = false, source = ProfileSource.CAMCORDER_HINT)
        val fourK = CameraProfile(3840, 2160, 30, highSpeed = false, codec = "h265", source = ProfileSource.CAMERA2)

        val merged = CameraProfileRules.mergeNormalProfiles(listOf(vendor), listOf(fourK))

        assertTrue(merged.any { it.width == 3840 && it.height == 2160 && it.fps == 30 && it.codec == "h265" })
    }
}
