package com.roadtale.core.model

/**
 * Core story/domain model of RoadTale Chronicle (로드테일 크로니클).
 *
 * The model mirrors the proposal document (section 3): one shared story engine,
 * two modes (Daily Chronicle commute serial / Family Journey), scenes anchored
 * to real waypoints on the driver's route, and the "My Companion" (내 동행자)
 * personalization system.
 */

/** Operating mode of the shared story engine. Proposal 3-1 / 3-2. */
enum class JourneyMode {
    /** Daily commute serial: episodes anchored to the repeated commute route. */
    DAILY_CHRONICLE,

    /** Interactive family trip story, co-created by passengers' voice choices. */
    FAMILY_JOURNEY
}

/** Story genre selected by the user in the "My Companion" system. Proposal 3-11. */
enum class Genre { ADVENTURE, HEALING, COMEDY, MYSTERY }

/**
 * Companion avatars available at launch. Proposal 3-11.
 * Choosing an avatar means choosing the whole mood of the journey: each avatar
 * ships with its own personality, speech style and voice as one set.
 */
enum class CompanionAvatar(val displayNameKo: String) {
    BAMI("바미"),            // The owl narrator; the brand's signature default.
    MOON_RABBIT("달토끼"),   // The moon rabbit of Korean folklore.
    SLEEPY_CAT("졸린 고양이"), // The sleepy cat.
    PANDA("판다")            // The panda — a proven cultural favorite in Korea (Fu Bao).
}

/**
 * Voice customization of the "My Companion" system. Proposal 3-11.
 * Type, tone and mood are all user-selected and applied consistently to
 * narration, in-story navigation guidance and rest suggestions
 * (implemented on top of TextToSpeech SDK voice styling).
 */
data class VoiceProfile(
    val type: String,     // e.g. "warm grandmother", "young friend", "late-night radio", "child companion"
    val tone: String,     // e.g. "gentle", "bright", "calm"
    val mood: String,     // e.g. "cozy", "cheerful", "soothing"
    val speed: Float = 1.0f
)

/**
 * The full "My Companion" (내 동행자) selection: avatar + genre + curated visual
 * theme pack + voice. Selection changes are only allowed while fully stopped
 * or by a passenger (driver-safety architecture, proposal 3-9 / 3-11).
 */
data class CompanionConfig(
    val avatar: CompanionAvatar,
    val genre: Genre,
    val visualThemePack: String,  // Curated theme packs only — quality stays with the service.
    val voice: VoiceProfile
)

/** Kinds of route waypoints a scene can be anchored to. */
enum class WaypointKind { LANDMARK, BRIDGE, TUNNEL, INTERCHANGE, REST_AREA, VIEWPOINT, DESTINATION }

/** A real point on the route that story scenes anchor to. */
data class Waypoint(
    val id: String,
    val name: String,
    val kind: WaypointKind,
    /** Distance from journey start, meters. */
    val distanceFromStartMeters: Int
)

/**
 * One scene of an episode. Scenes are the unit the scheduler places on the
 * journey timeline; a scene may optionally be bound to a specific waypoint.
 */
data class Scene(
    val id: String,
    val title: String,
    /** Nominal playback length if nothing about the journey changes. */
    val baseDurationSec: Int,
    /** Waypoint this scene is anchored to, if any (Daily Chronicle). */
    val anchorWaypointId: String? = null,
    val isClimax: Boolean = false,
    /** If true, ending the episode here leaves the daily cliffhanger. */
    val isCliffhangerPoint: Boolean = false
)

/** A serial episode (Daily Chronicle) or a journey-length story (Family Journey). */
data class Episode(
    val id: String,
    val title: String,
    val scenes: List<Scene>
) {
    val nominalDurationSec: Int get() = scenes.sumOf { it.baseDurationSec }
}

/** A family voice letter (가족 음성 편지), proposal 3-5. */
data class VoiceLetter(
    val from: String,
    val durationSec: Int,
    /** Waypoint id at which the letter is delivered, if location-bound. */
    val deliverAtWaypointId: String? = null
)
