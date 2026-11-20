package com.hamilton.stage

import android.util.Log
import com.hamilton.stage.performance.LyricCue
import com.hamilton.stage.performance.PerformanceSystem
import com.pico.spatial.core.ecs.SceneUpdateContext
import java.util.Locale

/** Reports the two native update intervals bracketing a displayed cue change. */
class CaptionFrameProbe : com.pico.spatial.core.ecs.System() {
    private var previousTime = 0L
    private var previousCue: LyricCue? = null
    private var openingInterval = 0f

    override fun update(context: SceneUpdateContext) {
        val director = PerformanceSystem.active ?: return
        if (context.scene != director.world?.scene) return
        val now = java.lang.System.nanoTime()
        val interval = (now - previousTime) / 1_000_000f
        val cue = director.captions?.displayedCue
        if (openingInterval > 0f) {
            Log.i("HamiltonPerformance", String.format(Locale.ROOT,
                "CUE_FRAME role=%s start=%s beforeMs=%.2f afterMs=%.2f",
                director.ui.selected?.role, previousCue?.start, openingInterval, interval))
        }
        openingInterval = if (previousTime != 0L && cue != null && cue != previousCue) interval else 0f
        previousCue = cue
        previousTime = now
    }
}
