package com.hamilton.stage.performance

import android.util.Log
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.gesture.data.InteractionKind

/** A valid native gaze-pinch tap may have no entity UUID on physical PICO devices.
 * Keep the SDK tap recognizer (release/cancellation), then resolve only that missing target.
 * Ordinary UI, controller and direct-touch events must never select a gazed-at actor instead. */
internal fun PerformanceDirector.selectFromTap(
    target:Entity?, kind:InteractionKind, gazeTarget:()->String?={hoverTracking.tapTarget()}
):Boolean {
    if(!ready || closed)return false
    val role=if(target!=null)roleFor(target)
        else if(kind==InteractionKind.GazePinch)gazeTarget() else null
    Log.i("HamiltonTap","TAP received nativeTarget=${target!=null} resolved=${role!=null}")
    if(role==null || role !in actors)return false
    select(role)
    return ui.selected?.role==role && ui.phase!=PerformancePhase.ERROR
}

/** Pick the visible name capsule or the nearest actual model, in the moving theater frame.
 * The capsule occupies 1.5 x .312 metres; do not select it simply because it is still fading. */
internal fun PerformanceDirector.hitTapRole(origin:Vector3,end:Vector3):String? {
    val model=hitRole(origin,end)
    val card=nameCard ?: return model
    val role=ui.hoverCardRole?.takeIf{card.enabled && ui.hoverCardAlpha>.01f} ?: return model
    val theater=world ?: return model
    val center=card.convertPositionTo(Vector3.ZERO,theater)
    val a=theater.convertPositionFrom(origin,null)
    val b=theater.convertPositionFrom(end,null)
    val hit=hitBox(a,b-a,StagePoint(center.x-.75f,center.y-.156f,center.z-.01f),
        StagePoint(center.x+.75f,center.y+.156f,center.z+.01f)) ?: return model
    // Preserve occlusion by any actor in front of the card.
    val modelHit=model?.let{actors[it]}?.let {
        val start=it.root.convertPositionFrom(origin,null)
        val finish=it.root.convertPositionFrom(end,null)
        hitBox(start,finish-start,it.member.min,it.member.max)
    }
    return if(modelHit==null || hit<modelHit)role else model
}
