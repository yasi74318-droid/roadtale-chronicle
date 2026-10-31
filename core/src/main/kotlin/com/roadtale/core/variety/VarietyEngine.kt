package com.roadtale.core.variety

/**
 * Variety Engine (컨텍스트 변주 엔진) — proposal 3-4 / 5-3.
 *
 * The story lives in the same world as today's road. This engine reads the
 * context signals (weather, traffic, time of day, EV state) and decides the
 * "variation layer" for the current scene. Variations never change the main
 * plot structure — they are a layer on top of it (scene variant, soundscape,
 * side-scene insertion, tension), which keeps production cost under control
 * while making every journey feel alive.
 */

enum class Weather { CLEAR, RAIN, SNOW, FOG }

enum class TrafficLevel { SMOOTH, SLOW, JAMMED }

/** Raw context signals, assembled from the SDK layer + a weather API. */
data class ContextSignals(
    val weather: Weather = Weather.CLEAR,
    /** Rate of ETA growth; >1.0 means the journey is getting longer (traffic). */
    val etaGrowthRate: Double = 1.0,
    val isNight: Boolean = false,
    /** EV battery percent; null for non-EV. */
    val evBatteryPercent: Int? = null,
    val isCharging: Boolean = false
) {
    val trafficLevel: TrafficLevel
        get() = when {
            etaGrowthRate >= 1.35 -> TrafficLevel.JAMMED
            etaGrowthRate >= 1.12 -> TrafficLevel.SLOW
            else -> TrafficLevel.SMOOTH
        }
}

/** The variation layer to apply to the scene that is about to play. */
data class VariationPlan(
    /** Scene variant to play instead of the base scene, if any. */
    val sceneVariant: String? = null,
    /** Soundscape bed under the narration (e.g. "rain on the roof"). */
    val soundscape: String? = null,
    /** Insert a side scene / hidden episode (traffic) instead of stretching the main plot. */
    val insertSideScene: Boolean = false,
    /** Offer the family voice-game mode (low-speed jam sections, family mode). */
    val offerVoiceGame: Boolean = false,
    /** Story tension modifier driven by the EV battery "energy gauge" of a character. */
    val tensionModifier: Double = 0.0,
    /** During charging: play a short, complete story sized to the remaining charge time. */
    val chargingInterlude: Boolean = false,
    /** Human-readable reasons, for logging and for the driver's one-line display. */
    val reasons: List<String> = emptyList()
)

class VarietyEngine(
    private val lowBatteryThresholdPercent: Int = 25
) {
    fun variationFor(signals: ContextSignals): VariationPlan {
        val reasons = mutableListOf<String>()
        var variant: String? = null
        var soundscape: String? = null
        var tension = 0.0

        // Weather variation (proposal 3-4): a rainy day gets the rainy-day variant.
        when (signals.weather) {
            Weather.RAIN -> {
                variant = "rainy-day variant"
                soundscape = "rain on the roof"
                reasons += "rain: rainy-day scene variant + rain soundscape"
            }
            Weather.SNOW -> {
                variant = "snowy-day variant"
                soundscape = "muffled winter wind"
                reasons += "snow: snowy-day scene variant"
            }
            Weather.FOG -> {
                soundscape = "distant fog horn"
                reasons += "fog: fog soundscape, slower narration pace"
            }
            Weather.CLEAR -> Unit
        }

        // Night variation: the same episode, re-dressed for the night.
        if (signals.isNight) {
            variant = listOfNotNull(variant, "night version").joinToString(" + ")
            reasons += "night: night-version adaptation (tone, music, some lines)"
        }

        // Traffic: never stretch the main episode — insert side content instead.
        val jammed = signals.trafficLevel == TrafficLevel.JAMMED
        if (jammed) reasons += "traffic jam (ETA x${"%.2f".format(signals.etaGrowthRate)}): side scene inserted, family voice game offered"

        // EV: the battery becomes a character's energy gauge; low battery raises tension
        // and makes the narrator weave the nearest fast charger into the story.
        val battery = signals.evBatteryPercent
        if (battery != null && battery < lowBatteryThresholdPercent && !signals.isCharging) {
            tension = 0.4
            reasons += "EV battery at $battery%: character energy gauge low — tension up, nearest fast charger woven into the story"
        }
        if (signals.isCharging) reasons += "charging: interlude mode — a short complete story sized to the charge time"

        return VariationPlan(
            sceneVariant = variant,
            soundscape = soundscape,
            insertSideScene = jammed,
            offerVoiceGame = jammed,
            tensionModifier = tension,
            chargingInterlude = signals.isCharging,
            reasons = reasons
        )
    }
}
