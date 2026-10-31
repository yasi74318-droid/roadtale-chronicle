package com.roadtale.core.state

/**
 * Journey State Machine (여정 상태 머신) — the heart of the RoadTale engine.
 *
 * Every vehicle event (waypoint passed, focus zone approaching, traffic jam,
 * charging started, destination reached...) is dispatched here, and the state
 * machine alone decides what the story is allowed to do. Both modes
 * (Daily Chronicle and Family Journey) run on this same machine
 * (proposal 5-1: "여정 상태 머신이 차량 이벤트를 받아 콘텐츠 재생을 제어하는 코어 엔진").
 *
 * The driver-safety architecture (proposal 3-9) is encoded as transition rules:
 *  - In [JourneyState.STORY_PAUSED_FOCUS] the story is silent; only character
 *    navigation guidance is heard, and NO interactive event (choice, quiz) may
 *    be scheduled — incoming choice requests are deferred, never dropped on the
 *    driver.
 *  - A driver reply is only honored in [JourneyState.EPISODE_PLAYING] /
 *    [JourneyState.WAYPOINT_SCENE_SHIFT] when the vehicle is fully stopped;
 *    the machine itself cannot move the vehicle, so the caller passes the
 *    stop state with the event (see [JourneyEvent.DriverReplied]).
 */

enum class JourneyState {
    /** No journey in progress. */
    IDLE,

    /** Route known, episode planned against ETA, not yet rolling. */
    JOURNEY_PLANNED,

    /** Story is playing and synced to the journey. */
    EPISODE_PLAYING,

    /**
     * Focus zone (complex interchange / exit / U-turn / destination approach):
     * the story has politely stepped aside at a sentence boundary; only
     * guidance audio remains. Proposal 3-9 "집중 구간 자동 일시정지".
     */
    STORY_PAUSED_FOCUS,

    /** A waypoint was just passed; the story is shifting to the anchored scene. */
    WAYPOINT_SCENE_SHIFT,

    /** A side scene / hidden episode inserted because traffic stretched the journey. */
    SIDE_SCENE,

    /** A story branch choice is open — passengers only, never the driver. */
    CHOICE_PENDING,

    /** Rest break suggested by the in-story rest coach; vehicle stopped. */
    REST_BREAK,

    /** EV charging interlude: a short self-contained story sized to the charge time. */
    CHARGING_INTERLUDE,

    /** The episode ended exactly at the destination on its cliffhanger point. */
    CLIFFHANGER,

    /** Destination reached; arrival celebration + voice journey summary playing. */
    ARRIVED,

    /** Journey fully wrapped up (summary stored to the journey collection). */
    JOURNEY_COMPLETE
}

/** Events the vehicle/SDK layer (or the story engine itself) feeds the machine. */
sealed interface JourneyEvent {
    data class JourneyPlanned(val destinationName: String) : JourneyEvent
    data object JourneyStarted : JourneyEvent
    data class WaypointPassed(val waypointId: String) : JourneyEvent
    data class SceneShiftCompleted(val sceneId: String) : JourneyEvent
    data class FocusZoneApproaching(val zoneName: String) : JourneyEvent
    data class FocusZoneCleared(val zoneName: String) : JourneyEvent
    data object TrafficJamDetected : JourneyEvent
    data object TrafficCleared : JourneyEvent
    data object SideSceneFinished : JourneyEvent
    data class BranchChoiceOffered(val choiceId: String) : JourneyEvent
    data class PassengerChoiceMade(val choiceId: String, val option: String) : JourneyEvent

    /**
     * The driver's single-word reply to a POI detour suggestion (proposal 3-3).
     * [vehicleFullyStopped] is evaluated by the caller from the Vehicle SDK
     * snapshot at the moment of the reply.
     */
    data class DriverReplied(val word: String, val vehicleFullyStopped: Boolean) : JourneyEvent

    data object RestSuggested : JourneyEvent
    data object RestFinished : JourneyEvent
    data object ChargingStarted : JourneyEvent
    data object ChargingFinished : JourneyEvent
    data object EpisodeFinished : JourneyEvent
    data object DestinationReached : JourneyEvent
    data object SummaryFinished : JourneyEvent
}

/** Outcome of one dispatch: where we were, where we are, and why it matters. */
data class Transition(
    val from: JourneyState,
    val to: JourneyState,
    val event: JourneyEvent,
    /** True if the event was accepted; false if it was deferred or ignored. */
    val accepted: Boolean,
    val note: String = ""
)

class JourneyStateMachine {

    var state: JourneyState = JourneyState.IDLE
        private set

    private val _history = mutableListOf<Transition>()
    val history: List<Transition> get() = _history.toList()

    /** Choice requests that arrived during a focus zone; replayed on resume. */
    private val deferredChoices = ArrayDeque<String>()

    /** Scene id to resume from after a focus pause (the bookmark, proposal 5-3). */
    var resumeBookmarkSceneId: String? = null
        private set

    var currentSceneId: String? = null

    fun dispatch(event: JourneyEvent): Transition {
        val from = state
        val (toAccepted, note) = when (event) {
            // each branch yields (toState to accepted) to note
            is JourneyEvent.JourneyPlanned ->
                if (from == JourneyState.IDLE) JourneyState.JOURNEY_PLANNED to true to "episode planned against ETA"
                else rejected("journey already planned")

            JourneyEvent.JourneyStarted ->
                if (from == JourneyState.JOURNEY_PLANNED) JourneyState.EPISODE_PLAYING to true to "story starts with the journey"
                else rejected("no planned journey")

            is JourneyEvent.WaypointPassed ->
                if (from.storyAudible()) JourneyState.WAYPOINT_SCENE_SHIFT to true to "waypoint ${event.waypointId} triggers its anchored scene"
                else rejected("story not audible; waypoint noted only")

            is JourneyEvent.SceneShiftCompleted -> {
                currentSceneId = event.sceneId
                if (from == JourneyState.WAYPOINT_SCENE_SHIFT || from == JourneyState.EPISODE_PLAYING)
                    JourneyState.EPISODE_PLAYING to true to "now playing scene ${event.sceneId}"
                else rejected("no scene shift in progress")
            }

            is JourneyEvent.FocusZoneApproaching -> {
                resumeBookmarkSceneId = currentSceneId
                if (from.storyAudible() || from == JourneyState.CHOICE_PENDING)
                    JourneyState.STORY_PAUSED_FOCUS to true to
                        "story fades out at the sentence boundary; guidance only (bookmark: ${resumeBookmarkSceneId ?: "start"})"
                else rejected("story already silent")
            }

            is JourneyEvent.FocusZoneCleared ->
                if (from == JourneyState.STORY_PAUSED_FOCUS) {
                    val replay = if (deferredChoices.isNotEmpty())
                        "deferred choice '${deferredChoices.first()}' may now be offered to passengers" else "resume from bookmark"
                    JourneyState.EPISODE_PLAYING to true to replay
                } else rejected("not in a focus pause")

            JourneyEvent.TrafficJamDetected ->
                if (from == JourneyState.EPISODE_PLAYING) JourneyState.SIDE_SCENE to true to
                    "journey stretched: side scene inserted instead of stretching the main episode"
                else rejected("side scenes only insert from main playback")

            JourneyEvent.TrafficCleared, JourneyEvent.SideSceneFinished ->
                if (from == JourneyState.SIDE_SCENE) JourneyState.EPISODE_PLAYING to true to "back to the main episode"
                else rejected("no side scene playing")

            is JourneyEvent.BranchChoiceOffered ->
                when (from) {
                    JourneyState.EPISODE_PLAYING ->
                        JourneyState.CHOICE_PENDING to true to "branch offered to passengers"
                    JourneyState.STORY_PAUSED_FOCUS -> {
                        // Safety rule: never schedule interaction inside/near a focus zone.
                        deferredChoices.addLast(event.choiceId)
                        from to false to "choice '${event.choiceId}' DEFERRED — interaction is never scheduled in a focus zone"
                    }
                    else -> rejected("choices only at scene boundaries during playback")
                }

            is JourneyEvent.PassengerChoiceMade ->
                if (from == JourneyState.CHOICE_PENDING) JourneyState.EPISODE_PLAYING to true to
                    "passengers chose '${event.option}' — story branches"
                else rejected("no open choice")

            is JourneyEvent.DriverReplied ->
                when {
                    from == JourneyState.STORY_PAUSED_FOCUS ->
                        from to false to "driver replies are never even asked for in a focus zone"
                    !event.vehicleFullyStopped ->
                        from to false to "driver reply IGNORED — only honored at a complete stop"
                    else ->
                        from to true to "driver's one-word reply '${event.word}' accepted at a full stop"
                }

            JourneyEvent.RestSuggested ->
                if (from.storyAudible()) JourneyState.REST_BREAK to true to "rest coach (in character) suggests a break"
                else rejected("rest suggestion needs active playback")

            JourneyEvent.RestFinished ->
                if (from == JourneyState.REST_BREAK) JourneyState.EPISODE_PLAYING to true to "journey story resumes after the break"
                else rejected("not on a rest break")

            JourneyEvent.ChargingStarted ->
                if (from.storyAudible() || from == JourneyState.REST_BREAK)
                    JourneyState.CHARGING_INTERLUDE to true to "short complete story sized to the charging time"
                else rejected("charging interlude needs an active journey")

            JourneyEvent.ChargingFinished ->
                if (from == JourneyState.CHARGING_INTERLUDE) JourneyState.EPISODE_PLAYING to true to "main journey story resumes"
                else rejected("not charging")

            JourneyEvent.EpisodeFinished ->
                if (from == JourneyState.EPISODE_PLAYING) JourneyState.CLIFFHANGER to true to
                    "episode ends on its cliffhanger point — tomorrow's drive is already awaited"
                else rejected("episode not playing")

            JourneyEvent.DestinationReached ->
                if (from == JourneyState.CLIFFHANGER || from.storyAudible() || from == JourneyState.STORY_PAUSED_FOCUS)
                    JourneyState.ARRIVED to true to "arrival is celebrated inside the story; voice summary follows"
                else rejected("no active journey to arrive from")

            JourneyEvent.SummaryFinished ->
                if (from == JourneyState.ARRIVED) JourneyState.JOURNEY_COMPLETE to true to "journey stored in the collection with its badge"
                else rejected("no arrival summary playing")
        }

        val (to, accepted) = toAccepted
        state = to
        val transition = Transition(from, to, event, accepted, note)
        _history += transition
        return transition
    }

    /** Pops the oldest deferred choice after a focus zone clears, if any. */
    fun pollDeferredChoice(): String? =
        if (deferredChoices.isEmpty()) null else deferredChoices.removeFirst()

    private fun JourneyState.storyAudible(): Boolean =
        this == JourneyState.EPISODE_PLAYING ||
            this == JourneyState.WAYPOINT_SCENE_SHIFT ||
            this == JourneyState.SIDE_SCENE ||
            this == JourneyState.CHARGING_INTERLUDE

    private fun rejected(note: String): Pair<Pair<JourneyState, Boolean>, String> =
        Pair(Pair(state, false), note)
}

// The audible-check helper on the state enum itself (used by the engine).
val JourneyState.isStoryAudible: Boolean
    get() = this == JourneyState.EPISODE_PLAYING ||
        this == JourneyState.WAYPOINT_SCENE_SHIFT ||
        this == JourneyState.SIDE_SCENE ||
        this == JourneyState.CHOICE_PENDING ||
        this == JourneyState.CHARGING_INTERLUDE
