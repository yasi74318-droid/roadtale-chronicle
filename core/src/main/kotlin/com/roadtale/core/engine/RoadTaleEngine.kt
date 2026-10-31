package com.roadtale.core.engine

import com.roadtale.core.model.CompanionConfig
import com.roadtale.core.model.Episode
import com.roadtale.core.model.JourneyMode
import com.roadtale.core.model.VoiceLetter
import com.roadtale.core.model.Waypoint
import com.roadtale.core.scheduler.JourneyPlan
import com.roadtale.core.scheduler.ScheduleDecision
import com.roadtale.core.scheduler.WaypointAnchorScheduler
import com.roadtale.core.sdk.AudioPriority
import com.roadtale.core.sdk.PoiCategory
import com.roadtale.core.sdk.SdkConnectionLayer
import com.roadtale.core.state.JourneyEvent
import com.roadtale.core.state.JourneyState
import com.roadtale.core.state.JourneyStateMachine
import com.roadtale.core.variety.ContextSignals
import com.roadtale.core.variety.VarietyEngine

/**
 * RoadTaleEngine — wires the four core components together:
 *
 *   SdkConnectionLayer  (vehicle/location/navi/audio signals in)
 *        -> JourneyStateMachine (what the story is allowed to do)
 *        -> WaypointAnchorScheduler (when scenes advance / pause)
 *        -> VarietyEngine (how reality re-dresses the scene)
 *        -> StoryAudioSink (character voice out, priority-queued)
 *
 * The engine is deliberately UI-free and Android-free: in production an
 * Android front-end drives [tick] from SDK callbacks; here the demo driver
 * drives it from a scripted simulation.
 */
class RoadTaleEngine(
    private val sdk: SdkConnectionLayer,
    val stateMachine: JourneyStateMachine = JourneyStateMachine(),
    val scheduler: WaypointAnchorScheduler = WaypointAnchorScheduler(),
    val variety: VarietyEngine = VarietyEngine()
) {
    private val _log = mutableListOf<String>()
    val eventLog: List<String> get() = _log.toList()

    private var plan: JourneyPlan? = null
    private var currentSceneIndex: Int = -1
    private var secondsSinceSceneStart: Int = 0
    private var companion: CompanionConfig? = null
    private var mode: JourneyMode = JourneyMode.DAILY_CHRONICLE
    private var letters: List<VoiceLetter> = emptyList()
    private var deliveredLetters = mutableSetOf<Int>()
    private var knownPassedWaypoints = mutableSetOf<String>()
    private var restCoachArmed = true
    private var lowBatteryAnnounced = false

    private fun log(line: String) {
        _log += line
        println(line)
    }

    /**
     * Plans a journey: builds the anchor schedule against the live ETA and
     * reports the variation layer for departure conditions.
     */
    fun planJourney(
        mode: JourneyMode,
        companion: CompanionConfig,
        episode: Episode,
        waypoints: List<Waypoint>,
        letters: List<VoiceLetter> = emptyList(),
        departureSignals: ContextSignals = ContextSignals()
    ) {
        this.mode = mode
        this.companion = companion
        this.letters = letters
        sdk.routeWaypoints = waypoints

        val route = sdk.navi.route() ?: error("NaviHelper reports no route — cannot plan a journey")
        plan = scheduler.planForEta(episode, waypoints, route.etaSeconds)
        stateMachine.dispatch(JourneyEvent.JourneyPlanned(route.destinationName))

        val variation = variety.variationFor(departureSignals)
        log("[PLAN] mode=$mode destination='${route.destinationName}' eta=${route.etaSeconds}s " +
            "episode='${episode.title}' scenes=${episode.scenes.size} " +
            "cliffhanger alignment error=${scheduler.cliffhangerAlignmentErrorSec(plan!!)}s")
        log("[PLAN] companion=${companion.avatar} genre=${companion.genre} theme='${companion.visualThemePack}' " +
            "voice=${companion.voice.type}/${companion.voice.tone}/${companion.voice.mood}")
        if (variation.reasons.isNotEmpty()) {
            log("[VARIETY] departure: ${variation.reasons.joinToString("; ")}")
        }
        plan!!.scenes.forEach { s ->
            log("[PLAN]  scene '${s.scene.title}' start=+${s.plannedStartSec}s dur=${s.plannedDurationSec}s anchor=${s.triggerWaypointId ?: "-"}")
        }
    }

    fun startJourney() {
        val t = stateMachine.dispatch(JourneyEvent.JourneyStarted)
        log("[STATE] ${t.from} -> ${t.to} (${t.note})")
        advanceToScene(0, "journey start")
    }

    /**
     * One simulation/observation step. In the car this is called from SDK
     * callbacks; here the demo calls it per scripted step.
     *
     * Order inside a tick is the safety architecture in code form:
     *   1. focus zones (story steps aside before anything else happens)
     *   2. waypoint anchors / letters
     *   3. traffic & charging reactions
     *   4. arrival
     */
    fun tick(elapsedSec: Int, signals: ContextSignals = ContextSignals()) {
        val ctx = sdk.currentContext()
        val currentPlan = plan ?: return
        secondsSinceSceneStart += elapsedSec

        // -- 1) Focus zones: the story pauses BEFORE the zone, at a sentence boundary.
        val nextZone = ctx.route?.upcomingFocusZones?.firstOrNull()
        if (stateMachine.state != JourneyState.STORY_PAUSED_FOCUS &&
            scheduler.upcomingPause(nextZone?.startsInMeters)
        ) {
            val t = stateMachine.dispatch(JourneyEvent.FocusZoneApproaching(nextZone!!.name))
            if (t.accepted) {
                sdk.audio.fadeOutStory()
                log("[SAFETY] focus zone '${nextZone.name}' in ${nextZone.startsInMeters}m -> ${t.from} -> ${t.to}: ${t.note}")
                sdk.audio.guide("[${companion?.avatar}] Careful now — complex interchange ahead. The story will wait right here.")
            }
        }
        if (stateMachine.state == JourneyState.STORY_PAUSED_FOCUS &&
            (nextZone == null || nextZone.startsInMeters < 0)
        ) {
            val t = stateMachine.dispatch(JourneyEvent.FocusZoneCleared("cleared"))
            if (t.accepted) {
                sdk.audio.resumeStory()
                log("[SAFETY] focus zone cleared -> ${t.to}: ${t.note}; one-line recap, then the story resumes")
                sdk.audio.narrate("Recap: our hero had just reached the river gate. And now…")
                stateMachine.pollDeferredChoice()?.let { deferred ->
                    log("[SAFETY] deferred choice '$deferred' is now offered to the passengers")
                    stateMachine.dispatch(JourneyEvent.BranchChoiceOffered(deferred))
                }
            }
        }

        // -- 2) Waypoints: newly passed anchors shift scenes; letters are delivered.
        val newlyPassed = ctx.passedWaypointIds - knownPassedWaypoints
        knownPassedWaypoints += newlyPassed
        for (wpId in newlyPassed) {
            val t = stateMachine.dispatch(JourneyEvent.WaypointPassed(wpId))
            log("[WAYPOINT] passed '$wpId' -> ${t.to} (accepted=${t.accepted})")
            letters.forEachIndexed { i, letter ->
                if (letter.deliverAtWaypointId == wpId && i !in deliveredLetters) {
                    deliveredLetters += i
                    log("[LETTER] voice letter from ${letter.from} delivered at '$wpId' (${letter.durationSec}s)")
                    sdk.audio.narrate("A letter from ${letter.from} has arrived…", AudioPriority.STORY)
                }
            }
            // Ask the scheduler what this progress means for the story timeline.
            val decision = scheduler.decide(
                plan = currentPlan,
                currentSceneIndex = currentSceneIndex,
                newlyPassedWaypointIds = newlyPassed,
                secondsSinceLastSceneStart = secondsSinceSceneStart,
                storyProgressRatio = storyProgress(),
                journeyProgressRatio = journeyProgress(ctx.route?.remainingDistanceMeters, currentPlan),
                nextFocusZoneName = null,
                nextFocusZoneStartsInMeters = null
            )
            applyDecision(decision)
        }

        // -- 3) Traffic & charging reactions via the variety engine.
        val variation = variety.variationFor(signals)
        if (variation.insertSideScene && stateMachine.state == JourneyState.EPISODE_PLAYING) {
            val t = stateMachine.dispatch(JourneyEvent.TrafficJamDetected)
            if (t.accepted) {
                log("[VARIETY] ${variation.reasons.joinToString("; ")} -> ${t.to}: a hidden side episode plays; the main episode is NOT stretched")
                if (variation.offerVoiceGame && mode == JourneyMode.FAMILY_JOURNEY) {
                    log("[VARIETY] low-speed section: family voice-game mode offered to passengers")
                }
                val back = stateMachine.dispatch(JourneyEvent.SideSceneFinished)
                log("[STATE] ${back.from} -> ${back.to}: main episode resumes")
            }
        }
        if (variation.tensionModifier > 0.0 && !lowBatteryAnnounced &&
            stateMachine.state == JourneyState.EPISODE_PLAYING
        ) {
            lowBatteryAnnounced = true
            log("[EV] ${variation.reasons.joinToString("; ")}")
        }
        if (variation.chargingInterlude && stateMachine.state == JourneyState.EPISODE_PLAYING) {
            val t = stateMachine.dispatch(JourneyEvent.ChargingStarted)
            if (t.accepted) {
                log("[EV] charging started -> ${t.to}: a short complete story sized to the charge time plays")
            }
        }
        if (!signals.isCharging && stateMachine.state == JourneyState.CHARGING_INTERLUDE) {
            val t = stateMachine.dispatch(JourneyEvent.ChargingFinished)
            log("[EV] charging finished -> ${t.to}: ${t.note}")
        }

        // -- 4) Rest coach: long continuous driving is answered inside the story.
        // One suggestion per stretch: after a break the driving timer resets,
        // which re-arms the coach (a real break is the only reset).
        if (ctx.vehicle.continuousDrivingMinutes < REST_SUGGESTION_MINUTES) restCoachArmed = true
        if (restCoachArmed &&
            ctx.vehicle.continuousDrivingMinutes >= REST_SUGGESTION_MINUTES &&
            stateMachine.state == JourneyState.EPISODE_PLAYING
        ) {
            restCoachArmed = false
            val restArea = sdk.navi.poisAlongRoute(PoiCategory.REST_AREA).firstOrNull()
            val t = stateMachine.dispatch(JourneyEvent.RestSuggested)
            if (t.accepted) {
                log("[REST] ${ctx.vehicle.continuousDrivingMinutes} min of continuous driving -> the companion suggests " +
                    "'${restArea?.name ?: "a rest area"}' in character, without breaking the story world")
                val done = stateMachine.dispatch(JourneyEvent.RestFinished)
                log("[REST] break taken -> ${done.to}: story resumes")
            }
        }

        // -- 5) Arrival: the episode ends on its cliffhanger, then the celebration.
        if (sdk.navi.hasArrived() && stateMachine.state == JourneyState.EPISODE_PLAYING) {
            val end = stateMachine.dispatch(JourneyEvent.EpisodeFinished)
            log("[ARRIVAL] ${end.from} -> ${end.to}: ${end.note}")
            val arr = stateMachine.dispatch(JourneyEvent.DestinationReached)
            sdk.audio.guide("[${companion?.avatar}] We have arrived — and what a journey it was.")
            log("[ARRIVAL] ${arr.from} -> ${arr.to}: ${arr.note}")
            val sum = stateMachine.dispatch(JourneyEvent.SummaryFinished)
            log("[ARRIVAL] ${sum.from} -> ${sum.to}: journey summary spoken and stored")
        }
    }

    /** Offers a story branch to passengers (never inside a focus zone). */
    fun offerBranchChoice(choiceId: String) {
        val t = stateMachine.dispatch(JourneyEvent.BranchChoiceOffered(choiceId))
        log("[CHOICE] offer '$choiceId' -> ${t.to} (accepted=${t.accepted}): ${t.note}")
    }

    fun passengerChooses(choiceId: String, option: String) {
        val t = stateMachine.dispatch(JourneyEvent.PassengerChoiceMade(choiceId, option))
        log("[CHOICE] passengers picked '$option' -> ${t.to} (accepted=${t.accepted})")
    }

    /**
     * The driver's one-word reply to a POI suggestion. Honored only at a full
     * stop and never in a focus zone — enforced by the state machine; on
     * acceptance the POI becomes a waypoint and the plan is rebuilt.
     */
    fun driverReplies(word: String) {
        val stopped = sdk.vehicle.snapshot().isFullyStopped
        val t = stateMachine.dispatch(JourneyEvent.DriverReplied(word, stopped))
        log("[DRIVER] reply '$word' (fullyStopped=$stopped) -> accepted=${t.accepted}: ${t.note}")
        if (t.accepted && word == "응") {
            sdk.navi.poisAlongRoute(PoiCategory.REST_AREA).firstOrNull()?.let { poi ->
                sdk.navi.addWaypoint(poi)
                log("[DRIVER] detour accepted: '${poi.name}' added as a waypoint; episode plan re-fits the new route")
            }
        }
    }

    private fun applyDecision(decision: ScheduleDecision) {
        when (decision) {
            is ScheduleDecision.AdvanceToScene -> {
                val idx = plan!!.scenes.indexOfFirst { it.scene.id == decision.sceneId }
                if (idx >= 0) advanceToScene(idx, decision.reason)
            }
            is ScheduleDecision.FallbackAdvance -> {
                val idx = plan!!.scenes.indexOfFirst { it.scene.id == decision.sceneId }
                if (idx >= 0) advanceToScene(idx, "fallback: ${decision.reason}")
            }
            is ScheduleDecision.CompressNextScene ->
                log("[SCHEDULE] journey is ahead of the story: scene '${decision.sceneId}' compresses into a ${decision.intoSeconds}s summary narration")
            ScheduleDecision.InsertSideScene ->
                log("[SCHEDULE] story is ahead of the road: side scene will cover the stretch")
            is ScheduleDecision.PauseForFocusZone -> Unit // handled before waypoints in tick()
            ScheduleDecision.KeepPlaying -> Unit
        }
    }

    private fun advanceToScene(index: Int, reason: String) {
        val scheduled = plan!!.scenes[index]
        currentSceneIndex = index
        secondsSinceSceneStart = 0
        stateMachine.currentSceneId = scheduled.scene.id
        val shift = stateMachine.dispatch(JourneyEvent.SceneShiftCompleted(scheduled.scene.id))
        log("[SCENE] -> '${scheduled.scene.title}' (${reason}; shift accepted=${shift.accepted})")
        val tags = buildList {
            if (scheduled.scene.isClimax) add("CLIMAX")
            if (scheduled.scene.isCliffhangerPoint) add("CLIFFHANGER POINT")
        }
        sdk.audio.narrate("Now playing: ${scheduled.scene.title}${if (tags.isEmpty()) "" else " [${tags.joinToString(", ")}]"}")
    }

    private fun storyProgress(): Double {
        val p = plan ?: return 0.0
        if (p.scenes.isEmpty()) return 0.0
        return (currentSceneIndex + 1).toDouble() / p.scenes.size
    }

    private fun journeyProgress(remainingMeters: Int?, plan: JourneyPlan): Double {
        if (remainingMeters == null) return storyProgress()
        val total = plan.etaSeconds // ETA seconds double as the journey-length baseline in the demo.
        if (total <= 0) return 0.0
        return 1.0 - (remainingMeters.toDouble() / (total * 12.0)).coerceIn(0.0, 1.0)
    }

    companion object {
        /** Continuous driving after which the rest coach speaks up (proposal 3-6). */
        const val REST_SUGGESTION_MINUTES = 110
    }
}
