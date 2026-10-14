package com.hamilton.stage.performance

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.eye.EyeTrackingProvider
import com.pico.spatial.tracking.eye.EyeTrackingData
import com.pico.spatial.tracking.eye.EyePose
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import com.pico.spatial.tracking.controller.ControllerTrackingData
import kotlin.math.*

const val EYE_PERMISSION="com.picovr.permission.EYE_TRACKING"

/** No gaze logging or storage. The platform permission is requested through its own dialog. */
@Composable
fun StageHoverPermission(director:PerformanceDirector) {
    val context=LocalContext.current
    DisposableEffect(context,director) {
        var current:Context?=context
        while(current is ContextWrapper && current !is ComponentActivity) current=current.baseContext
        val activity=current as? ComponentActivity
        val preferences=context.getSharedPreferences("stage-permissions",Context.MODE_PRIVATE)
        val granted=ContextCompat.checkSelfPermission(context,EYE_PERMISSION)==PackageManager.PERMISSION_GRANTED
        val launcher=activity?.activityResultRegistry?.register("hamilton-hover-eye",ActivityResultContracts.RequestPermission()) { director.hoverTracking.refreshPermission() }
        if(!granted && !preferences.getBoolean("eye-requested",false) && launcher!=null) {
            preferences.edit().putBoolean("eye-requested",true).apply()
            launcher.launch(EYE_PERMISSION)
        }
        director.hoverTracking.refreshPermission()
        onDispose { launcher?.unregister() }
    }
}

/** Timing is based on monotonic wall time, independent of rendering frame rate. */
class HoverTransition {
    var role:String?=null;private set
    var cardRole:String?=null;private set
    var cardAlpha=0f;private set
    var highlight=0f;private set
    private var enteredAt=0L
    private var lastAt=0L
    fun update(target:String?,now:Long) {
        val dt=if(lastAt==0L)0f else (now-lastAt).coerceIn(0L,100L)/1000f
        lastAt=now
        if(target!=role) { role=target;enteredAt=now;highlight=0f;if(target!=null){cardAlpha=0f;cardRole=target} }
        val age=now-enteredAt
        val reveal=target!=null && age>=750L
        val desired=if(reveal)1f else 0f
        cardAlpha=moveTowards(cardAlpha,desired,dt/.24f)
        if(cardAlpha==0f && target==null)cardRole=null
        highlight=if(target==null)0f else if(age<750L)(age/120f).coerceIn(0f,1f) else (1f-(age-750L)/260f).coerceIn(0f,1f)
    }
    fun clear(){role=null;cardRole=null;cardAlpha=0f;highlight=0f;lastAt=0L}
}

internal data class HoverInput(val role:String?,val source:String) {
    val tracked get()=source!="native"
}

/** A connected/resting controller must not steal the gaze-pinching user's dwell.
 * A valid gaze looking away also clears hover, even if a resting controller hits a model. */
internal fun resolveHoverInput(eye:EyePose?,controllers:ControllerTrackingData?,hit:(Vector3,Vector3)->String?):HoverInput {
    fun ray(position:Vector3,direction:Vector3)=hit(position,position+direction)
    if(eye!=null)return HoverInput(ray(eye.position,eye.rotation.rotateVector(Vector3(0f,0f,-1f))),"eye")
    val poses=listOfNotNull(controllers?.right,controllers?.left)
    if(poses.isNotEmpty())return HoverInput(poses.firstNotNullOfOrNull{
        ray(it.position,it.rotation.rotateVector(Vector3(0f,0f,-1f)))
    },"controller")
    return HoverInput(null,"native")
}

/** Real gaze / controller rays in Stage coordinates. Providers never modify ECS from callbacks. */
class StageHoverTracking(private val context:Context,private val director:PerformanceDirector) {
    private val eyes=EyeTrackingProvider()
    private val controllers=ControllerTrackingProvider()
    private var running=false
    private var eyeStarted=false
    private var allowed=false
    @Volatile private var eye:EyeTrackingData?=null
    @Volatile private var eyeArrival=0L
    @Volatile private var controller:ControllerTrackingData?=null
    @Volatile private var controllerArrival=0L
    private val eyeListener=DataProvider.DataListener<EyeTrackingData>{ eye=it;eyeArrival=SystemClock.elapsedRealtime() }
    private val controllerListener=DataProvider.DataListener<ControllerTrackingData>{controller=it;controllerArrival=SystemClock.elapsedRealtime()}
    private var lastPoll=0L
    private var lastRole:String?=null
    private var lastHit=0L
    private var healthAt=0L
    private var health=""
    fun refreshPermission(){
        allowed=ContextCompat.checkSelfPermission(context,EYE_PERMISSION)==PackageManager.PERMISSION_GRANTED
        if(running && allowed && !eyeStarted){eyes.start();eyeStarted=true}
        if(!allowed && eyeStarted){eyes.stop();eyeStarted=false;eye=null;eyeArrival=0}
    }
    fun resume(){
        if(running)return
        running=true
        eyes.addListener(eyeListener);controllers.addListener(controllerListener)
        controllers.start();refreshPermission()
    }
    fun pause(){
        if(!running)return
        running=false;eyes.removeListener(eyeListener);controllers.removeListener(controllerListener)
        eyes.stop();controllers.stop();eyeStarted=false;eye=null;controller=null;eyeArrival=0;controllerArrival=0;lastRole=null;lastHit=0L
        director.updateHover(null,false,SystemClock.elapsedRealtime())
    }
    fun poll(now:Long){
        if(!running||!director.ready||now-lastPoll<30L)return
        lastPoll=now
        val e=eye?.takeIf{allowed&&now-eyeArrival<180L&&it.timestamp>0L}
        val c=controller?.takeIf{now-controllerArrival<180L}
        val input=resolveHoverInput(e?.eyePose,c,director::hitRole)
        var role=input.role
        if(role!=null){lastRole=role;lastHit=now}
        // A short blink does not repeatedly restart the dwell timer; moving to another actor does.
        if(role==null && input.tracked && now-lastHit<90L)role=lastRole
        director.updateHover(role,input.tracked,now)
        // Debug availability only: never emit gaze vectors, target identity or a gaze history.
        if(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE!=0 && now-healthAt>=3000L){
            healthAt=now
            val value="eye=${eyes.supportState}/${eyes.state} fresh=${e!=null} controller=${controllers.supportState}/${controllers.state} source=${input.source}"
            if(value!=health){health=value;Log.i("HamiltonHover",value)}
        }
    }
    /** Fresh, permitted gaze only. Never reuse the fading hover card or blink grace for taps. */
    internal fun tapTarget():String? {
        if(!running || !allowed)return null
        val now=SystemClock.elapsedRealtime()
        val pose=eye?.takeIf{now-eyeArrival<180L && it.timestamp>0L}?.eyePose ?: return null
        val origin=pose.position
        return director.hitTapRole(origin,origin+pose.rotation.rotateVector(Vector3(0f,0f,-1f)))
    }
    fun close(){pause()}
}

internal fun moveTowards(value:Float,target:Float,step:Float)=value+(target-value).coerceIn(-step,step)

/** Slab intersection of a ray with measured local model bounds; nearest positive hit wins. */
internal fun hitBox(origin:Vector3,direction:Vector3,min:StagePoint,max:StagePoint):Float? {
    var near=0f;var far=40f
    val origins=floatArrayOf(origin.x,origin.y,origin.z)
    val directions=floatArrayOf(direction.x,direction.y,direction.z)
    val lows=floatArrayOf(min.x-.04f,min.y-.03f,min.z-.05f)
    val highs=floatArrayOf(max.x+.04f,max.y+.03f,max.z+.05f)
    for(i in 0..2){
        if(abs(directions[i])<1e-6f){if(origins[i]<lows[i]||origins[i]>highs[i])return null}
        else {
            val a=(lows[i]-origins[i])/directions[i];val b=(highs[i]-origins[i])/directions[i]
            near=maxOf(near,minOf(a,b));far=minOf(far,maxOf(a,b));if(near>far)return null
        }
    }
    return near
}
