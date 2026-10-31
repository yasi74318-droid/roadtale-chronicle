package com.roadtale.core.sdk

import com.roadtale.core.model.Waypoint

/**
 * SDK connection layer (SDK 연동 레이어).
 *
 * Clean, Pleos-style abstractions over the four vehicle SDKs the proposal is
 * built on (proposal 3-10 / 5-2):
 *  - Vehicle SDK      -> [VehicleDataSource]
 *  - FusedLocation SDK -> [LocationDataSource]
 *  - NaviHelper SDK   -> [NaviHelperDataSource]
 *  - TextToSpeech / SpeechToText / media playback -> [StoryAudioSink], [VoiceInputSource]
 *
 * The story engine only ever talks to these interfaces, never to a concrete
 * SDK, so the same core runs against the real Pleos SDKs in the vehicle and
 * against the mock implementations in this repository for development/tests.
 */

// ---------------------------------------------------------------- Vehicle ---

/** Snapshot of the vehicle state the engine reasons about (Vehicle SDK). */
data class VehicleSnapshot(
    val speedKmh: Double,
    /** Continuously driven time without a real break, minutes. Feeds the rest coach. */
    val continuousDrivingMinutes: Int,
    /** EV battery state of charge, percent; null for non-EV vehicles. */
    val evBatteryPercent: Int? = null,
    /** True while the EV is plugged in and charging. */
    val isCharging: Boolean = false
) {
    val isMoving: Boolean get() = speedKmh > MOVING_THRESHOLD_KMH
    val isFullyStopped: Boolean get() = speedKmh <= STOPPED_THRESHOLD_KMH

    companion object {
        const val MOVING_THRESHOLD_KMH = 3.0
        const val STOPPED_THRESHOLD_KMH = 0.5
    }
}

interface VehicleDataSource {
    fun snapshot(): VehicleSnapshot
}

// ------------------------------------------------------------- Location -----

/** A geographic coordinate. */
data class GeoPoint(val latitude: Double, val longitude: Double)

interface LocationDataSource {
    fun currentPosition(): GeoPoint
    /** Ids of waypoints the vehicle has already passed on this journey. */
    fun passedWaypointIds(): Set<String>
}

// ------------------------------------------------------------ NaviHelper ----

/** A route section where the driver needs full attention (proposal 3-9 / 5-3). */
enum class FocusZoneKind { COMPLEX_INTERCHANGE, EXIT, U_TURN, SHARP_TRAFFIC_CHANGE, DESTINATION_APPROACH }

data class FocusZone(
    val name: String,
    val kind: FocusZoneKind,
    /** Distance ahead of the vehicle where the zone starts, meters. */
    val startsInMeters: Int,
    /** Length of the zone, meters. */
    val lengthMeters: Int
)

/** A point of interest on/near the route (NaviHelper route POI search). */
enum class PoiCategory { LANDMARK, REST_AREA, FUEL_STATION, EV_CHARGER, RESTAURANT }

data class RoutePoi(
    val name: String,
    val category: PoiCategory,
    /** Driving time until the vehicle reaches it, minutes. */
    val etaMinutes: Int
)

/** Live route state from NaviHelper. */
data class RouteInfo(
    val destinationName: String,
    val remainingDistanceMeters: Int,
    val etaSeconds: Int,
    /** Upcoming focus zones, nearest first. */
    val upcomingFocusZones: List<FocusZone> = emptyList()
)

interface NaviHelperDataSource {
    fun route(): RouteInfo?
    fun poisAlongRoute(category: PoiCategory): List<RoutePoi>
    /** Registers a one-word-approved detour waypoint ("응" — proposal 3-3). */
    fun addWaypoint(poi: RoutePoi)
    fun hasArrived(): Boolean
}

// ----------------------------------------------------------------- Audio ----

/**
 * Audio output priorities (proposal 3-9 / 5-4). Guidance always outranks
 * safety announcements, which always outrank the story: the story can never
 * mask navigation or safety audio, by construction.
 */
enum class AudioPriority { GUIDANCE, SAFETY, STORY }

/** Abstraction over TTS + media playback (the "Media" output of the car). */
interface StoryAudioSink {
    /** Speaks/plays a story line as the companion character. */
    fun narrate(text: String, priority: AudioPriority = AudioPriority.STORY)

    /** Character-voiced turn guidance (story navigation, proposal 3-3). */
    fun guide(text: String) = narrate(text, AudioPriority.GUIDANCE)

    fun fadeOutStory()
    fun resumeStory()
}

/** Abstraction over SpeechToText for passenger choices and driver one-word replies. */
interface VoiceInputSource {
    /** The single-word driver reply ("응"/"아니"), only honored at a full stop. */
    fun lastDriverReply(): String?
}

// ------------------------------------------------------- Connection layer ---

/** Everything the engine needs to know about the car right now, in one read. */
data class DrivingContext(
    val vehicle: VehicleSnapshot,
    val position: GeoPoint,
    val route: RouteInfo?,
    val passedWaypointIds: Set<String>
)

/**
 * Facade that binds the four SDK abstractions into one connection point.
 * In production this wraps the real Pleos SDK adapters; in this prototype it
 * wraps [com.roadtale.core.sdk.mock] implementations.
 */
class SdkConnectionLayer(
    val vehicle: VehicleDataSource,
    val location: LocationDataSource,
    val navi: NaviHelperDataSource,
    val audio: StoryAudioSink,
    val voiceInput: VoiceInputSource
) {
    fun currentContext(): DrivingContext = DrivingContext(
        vehicle = vehicle.snapshot(),
        position = location.currentPosition(),
        route = navi.route(),
        passedWaypointIds = location.passedWaypointIds()
    )

    /** Waypoints of the planned route; filled by the engine at journey start. */
    var routeWaypoints: List<Waypoint> = emptyList()
}
