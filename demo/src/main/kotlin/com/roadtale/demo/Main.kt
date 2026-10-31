package com.roadtale.demo

import com.roadtale.core.engine.RoadTaleEngine
import com.roadtale.core.model.CompanionAvatar
import com.roadtale.core.model.CompanionConfig
import com.roadtale.core.model.Episode
import com.roadtale.core.model.Genre
import com.roadtale.core.model.JourneyMode
import com.roadtale.core.model.Scene
import com.roadtale.core.model.VoiceLetter
import com.roadtale.core.model.VoiceProfile
import com.roadtale.core.model.Waypoint
import com.roadtale.core.model.WaypointKind
import com.roadtale.core.sdk.FocusZone
import com.roadtale.core.sdk.FocusZoneKind
import com.roadtale.core.sdk.PoiCategory
import com.roadtale.core.sdk.RouteInfo
import com.roadtale.core.sdk.RoutePoi
import com.roadtale.core.sdk.SdkConnectionLayer
import com.roadtale.core.sdk.mock.MockAudio
import com.roadtale.core.sdk.mock.MockLocation
import com.roadtale.core.sdk.mock.MockNavi
import com.roadtale.core.sdk.mock.MockVehicle
import com.roadtale.core.sdk.mock.MockVoiceInput
import com.roadtale.core.variety.ContextSignals
import com.roadtale.core.variety.Weather

/**
 * Demo driver: runs two simulated journeys against the mock SDK layer and
 * prints the full event log.
 *
 *  Scenario 1 — a rainy Monday commute (proposal section 4, Scenario 1):
 *  episode resumes, rain variant, climax exactly on the bridge, the story
 *  steps aside for the complex interchange, a choice is deferred (never
 *  asked in a focus zone), a child's voice letter arrives at the bridge,
 *  the driver's reply is ignored while moving and honored at a full stop,
 *  and the episode ends on its cliffhanger exactly at the office.
 *
 *  Scenario 2 — a family weekend trip in an EV (proposal Scenario 3 beats):
 *  traffic jam inserts a side scene + voice game, the rest coach speaks up
 *  in character, and a charging stop plays a short complete interlude story.
 */
fun main() {
    scenario1RainyCommute()
    println()
    scenario2FamilyEvTrip()
}

private fun scenario1RainyCommute() {
    println("=== SCENARIO 1: Daily Chronicle — rainy Monday commute (Seoul, 40 min) ===")

    val vehicle = MockVehicle(speedKmh = 0.0, continuousDrivingMinutes = 5)
    val location = MockLocation()
    val office = Waypoint("office", "Office", WaypointKind.DESTINATION, 30_000)
    val bridge = Waypoint("hangang-bridge", "Hangang Bridge", WaypointKind.BRIDGE, 12_000)
    val interchange = Waypoint("office-interchange", "Office Interchange", WaypointKind.INTERCHANGE, 26_000)
    val waypoints = listOf(
        Waypoint("home", "Home", WaypointKind.LANDMARK, 0),
        bridge, interchange, office
    )
    val navi = MockNavi(
        routeInfo = RouteInfo("Office", remainingDistanceMeters = 30_000, etaSeconds = 2_400),
        pois = mapOf(PoiCategory.REST_AREA to listOf(RoutePoi("Hangang View Rest Stop", PoiCategory.REST_AREA, 10)))
    )
    val audio = MockAudio()
    val sdk = SdkConnectionLayer(vehicle, location, navi, audio, MockVoiceInput())
    val engine = RoadTaleEngine(sdk)

    val episode = Episode(
        id = "S1E12", title = "The River Gate — Season 1, Episode 12",
        scenes = listOf(
            Scene("s1", "Yesterday's Door", 500),
            Scene("s2", "The River Gate Opens", 700, anchorWaypointId = "hangang-bridge", isClimax = true),
            Scene("s3", "Streets of Quiet Rain", 600),
            Scene("s4", "The Gate of Mirrors", 600, isCliffhangerPoint = true)
        )
    )
    val companion = CompanionConfig(
        avatar = CompanionAvatar.BAMI, genre = Genre.MYSTERY,
        visualThemePack = "Warm Storybook",
        voice = VoiceProfile("warm grandmother", "gentle", "cozy")
    )
    val letter = VoiceLetter(from = "the driver's child", durationSec = 20, deliverAtWaypointId = "hangang-bridge")

    engine.planJourney(
        JourneyMode.DAILY_CHRONICLE, companion, episode, waypoints,
        letters = listOf(letter),
        departureSignals = ContextSignals(weather = Weather.RAIN)
    )

    vehicle.speedKmh = 42.0
    engine.startJourney()
    engine.tick(300, ContextSignals(weather = Weather.RAIN))

    // The bridge: climax scene + the child's voice letter, both anchored here.
    location.markPassed(bridge)
    navi.routeInfo = RouteInfo("Office", remainingDistanceMeters = 18_000, etaSeconds = 1_500)
    engine.tick(420, ContextSignals(weather = Weather.RAIN))

    // The complex interchange approaches: the story must step aside by itself.
    navi.routeInfo = RouteInfo(
        "Office", remainingDistanceMeters = 4_000, etaSeconds = 420,
        upcomingFocusZones = listOf(FocusZone("Office Interchange", FocusZoneKind.COMPLEX_INTERCHANGE, 400, 800))
    )
    engine.tick(60)
    engine.offerBranchChoice("bridge-keeper")          // must be DEFERRED, not asked
    engine.driverReplies("응")                            // must be IGNORED (moving + focus zone)

    // Through the interchange: zone clears, story resumes, deferred choice appears.
    navi.routeInfo = RouteInfo("Office", remainingDistanceMeters = 2_800, etaSeconds = 300)
    location.markPassed(interchange)
    engine.tick(60)
    engine.passengerChooses("bridge-keeper", "help the keeper")

    // A full stop at a light: now — and only now — the driver's one word counts.
    vehicle.speedKmh = 0.0
    engine.driverReplies("응")
    vehicle.speedKmh = 35.0

    // Arrival at the office: cliffhanger, celebration, summary.
    navi.routeInfo = RouteInfo("Office", remainingDistanceMeters = 0, etaSeconds = 0)
    navi.arrived = true
    engine.tick(30)

    println("[DEMO] final state=${engine.stateMachine.state}, transitions=${engine.stateMachine.history.size}, " +
        "audio utterances=${audio.utterances.size}, detours added=${navi.addedWaypoints.map { it.name }}")
}

private fun scenario2FamilyEvTrip() {
    println("=== SCENARIO 2: Family Journey — weekend EV trip to Gangneung ===")

    val vehicle = MockVehicle(speedKmh = 88.0, continuousDrivingMinutes = 40, evBatteryPercent = 46)
    val location = MockLocation()
    val restStop = Waypoint("daegwallyeong", "Daegwallyeong Pass", WaypointKind.VIEWPOINT, 150_000)
    val waypoints = listOf(
        Waypoint("seoul", "Seoul", WaypointKind.LANDMARK, 0),
        restStop,
        Waypoint("gangneung", "Gangneung", WaypointKind.DESTINATION, 230_000)
    )
    val navi = MockNavi(
        routeInfo = RouteInfo("Gangneung", remainingDistanceMeters = 230_000, etaSeconds = 9_600),
        pois = mapOf(PoiCategory.REST_AREA to listOf(RoutePoi("Hoengseong Rest Area", PoiCategory.REST_AREA, 15)))
    )
    val audio = MockAudio()
    val sdk = SdkConnectionLayer(vehicle, location, navi, audio, MockVoiceInput())
    val engine = RoadTaleEngine(sdk)

    val episode = Episode(
        id = "FAM-01", title = "The Dragon of the East Sea",
        scenes = listOf(
            Scene("f1", "The Map in the Glove Box", 2_400),
            Scene("f2", "The Mountain Keeper", 3_600, anchorWaypointId = "daegwallyeong"),
            Scene("f3", "The Sea at the End of the Road", 3_600, isCliffhangerPoint = true)
        )
    )
    val companion = CompanionConfig(
        avatar = CompanionAvatar.PANDA, genre = Genre.ADVENTURE,
        visualThemePack = "Cinematic Journey",
        voice = VoiceProfile("child companion", "bright", "cheerful")
    )
    engine.planJourney(JourneyMode.FAMILY_JOURNEY, companion, episode, waypoints)
    engine.startJourney()

    // Holiday traffic: ETA grows 50% -> side scene + voice game, main plot untouched.
    engine.tick(600, ContextSignals(etaGrowthRate = 1.5))

    // 1h50m of continuous driving: the companion (rest coach) speaks up in character.
    vehicle.continuousDrivingMinutes = 115
    engine.tick(300)

    // Battery low, then a charging stop: interlude story sized to the charge.
    vehicle.evBatteryPercent = 18
    engine.tick(120, ContextSignals(evBatteryPercent = 18))
    vehicle.isCharging = true
    engine.tick(60, ContextSignals(evBatteryPercent = 18, isCharging = true))
    vehicle.isCharging = false
    vehicle.evBatteryPercent = 82
    engine.tick(60, ContextSignals(evBatteryPercent = 82))

    navi.routeInfo = RouteInfo("Gangneung", remainingDistanceMeters = 0, etaSeconds = 0)
    navi.arrived = true
    engine.tick(30)

    println("[DEMO] final state=${engine.stateMachine.state}, transitions=${engine.stateMachine.history.size}, " +
        "audio utterances=${audio.utterances.size}")
}
