package com.hamilton.stage.performance

import android.os.SystemClock
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.hmd.HMDTrackingData
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import kotlin.math.*

internal data class ActorEnvelope(val role:String,val min:Vector3,val max:Vector3)
internal data class PerformanceFrame(val eye:Vector3,val center:Vector3,val yaw:Float,val width:Float)

/** Metre-space composition from actual cast bounds. Small side angles avoid other actors. */
internal fun performanceFrame(subjects:List<ActorEnvelope>,others:List<ActorEnvelope>,bioHeight:Float):PerformanceFrame {
    require(subjects.isNotEmpty())
    val min=Vector3(subjects.minOf{it.min.x},subjects.minOf{it.min.y},subjects.minOf{it.min.z})
    val max=Vector3(subjects.maxOf{it.max.x},subjects.maxOf{it.max.y},subjects.maxOf{it.max.z})
    val center=Vector3((min.x+max.x)/2f,min.y,(min.z+max.z)/2f)
    val width=max.x-min.x
    val distance=maxOf(5.8f,(width/2f+.5f)/tan(Math.toRadians(25.0)).toFloat(),
        (max.y+bioHeight+BIOGRAPHY_HEAD_GAP-(min.y+2.15f))/tan(Math.toRadians(24.0)).toFloat())
    return listOf(0f,-18f,18f,-30f,30f).map { yaw ->
        val normal=rotateY(Vector3(0f,0f,1f),yaw)
        val eye=Vector3(center.x+normal.x*distance,min.y+2.15f,max.z+normal.z*distance)
        val occlusions=subjects.sumOf { actor ->
            listOf(.55f,.9f).sumOf { height ->
                val aim=Vector3((actor.min.x+actor.max.x)/2f,actor.min.y+(actor.max.y-actor.min.y)*height,(actor.min.z+actor.max.z)/2f)
                others.count { blocker ->
                    val hit=hitBox(eye,aim-eye,StagePoint(blocker.min.x,blocker.min.y,blocker.min.z),StagePoint(blocker.max.x,blocker.max.y,blocker.max.z))
                    hit!=null && hit<.98f
                }
            }
        }
        PerformanceFrame(eye,center,yaw,width) to (occlusions*100f+abs(yaw))
    }.minBy{it.second}.first
}

internal fun rotateY(p:Vector3,degrees:Float):Vector3 {
    val a=Math.toRadians(degrees.toDouble());val c=cos(a).toFloat();val s=sin(a).toFloat()
    return Vector3(c*p.x+s*p.z,p.y,-s*p.x+c*p.z)
}

/** Move the theater, retaining OS head tracking and a level horizon. Capture head heading once;
 * never attach the world to the viewer or steer their view continuously during a song. */
class PerformanceViewRig(private val world:Entity) {
    private val transform=world.components[TransformComponent::class.java]!!
    private val overview=transform.position
    private val hmd=HMDTrackingProvider()
    @Volatile private var sample:HMDTrackingData?=null
    @Volatile private var arrival=0L
    private val listener=DataProvider.DataListener<HMDTrackingData>{sample=it;arrival=SystemClock.elapsedRealtime()}
    private var running=false
    private var start=overview
    private var target=overview
    private var startYaw=0f
    private var targetYaw=0f
    private var yaw=0f
    private var elapsed=1.2f
    var subjectYaw=0f;private set
    var framingEye:Vector3?=null;private set
    val moving get()=elapsed<1.2f
    fun resume(){if(!running){running=true;hmd.addListener(listener);hmd.start()}}
    fun pause(){if(running){running=false;hmd.removeListener(listener);hmd.stop();sample=null;arrival=0L}}
    internal fun focus(frame:PerformanceFrame){
        framingEye=frame.eye
        val anchor=world.getParent()!!
        val pose=sample?.takeIf{SystemClock.elapsedRealtime()-arrival<500L}?.hmdPose
        val head=pose?.let{anchor.convertPositionFrom(it.position,null)} ?: Vector3(0f,1.65f,0f)
        val direction=pose?.let{
            anchor.convertPositionFrom(it.position+it.rotation.rotateVector(Vector3(0f,0f,-1f)),null)-head
        } ?: Vector3(0f,0f,-1f)
        val heading=if(hypot(direction.x,direction.z)>.1f)
            Math.toDegrees(atan2(-direction.x.toDouble(),-direction.z.toDouble())).toFloat() else 0f
        subjectYaw=frame.yaw
        animate(head-rotateY(frame.eye,heading-frame.yaw),heading-frame.yaw)
    }
    fun reset(){framingEye=null;subjectYaw=0f;animate(overview,0f)}
    private fun animate(position:Vector3,rotation:Float){
        start=transform.position;target=position;startYaw=yaw
        targetYaw=yaw+((rotation-yaw+540f)%360f)-180f;elapsed=0f
    }
    fun tick(dt:Float){
        if(!moving)return
        elapsed=(elapsed+dt).coerceAtMost(1.2f)
        val t=elapsed/1.2f;val ease=t*t*(3f-2f*t)
        yaw=startYaw+(targetYaw-startYaw)*ease
        transform.setPosition(start+(target-start)*ease).setEulerAngles(EulerAngles(0f,yaw,0f))
    }
    fun close(){pause()}
}
