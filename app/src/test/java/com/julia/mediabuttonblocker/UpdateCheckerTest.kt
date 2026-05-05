package com.julia.mediabuttonblocker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `newer patch version is detected`() {
        assertTrue(UpdateChecker.isStrictlyNewer("1.11", "1.12"))
    }

    @Test
    fun `same version is not newer`() {
        assertFalse(UpdateChecker.isStrictlyNewer("1.12", "1.12"))
    }

    @Test
    fun `older version is not newer`() {
        assertFalse(UpdateChecker.isStrictlyNewer("1.12", "1.11"))
    }

    @Test
    fun `newer major version is detected`() {
        assertTrue(UpdateChecker.isStrictlyNewer("1.12", "2.0"))
    }

    @Test
    fun `three-segment versions compared correctly`() {
        assertTrue(UpdateChecker.isStrictlyNewer("1.0.0", "1.0.1"))
        assertFalse(UpdateChecker.isStrictlyNewer("1.0.1", "1.0.0"))
    }

    @Test
    fun `missing segments treated as zero`() {
        assertTrue(UpdateChecker.isStrictlyNewer("1.0", "1.0.1"))
        assertFalse(UpdateChecker.isStrictlyNewer("1.0.1", "1.0"))
    }

    @Test
    fun `numeric comparison not lexicographic`() {
        assertTrue(UpdateChecker.isStrictlyNewer("1.9", "1.10"))
    }

    @Test
    fun `dash-separated segments are compared`() {
        assertTrue(UpdateChecker.isStrictlyNewer("1.0-alpha", "1.0-beta"))
    }
}
