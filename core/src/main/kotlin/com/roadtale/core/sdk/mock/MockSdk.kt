package com.roadtale.core.sdk.mock

import com.roadtale.core.model.Waypoint
import com.roadtale.core.sdk.AudioPriority
import com.roadtale.core.sdk.GeoPoint
import com.roadtale.core.sdk.LocationDataSource
import com.roadtale.core.sdk.NaviHelperDataSource
import com.roadtale.core.sdk.PoiCategory
import com.roadtale.core.sdk.RouteInfo
import com.roadtale.core.sdk.RoutePoi
import com.roadtale.core.sdk.StoryAudioSink
import com.roadtale.core.sdk.VehicleDataSource
import com.roadtale.core.sdk.VehicleSnapshot
import com.roadtale.core.sdk.VoiceInputSource

/**
 * Mock implementations of the Pleos-style SDK abstractions, for development
 * and demo runs on a plain JVM (no vehicle, no Android SDK needed).
 *
 * The demo driver script mutates these mocks step by step to simulate a real
 * journey: speed changes, waypoints passing, battery draining, arrival.
 */

class MockVehicle(
    var speedKmh: Double = 0.0,
    var continuousDrivingMinutes: Int = 0,
    var evBatteryPercent: Int? = null,
    var isCharging: Boolean = false
) : VehicleDataSource {
    override fun snapshot(): VehicleSnapshot = VehicleSnapshot(
        speedKmh = speedKmh,
        continuousDrivingMinutes = continuousDrivingMinutes,
        evBatteryPercent = evBatteryPercent,
        isCharging = isCharging
    )
}

class MockLocation(
    var position: GeoPoint = GeoPoint(37.5665, 126.9780) // Seoul City Hall
) : LocationDataSource {
    private val passed = linkedSetOf<String>()
    fun markPassed(waypoint: Waypoint) { passed += waypoint.id }
    override fun currentPosition(): GeoPoint = position
    override fun passedWaypointIds(): Set<String> = passed.toSet()
}

class MockNavi(
    var routeInfo: RouteInfo? = null,
    var arrived: Boolean = false,
    private val pois: Map<PoiCategory, List<RoutePoi>> = emptyMap()
) : NaviHelperDataSource {
    val addedWaypoints = mutableListOf<RoutePoi>()
    override fun route(): RouteInfo? = routeInfo
    override fun poisAlongRoute(category: PoiCategory): List<RoutePoi> = pois[category].orEmpty()
    override fun addWaypoint(poi: RoutePoi) { addedWaypoints += poi }
    override fun hasArrived(): Boolean = arrived
}

/** Records every utterance with its priority instead of playing audio. */
class MockAudio : StoryAudioSink {
    data class Utterance(val priority: AudioPriority, val text: String)

    val utterances = mutableListOf<Utterance>()
    var storyFadedOut: Boolean = false
        private set

    override fun narrate(text: String, priority: AudioPriority) {
        utterances += Utterance(priority, text)
    }

    override fun fadeOutStory() { storyFadedOut = true }
    override fun resumeStory() { storyFadedOut = false }
}

class MockVoiceInput(var driverReply: String? = null) : VoiceInputSource {
    override fun lastDriverReply(): String? = driverReply
}
