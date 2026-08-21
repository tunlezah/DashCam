package com.tunlezah.dashcam.domain.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "dashcam_settings")

/**
 * DataStore-backed settings persistence. Every read passes through
 * [SettingsValidator.validate] so downstream code can rely on sane values.
 */
class SettingsRepository(private val context: Context) {

    val settings: Flow<DashcamSettings> = context.dataStore.data.map { p ->
        val defaults = DashcamSettings()
        SettingsValidator.validate(
            DashcamSettings(
                qualityMode = p.enum(Keys.QUALITY_MODE, defaults.qualityMode),
                manualResolution = p.enum(Keys.MANUAL_RESOLUTION, defaults.manualResolution),
                manualFps = p[Keys.MANUAL_FPS] ?: defaults.manualFps,
                manualCodec = p.enum(Keys.MANUAL_CODEC, defaults.manualCodec),
                manualBitrateBps = p[Keys.MANUAL_BITRATE] ?: defaults.manualBitrateBps,
                segmentMinutes = p[Keys.SEGMENT_MINUTES] ?: defaults.segmentMinutes,
                startupDelaySeconds = p[Keys.STARTUP_DELAY] ?: defaults.startupDelaySeconds,
                autoStartOnLaunch = p[Keys.AUTO_START] ?: defaults.autoStartOnLaunch,
                eventDetectionEnabled = p[Keys.EVENT_DETECTION] ?: defaults.eventDetectionEnabled,
                eventSensitivity = p.enum(Keys.EVENT_SENSITIVITY, defaults.eventSensitivity),
                preEventSeconds = p[Keys.PRE_EVENT_SECONDS] ?: defaults.preEventSeconds,
                postEventSeconds = p[Keys.POST_EVENT_SECONDS] ?: defaults.postEventSeconds,
                loopMaxBytes = p[Keys.LOOP_MAX_BYTES] ?: defaults.loopMaxBytes,
                protectedBudgetBytes = p[Keys.PROTECTED_BUDGET] ?: defaults.protectedBudgetBytes,
                preferRemovableStorage = p[Keys.PREFER_REMOVABLE] ?: defaults.preferRemovableStorage,
                overlayMode = p.enum(Keys.OVERLAY_MODE, defaults.overlayMode),
                overlaySpeed = p[Keys.OVERLAY_SPEED] ?: defaults.overlaySpeed,
                overlayGpsCoordinates = p[Keys.OVERLAY_GPS] ?: defaults.overlayGpsCoordinates,
                overlayDate = p[Keys.OVERLAY_DATE] ?: defaults.overlayDate,
                overlayTime = p[Keys.OVERLAY_TIME] ?: defaults.overlayTime,
                overlayWeather = p[Keys.OVERLAY_WEATHER] ?: defaults.overlayWeather,
                overlayCustomLabel = p[Keys.OVERLAY_LABEL] ?: defaults.overlayCustomLabel,
                gpsEnabled = p[Keys.GPS_ENABLED] ?: defaults.gpsEnabled,
                gpsEmbedInVideoMetadata = p[Keys.GPS_EMBED] ?: defaults.gpsEmbedInVideoMetadata,
                gpsWriteGpxTrack = p[Keys.GPS_GPX] ?: defaults.gpsWriteGpxTrack,
                gpsUpdateIntervalMs = p[Keys.GPS_INTERVAL] ?: defaults.gpsUpdateIntervalMs,
                microphoneEnabled = p[Keys.MIC_ENABLED] ?: defaults.microphoneEnabled,
                cameraFacing = p.enum(Keys.CAMERA_FACING, defaults.cameraFacing),
                stabilizationEnabled = p[Keys.STABILIZATION] ?: defaults.stabilizationEnabled,
                plugInAction = p.enum(Keys.PLUG_IN_ACTION, defaults.plugInAction),
                unplugAction = p.enum(Keys.UNPLUG_ACTION, defaults.unplugAction),
                unplugStopDelaySeconds = p[Keys.UNPLUG_DELAY] ?: defaults.unplugStopDelaySeconds,
                batteryFloorPercent = p[Keys.BATTERY_FLOOR] ?: defaults.batteryFloorPercent,
                keepScreenOn = p[Keys.KEEP_SCREEN_ON] ?: defaults.keepScreenOn,
                allowScreenOffRecording = p[Keys.SCREEN_OFF_RECORDING] ?: defaults.allowScreenOffRecording,
                theme = p.enum(Keys.THEME, defaults.theme),
                mapEnabled = p[Keys.MAP_ENABLED] ?: defaults.mapEnabled,
                mapMaxFps = p[Keys.MAP_MAX_FPS] ?: defaults.mapMaxFps,
                weatherEnabled = p[Keys.WEATHER_ENABLED] ?: defaults.weatherEnabled,
            )
        )
    }

    suspend fun current(): DashcamSettings = settings.first()

    suspend fun update(transform: (DashcamSettings) -> DashcamSettings) {
        val updated = SettingsValidator.validate(transform(current()))
        context.dataStore.edit { p ->
            p[Keys.QUALITY_MODE] = updated.qualityMode.name
            p[Keys.MANUAL_RESOLUTION] = updated.manualResolution.name
            p[Keys.MANUAL_FPS] = updated.manualFps
            p[Keys.MANUAL_CODEC] = updated.manualCodec.name
            p[Keys.MANUAL_BITRATE] = updated.manualBitrateBps
            p[Keys.SEGMENT_MINUTES] = updated.segmentMinutes
            p[Keys.STARTUP_DELAY] = updated.startupDelaySeconds
            p[Keys.AUTO_START] = updated.autoStartOnLaunch
            p[Keys.EVENT_DETECTION] = updated.eventDetectionEnabled
            p[Keys.EVENT_SENSITIVITY] = updated.eventSensitivity.name
            p[Keys.PRE_EVENT_SECONDS] = updated.preEventSeconds
            p[Keys.POST_EVENT_SECONDS] = updated.postEventSeconds
            p[Keys.LOOP_MAX_BYTES] = updated.loopMaxBytes
            p[Keys.PROTECTED_BUDGET] = updated.protectedBudgetBytes
            p[Keys.PREFER_REMOVABLE] = updated.preferRemovableStorage
            p[Keys.OVERLAY_MODE] = updated.overlayMode.name
            p[Keys.OVERLAY_SPEED] = updated.overlaySpeed
            p[Keys.OVERLAY_GPS] = updated.overlayGpsCoordinates
            p[Keys.OVERLAY_DATE] = updated.overlayDate
            p[Keys.OVERLAY_TIME] = updated.overlayTime
            p[Keys.OVERLAY_WEATHER] = updated.overlayWeather
            p[Keys.OVERLAY_LABEL] = updated.overlayCustomLabel
            p[Keys.GPS_ENABLED] = updated.gpsEnabled
            p[Keys.GPS_EMBED] = updated.gpsEmbedInVideoMetadata
            p[Keys.GPS_GPX] = updated.gpsWriteGpxTrack
            p[Keys.GPS_INTERVAL] = updated.gpsUpdateIntervalMs
            p[Keys.MIC_ENABLED] = updated.microphoneEnabled
            p[Keys.CAMERA_FACING] = updated.cameraFacing.name
            p[Keys.STABILIZATION] = updated.stabilizationEnabled
            p[Keys.PLUG_IN_ACTION] = updated.plugInAction.name
            p[Keys.UNPLUG_ACTION] = updated.unplugAction.name
            p[Keys.UNPLUG_DELAY] = updated.unplugStopDelaySeconds
            p[Keys.BATTERY_FLOOR] = updated.batteryFloorPercent
            p[Keys.KEEP_SCREEN_ON] = updated.keepScreenOn
            p[Keys.SCREEN_OFF_RECORDING] = updated.allowScreenOffRecording
            p[Keys.THEME] = updated.theme.name
            p[Keys.MAP_ENABLED] = updated.mapEnabled
            p[Keys.MAP_MAX_FPS] = updated.mapMaxFps
            p[Keys.WEATHER_ENABLED] = updated.weatherEnabled
        }
    }

    private object Keys {
        val QUALITY_MODE = stringPreferencesKey("quality_mode")
        val MANUAL_RESOLUTION = stringPreferencesKey("manual_resolution")
        val MANUAL_FPS = intPreferencesKey("manual_fps")
        val MANUAL_CODEC = stringPreferencesKey("manual_codec")
        val MANUAL_BITRATE = intPreferencesKey("manual_bitrate")
        val SEGMENT_MINUTES = intPreferencesKey("segment_minutes")
        val STARTUP_DELAY = intPreferencesKey("startup_delay_seconds")
        val AUTO_START = booleanPreferencesKey("auto_start_on_launch")
        val EVENT_DETECTION = booleanPreferencesKey("event_detection_enabled")
        val EVENT_SENSITIVITY = stringPreferencesKey("event_sensitivity")
        val PRE_EVENT_SECONDS = intPreferencesKey("pre_event_seconds")
        val POST_EVENT_SECONDS = intPreferencesKey("post_event_seconds")
        val LOOP_MAX_BYTES = longPreferencesKey("loop_max_bytes")
        val PROTECTED_BUDGET = longPreferencesKey("protected_budget_bytes")
        val PREFER_REMOVABLE = booleanPreferencesKey("prefer_removable_storage")
        val OVERLAY_MODE = stringPreferencesKey("overlay_mode")
        val OVERLAY_SPEED = booleanPreferencesKey("overlay_speed")
        val OVERLAY_GPS = booleanPreferencesKey("overlay_gps")
        val OVERLAY_DATE = booleanPreferencesKey("overlay_date")
        val OVERLAY_TIME = booleanPreferencesKey("overlay_time")
        val OVERLAY_WEATHER = booleanPreferencesKey("overlay_weather")
        val OVERLAY_LABEL = stringPreferencesKey("overlay_custom_label")
        val GPS_ENABLED = booleanPreferencesKey("gps_enabled")
        val GPS_EMBED = booleanPreferencesKey("gps_embed_metadata")
        val GPS_GPX = booleanPreferencesKey("gps_write_gpx")
        val GPS_INTERVAL = longPreferencesKey("gps_update_interval_ms")
        val MIC_ENABLED = booleanPreferencesKey("microphone_enabled")
        val CAMERA_FACING = stringPreferencesKey("camera_facing")
        val STABILIZATION = booleanPreferencesKey("stabilization_enabled")
        val PLUG_IN_ACTION = stringPreferencesKey("plug_in_action")
        val UNPLUG_ACTION = stringPreferencesKey("unplug_action")
        val UNPLUG_DELAY = intPreferencesKey("unplug_stop_delay_seconds")
        val BATTERY_FLOOR = intPreferencesKey("battery_floor_percent")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val SCREEN_OFF_RECORDING = booleanPreferencesKey("allow_screen_off_recording")
        val THEME = stringPreferencesKey("theme")
        val MAP_ENABLED = booleanPreferencesKey("map_enabled")
        val MAP_MAX_FPS = intPreferencesKey("map_max_fps")
        val WEATHER_ENABLED = booleanPreferencesKey("weather_enabled")
    }
}

private inline fun <reified T : Enum<T>> androidx.datastore.preferences.core.Preferences.enum(
    key: androidx.datastore.preferences.core.Preferences.Key<String>,
    default: T,
): T = this[key]?.let { stored -> enumValues<T>().firstOrNull { it.name == stored } } ?: default
