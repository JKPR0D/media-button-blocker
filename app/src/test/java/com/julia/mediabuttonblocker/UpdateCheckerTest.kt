package com.julia.mediabuttonblocker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Direct tests of [UpdateChecker.isStrictlyNewer] / [UpdateChecker.compareVersions].
 *
 * The in-app updater hits GitHub Releases, strips the leading "v", and asks
 * "is this strictly newer than what we're running?". A subtle version comparison
 * bug there would make the updater either silent (never offers an update) or
 * stuck in a loop (always offers an update). These tests pin the contract.
 */
class UpdateCheckerTest {
    @Test
    fun newerMinorVersionIsStrictlyNewer() {
        assertTrue(UpdateChecker.isStrictlyNewer("1.12", "1.13"))
    }

    @Test
    fun newerPatchVersionIsStrictlyNewer() {
        assertTrue(UpdateChecker.isStrictlyNewer("1.12.0", "1.12.1"))
    }

    @Test
    fun sameVersionIsNotStrictlyNewer() {
        assertFalse(UpdateChecker.isStrictlyNewer("1.13", "1.13"))
    }

    @Test
    fun olderRemoteIsNotStrictlyNewer() {
        assertFalse(UpdateChecker.isStrictlyNewer("1.13", "1.12"))
    }

    @Test
    fun multiDigitMinorBeatsLowerSingleDigit() {
        // Regression guard: lexicographic comparison would say "1.9" > "1.10".
        assertTrue(UpdateChecker.isStrictlyNewer("1.9", "1.10"))
    }

    @Test
    fun multiDigitMinorOrderingIsCorrect() {
        assertTrue(UpdateChecker.isStrictlyNewer("1.13", "1.100"))
        assertFalse(UpdateChecker.isStrictlyNewer("1.100", "1.13"))
    }

    @Test
    fun majorBumpBeatsMinor() {
        assertTrue(UpdateChecker.isStrictlyNewer("1.99", "2.0"))
        assertFalse(UpdateChecker.isStrictlyNewer("2.0", "1.99"))
    }
}
