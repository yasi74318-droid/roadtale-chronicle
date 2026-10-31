package com.roadtale.core.scheduler

import com.roadtale.core.model.Episode
import com.roadtale.core.model.Scene
import com.roadtale.core.model.Waypoint
import kotlin.math.abs

/**
 * Waypoint Anchor Scheduler (지점 앵커링 스케줄러) — proposal 5-3.
 *
 * Responsibilities:
 *  1. Build the scene<->waypoint mapping for an episode on a concrete route:
 *     scenes that name an anchor waypoint fire when the vehicle passes it;
 *     unanchored scenes fill the stretches in between.
 *  2. Decide, as the journey progresses, when to advance the story: a passed
 *     anchor waypoint always wins, but if traffic stretches the gap between
 *     two waypoints, a time-based fallback trigger keeps the story moving.
 *  3. Keep the auto-pause promise: focus zones (complex maneuvers, exits,
 *     destination approach) are looked up ahead of time so the engine can
 *     fade the story out at a sentence boundary BEFORE the zone starts
 *     (see [upcomingPause]).
 *  4. Align the cliffhanger with the destination: scale scene durations so
 *     the episode's cliffhanger point lands exactly on arrival
 *     (see [planForEta]).
 */

/** A scene placed on the journey: when it should start and how long it gets. */
data class ScheduledScene(
    val scene: Scene,
    /** Waypoint that triggers this scene, if anchored. */
    val triggerWaypointId: String?,
    /** Planned start, seconds after journey start. */
    val plannedStartSec: Int,
    /** Duration after ETA alignment (may differ from the scene's nominal length). */
    val plannedDurationSec: Int
)

/** The full playback plan for one journey. */
data class JourneyPlan(
    val episode: Episode,
    val etaSeconds: Int,
    val scenes: List<ScheduledScene>
) {
    val cliffhangerScene: ScheduledScene? get() = scenes.lastOrNull { it.scene.isCliffhangerPoint }
}

/** What the engine should do next, decided from live progress. */
sealed interface ScheduleDecision {
    /** Keep playing the current scene. */
    data object KeepPlaying : ScheduleDecision

    /** A waypoint was passed: shift to its anchored scene now. */
    data class AdvanceToScene(val sceneId: String, val reason: String) : ScheduleDecision

    /** Gap between waypoints grew too long: time-based fallback trigger. */
    data class FallbackAdvance(val sceneId: String, val reason: String) : ScheduleDecision

    /** Journey is running long: insert a side scene instead of stretching the episode. */
    data object InsertSideScene : ScheduleDecision

    /** Journey is running short: compress the next scene into a summary narration. */
    data class CompressNextScene(val sceneId: String, val intoSeconds: Int) : ScheduleDecision

    /** A focus zone starts within the look-ahead distance: pause the story. */
    data class PauseForFocusZone(val zoneName: String, val startsInMeters: Int) : ScheduleDecision
}

class WaypointAnchorScheduler(
    /** If no waypoint fires within this gap, time takes over (proposal 5-3, rule 2). */
    private val maxSilentGapSec: Int = 420,
    /** How far ahead focus zones are announced to the engine, meters. */
    private val focusLookAheadMeters: Int = 600
) {

    /**
     * Builds the playback plan: anchors scenes to their waypoints and scales
     * durations so the whole episode (ending on its cliffhanger) matches ETA.
     */
    fun planForEta(episode: Episode, waypoints: List<Waypoint>, etaSeconds: Int): JourneyPlan {
        require(episode.scenes.isNotEmpty()) { "episode has no scenes" }
        val waypointIds = waypoints.map { it.id }.toSet()
        val scale = etaSeconds.toDouble() / episode.nominalDurationSec

        var cursor = 0
        val scheduled = episode.scenes.map { scene ->
            val duration = (scene.baseDurationSec * scale).toInt().coerceAtLeast(MIN_SCENE_SEC)
            val anchor = scene.anchorWaypointId?.takeIf { it in waypointIds }
            ScheduledScene(scene, anchor, cursor, duration).also { cursor += duration }
        }
        return JourneyPlan(episode, etaSeconds, scheduled)
    }

    /**
     * Live decision from progress signals. Priority order is deliberate:
     * safety (focus zone) > waypoint anchor > journey-length correction
     * (side scene / compression) > time fallback > keep playing.
     */
    fun decide(
        plan: JourneyPlan,
        currentSceneIndex: Int,
        newlyPassedWaypointIds: Set<String>,
        secondsSinceLastSceneStart: Int,
        storyProgressRatio: Double,
        journeyProgressRatio: Double,
        nextFocusZoneName: String?,
        nextFocusZoneStartsInMeters: Int?
    ): ScheduleDecision {
        // 1) Safety first: a focus zone is near -> the story steps aside.
        if (nextFocusZoneName != null && nextFocusZoneStartsInMeters != null &&
            nextFocusZoneStartsInMeters <= focusLookAheadMeters
        ) {
            return ScheduleDecision.PauseForFocusZone(nextFocusZoneName, nextFocusZoneStartsInMeters)
        }

        val nextIndex = currentSceneIndex + 1
        val next = plan.scenes.getOrNull(nextIndex) ?: return ScheduleDecision.KeepPlaying

        // 2) A passed waypoint with an anchored scene always triggers the shift.
        if (next.triggerWaypointId != null && next.triggerWaypointId in newlyPassedWaypointIds) {
            return ScheduleDecision.AdvanceToScene(next.scene.id, "anchor waypoint '${next.triggerWaypointId}' passed")
        }

        // 3) Journey vs. story drift correction (proposal 5-3, journey-sync engine rule 2).
        val drift = storyProgressRatio - journeyProgressRatio
        if (drift > DRIFT_TOLERANCE && next.scene.anchorWaypointId == null) {
            // Story is ahead of the road: the journey stretched (traffic) -> side scene.
            return ScheduleDecision.InsertSideScene
        }
        if (drift < -DRIFT_TOLERANCE && next.scene.anchorWaypointId == null) {
            // Road is ahead of the story: compress the filler scene into a summary.
            return ScheduleDecision.CompressNextScene(next.scene.id, SUMMARY_SCENE_SEC)
        }

        // 4) Time-based fallback: no anchor fired for too long.
        if (secondsSinceLastSceneStart >= maxSilentGapSec && next.triggerWaypointId == null) {
            return ScheduleDecision.FallbackAdvance(next.scene.id, "no waypoint for ${secondsSinceLastSceneStart}s — time trigger")
        }

        return ScheduleDecision.KeepPlaying
    }

    /**
     * True when [zoneStartsInMeters] is close enough that the engine must
     * start the fade-out at the next sentence boundary.
     */
    fun upcomingPause(zoneStartsInMeters: Int?): Boolean =
        zoneStartsInMeters != null && zoneStartsInMeters <= focusLookAheadMeters

    /** Sanity metric used by tests/demos: planned cliffhanger end vs. ETA. */
    fun cliffhangerAlignmentErrorSec(plan: JourneyPlan): Int {
        val cliff = plan.cliffhangerScene ?: return Int.MAX_VALUE
        return abs((cliff.plannedStartSec + cliff.plannedDurationSec) - plan.etaSeconds)
    }

    companion object {
        const val MIN_SCENE_SEC = 30
        const val SUMMARY_SCENE_SEC = 45
        const val DRIFT_TOLERANCE = 0.15
    }
}
