package com.tunlezah.dashcam

import android.Manifest
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.google.common.truth.Truth.assertThat
import com.tunlezah.dashcam.domain.capability.CapabilityScorer
import com.tunlezah.dashcam.domain.capability.DeviceCapabilityProfiler
import com.tunlezah.dashcam.domain.capability.ProfileSelector
import com.tunlezah.dashcam.core.DiagnosticsLog
import com.tunlezah.dashcam.domain.settings.DashcamSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device smoke tests (require a real device or emulator with a camera).
 * These verify the capability probe and profile selection against REAL
 * hardware — the on-hardware counterpart of the JVM fixture tests. Extended
 * recording benchmarks are documented in docs/benchmarking.md and run via the
 * diagnostics screen on physical hardware.
 */
@RunWith(AndroidJUnit4::class)
class RecordingPipelineSmokeTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun capabilityProfilerFindsCameraAndEncoder() {
        val caps = DeviceCapabilityProfiler(context, DiagnosticsLog()).profile()
        assertThat(caps.cameras).isNotEmpty()
        assertThat(caps.videoEncoders.filter { it.mimeType == "video/avc" }).isNotEmpty()
        assertThat(caps.hasAccelerometer).isTrue()
    }

    @Test
    fun autoProfileIsSupportedByThisDevice() {
        val profiler = DeviceCapabilityProfiler(context, DiagnosticsLog())
        val caps = profiler.profile()
        val tier = CapabilityScorer.tier(caps)
        val profile = ProfileSelector.select(caps, DashcamSettings(), tier)

        // The selected profile must be verifiably supported by both the camera
        // and an encoder on the actual hardware.
        val camera = caps.cameras.first { it.facingBack }
        assertThat(camera.supportsSize(profile.width, profile.height)).isTrue()
        val encoder = caps.videoEncoders.first { it.mimeType == profile.codec.mimeType }
        assertThat(encoder.supports(profile.width, profile.height)).isTrue()
        assertThat(profile.fps).isAtMost(camera.maxFixedFps)
    }
}
