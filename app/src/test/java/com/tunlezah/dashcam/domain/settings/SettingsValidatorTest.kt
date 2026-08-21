package com.tunlezah.dashcam.domain.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettingsValidatorTest {

    @Test
    fun `defaults pass through unchanged`() {
        val defaults = DashcamSettings()
        assertThat(SettingsValidator.validate(defaults)).isEqualTo(defaults)
    }

    @Test
    fun `absurd segment length snaps to nearest allowed`() {
        val s = SettingsValidator.validate(DashcamSettings(segmentMinutes = 45))
        assertThat(s.segmentMinutes).isEqualTo(10)
        val s2 = SettingsValidator.validate(DashcamSettings(segmentMinutes = 2))
        assertThat(s2.segmentMinutes).isEqualTo(1)
    }

    @Test
    fun `pre and post event durations snap to allowed options`() {
        val s = SettingsValidator.validate(DashcamSettings(preEventSeconds = 999, postEventSeconds = 999))
        assertThat(s.preEventSeconds).isEqualTo(60)
        assertThat(s.postEventSeconds).isEqualTo(120)
    }

    @Test
    fun `loop cap cannot consume the device or go below 1GiB`() {
        val big = SettingsValidator.validate(DashcamSettings(loopMaxBytes = Long.MAX_VALUE))
        assertThat(big.loopMaxBytes).isEqualTo(SettingsValidator.MAX_LOOP_BYTES)
        val tiny = SettingsValidator.validate(DashcamSettings(loopMaxBytes = 1))
        assertThat(tiny.loopMaxBytes).isEqualTo(SettingsValidator.MIN_LOOP_BYTES)
    }

    @Test
    fun `startup delay clamps to 0-30 seconds`() {
        assertThat(SettingsValidator.validate(DashcamSettings(startupDelaySeconds = -5)).startupDelaySeconds)
            .isEqualTo(0)
        assertThat(SettingsValidator.validate(DashcamSettings(startupDelaySeconds = 300)).startupDelaySeconds)
            .isEqualTo(30)
    }

    @Test
    fun `battery floor clamps to safe range`() {
        assertThat(SettingsValidator.validate(DashcamSettings(batteryFloorPercent = 0)).batteryFloorPercent)
            .isEqualTo(SettingsValidator.BATTERY_FLOOR_MIN)
        assertThat(SettingsValidator.validate(DashcamSettings(batteryFloorPercent = 99)).batteryFloorPercent)
            .isEqualTo(SettingsValidator.BATTERY_FLOOR_MAX)
    }

    @Test
    fun `gps interval clamps and custom label is truncated`() {
        val s = SettingsValidator.validate(
            DashcamSettings(gpsUpdateIntervalMs = 1, overlayCustomLabel = "X".repeat(100))
        )
        assertThat(s.gpsUpdateIntervalMs).isEqualTo(SettingsValidator.GPS_INTERVAL_MIN_MS)
        assertThat(s.overlayCustomLabel.length).isEqualTo(SettingsValidator.MAX_CUSTOM_LABEL_LENGTH)
    }

    @Test
    fun `effective pre-event window is bounded by segment coverage`() {
        val s = DashcamSettings(preEventSeconds = 60, segmentMinutes = 1)
        // 1-minute segments: protection can reach back one full segment + the
        // in-progress one, so 60 s is fine…
        assertThat(SettingsValidator.effectivePreEventSeconds(s)).isEqualTo(60)
        // …and can never exceed that coverage.
        val extreme = s.copy(preEventSeconds = 60, segmentMinutes = 1)
        assertThat(SettingsValidator.effectivePreEventSeconds(extreme)).isAtMost(120)
    }

    @Test
    fun `zero bitrate means auto and survives validation`() {
        assertThat(SettingsValidator.validate(DashcamSettings(manualBitrateBps = 0)).manualBitrateBps)
            .isEqualTo(0)
        assertThat(SettingsValidator.validate(DashcamSettings(manualBitrateBps = 500)).manualBitrateBps)
            .isEqualTo(SettingsValidator.BITRATE_RANGE_BPS.first)
    }
}
