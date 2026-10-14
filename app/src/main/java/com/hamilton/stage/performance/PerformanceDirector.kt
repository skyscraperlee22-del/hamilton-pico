package com.hamilton.stage.performance

import android.content.Context
import android.util.Log
import android.os.SystemClock
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.math.Color4
import androidx.compose.runtime.*
import com.pico.spatial.core.ecs.*
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*

enum class PerformancePhase { LOADING, IDLE, WALKING, TURNING, FRAMING, PREPARING, PLAYING, FINISHED, ERROR }
const val BIOGRAPHY_WIDTH_METRES=2.4f
internal const val BIOGRAPHY_HEAD_GAP=.55f
class PerformanceUi {
    var phase by mutableStateOf(PerformancePhase.LOADING)
    var selected by mutableStateOf<CastMember?>(null)
    var years by mutableStateOf(listOf<StageYear>())
    var yearIndex by mutableIntStateOf(0)
    var yearPosition by mutableFloatStateOf(0f)
    var lyric by mutableStateOf<LyricCue?>(null)
    var progress by mutableFloatStateOf(0f)
    var error by mutableStateOf("")
    var hoverCardRole by mutableStateOf<String?>(null)
    var hoverCardAlpha by mutableFloatStateOf(0f)
}
class StageActor(var member:CastMember,val root:Entity) {
    val mouth=root.findEntity("Mouth")?.components?.get(TransformComponent::class.java)
    val limbs=listOf("ArmLeft","ArmRight","LegLeft","LegRight").map { root.findEntity(it)?.components?.get(TransformComponent::class.java) }
    private val glowMaterials=collectGlowMaterials(root).distinct().map { it to it.getEmissiveColor() }
    private var glow=-1f
    var nativeHover=true;private set
    fun highlight(value:Float,native:Boolean) {
        if(native!=nativeHover){
            nativeHover=native
            if(native)root.components[HoverEffectComponent::class.java]=HoverEffectComponent()
            else root.components.remove(HoverEffectComponent::class.java)
        }
        if(abs(glow-value)<.002f)return
        glow=value
        glowMaterials.forEach{(material,base)->material.setEmissiveColor(Color4(base.red+.20f*value,base.green+.15f*value,base.blue+.08f*value,base.alpha))}
    }
    val transform get()=root.components[TransformComponent::class.java]!!
    fun reset() {
        transform.setPosition(member.position.vector()).setEulerAngles(EulerAngles(0f,member.facing,0f))
        pose(0f,false,false)
    }
    fun pose(time:Float,walking:Boolean,singing:Boolean) {
        val swing=if(walking) sin(time*10.5f)*28f else 0f
        limbs.forEachIndexed { i,t ->t?.setEulerAngles(EulerAngles(swing*(if(i==0||i==3)1 else -1),0f,0f)) }
        val opening=if(singing) 1f+5f*abs(sin(time*11f)*.7f+sin(time*17.1f)*.3f) else 1f
        mouth?.setScaleVector(Vector3(1f,opening,1f))
    }
}
/** Owns cast interaction, cancellable routes and exactly one local audio player. */
class PerformanceDirector(context:Context) {
    val ui=PerformanceUi()
    val hoverTracking=StageHoverTracking(context.applicationContext,this)
    private val hover=HoverTransition()
    private var trackedHover=false
    var nameCard:Entity?=null
    var biographyCard:Entity?=null
    var lyricsPanel:Entity?=null
    var captions:StageCaptions?=null
    var biographyHeight=1f;private set
    var biographyMeasuredRole:String?=null;private set
    var viewRig:PerformanceViewRig?=null;private set
    var frameCount=0L;private set
    private var previousFrameNanos=0L
    // Only the current cast participates in interaction, framing and navigation.
    val actors=linkedMapOf<String,StageActor>()
    private val actorPool=linkedMapOf<String,StageActor>()
    var catalog:StageCatalog?=null;private set
    var world:Entity?=null;private set
    var hamiltonPosition=StagePoint(0f,.04f,2.6f);private set
    var hamiltonFacing=0f;private set
    var ready=false;private set
    var closed=false;private set
    private val audio=StageAudio(context.applicationContext)
    private val shapes=mutableListOf<ShapeResource>()
    private var physics:PhysicsMaterialResource?=null
    private lateinit var navigation:StageNavigation
    private var route:List<StagePoint> = emptyList()
    private var routeStep=0
    private var clock=0f
    private var turnTime=0f
    private var turnStart=0f
    private var captionTick=0f
    private var draggingYear=false
    private var yearTarget=0f
    init { audio.onInterrupted={if(!closed){ui.phase=PerformancePhase.FINISHED;ui.lyric=null;resetPoses();renderLyrics();renderHover(SystemClock.elapsedRealtime())}} }
    suspend fun load(context:Context,root:Entity) {
        world=root
        viewRig=PerformanceViewRig(root).also{it.resume()}
        val data=withContext(Dispatchers.IO){StageCatalog.load(context)}
        if(closed)return
        catalog=data;ui.years=data.years
        physics=PhysicsMaterialResource()
        for(member in data.models) {
            val entity=Entity.loadSuspend("asset://${member.asset}")
            if(closed){entity.destroy();return}
            entity.setName("Actor_${member.role}")
            val size=Vector3(member.max.x-member.min.x+.08f,member.max.y-member.min.y+.06f,member.max.z-member.min.z+.10f)
            val box=ShapeResource.createBox(size)
            val offset=box.offsetByTranslation(Vector3((member.min.x+member.max.x)/2,(member.min.y+member.max.y)/2,(member.min.z+member.max.z)/2))
            shapes+=box;shapes+=offset
            entity.components[CollisionComponent::class.java]=CollisionComponent(listOf(offset),physics!!)
            entity.components[InteractableComponent::class.java]=InteractableComponent()
            entity.components[HoverEffectComponent::class.java]=HoverEffectComponent()
            root.addChild(entity)
            val actor=StageActor(member,entity);actorPool[member.role]=actor;entity.enabled=false
        }
        activateScene(0)
        ready=true;ui.phase=PerformancePhase.IDLE
        PerformanceSystem.active=this
        hoverTracking.resume()
        Log.i("HamiltonPerformance","CAST_READY years=${data.scenes.size} actors=${actors.size} models=${actorPool.size} audio=${data.scenes.sumOf{it.actors.size}} bilingual=true")
    }
    private fun activateScene(index:Int) {
        val scene=catalog!!.scene(index)
        actorPool.values.forEach { it.highlight(0f,false);it.root.enabled=false;it.pose(0f,false,false) }
        actors.clear()
        for(member in scene.actors){
            val actor=actorPool.getValue(member.role)
            actor.member=member;actor.reset();actor.root.enabled=true
            actor.highlight(0f,!trackedHover)
            actors[member.role]=actor
        }
        navigation=StageNavigation(scene.actors)
        hamiltonPosition=actors.getValue("Hamilton").member.position;hamiltonFacing=0f
    }
    fun roleFor(entity:Entity?):String? {
        var node=entity
        while(node!=null){val current=node
            if(current==nameCard && current.enabled)return ui.hoverCardRole
            actors.entries.firstOrNull{it.value.root==current}?.let{return it.key};node=current.getParent()}
        return null
    }
    fun select(role:String) {
        if(!ready||closed)return
        val actor=actors[role]?:return
        viewRig?.reset()
        audio.stop();resetPoses();ui.lyric=null;ui.progress=0f;ui.error="";ui.selected=actor.member
        try {
            route=navigation.route(hamiltonPosition,role);routeStep=0
            ui.phase=if(route.isEmpty())PerformancePhase.TURNING else PerformancePhase.WALKING
            turnTime=0f;turnStart=hamiltonFacing
            Log.i("HamiltonPerformance","SELECT role=$role phase=${ui.phase} waypoints=${route.size}")
        }catch(error:Exception){ui.error="暂时找不到通路，请选择另一位人物";ui.phase=PerformancePhase.ERROR;Log.e("HamiltonPerformance","ROUTE_FAILED",error)}
        renderBiography()
        renderLyrics()
    }
    fun beginYearDrag(){if(!ready)return;draggingYear=true}
    /** UI drag delta is normalized by its visible year-cell width, never treated as metres. */
    fun dragYear(deltaInCells:Float){if(!ready)return;ui.yearPosition=(ui.yearPosition-deltaInCells).coerceIn(0f,5f)}
    fun endYearDrag(){if(!ready)return;draggingYear=false;chooseYear(ui.yearPosition.roundToInt())}
    fun cancelYearDrag(){draggingYear=false;yearTarget=ui.yearIndex.toFloat()}
    fun chooseYear(index:Int) {
        if(!ready||closed)return
        val next=index.coerceIn(0,5);yearTarget=next.toFloat();draggingYear=false
        if(next==ui.yearIndex)return
        viewRig?.reset()
        clearHover();audio.stop();route=emptyList();resetPoses();ui.selected=null;ui.lyric=null;ui.progress=0f;ui.yearIndex=next
        activateScene(next)
        ui.error="";ui.phase=PerformancePhase.IDLE
        renderBiography()
        renderLyrics()
        Log.i("HamiltonPerformance","YEAR_SELECT year=${ui.years[next].year} actors=${actors.size}")
    }
    fun stopPerformance() {audio.stop();route=emptyList();resetPoses();ui.lyric=null;ui.phase=PerformancePhase.FINISHED;viewRig?.reset();clearHover();renderLyrics()}
    /** Native container updates may be throttled while its UI is idle. Both the renderer's
     * update callback and the UI vsync pump share this clock so movement never advances twice. */
    fun advanceFrame(nanos:Long) {
        if(!ready||closed){previousFrameNanos=0L;return}
        if(previousFrameNanos==0L){previousFrameNanos=nanos;return}
        val elapsed=(nanos-previousFrameNanos)/1_000_000_000f
        if(elapsed<.006f)return
        previousFrameNanos=nanos
        tick(elapsed.coerceAtMost(.5f))
    }
    fun suspendFrames(){previousFrameNanos=0L}
    fun tick(delta:Float) {
        if(!ready||closed)return
        frameCount++
        val now=SystemClock.elapsedRealtime()
        hoverTracking.poll(now)
        renderHover(now)
        val dt=delta.coerceIn(0f,.5f);clock+=dt
        viewRig?.tick(dt)
        if(!draggingYear){val diff=yearTarget-ui.yearPosition;if(abs(diff)>.001f)ui.yearPosition+=diff*(1-exp(-16f*dt)) else if(ui.yearPosition!=yearTarget)ui.yearPosition=yearTarget}
        val hamilton=actors["Hamilton"]?:return
        when(ui.phase) {
            PerformancePhase.WALKING -> {
                var remaining=2.2f*dt
                while(remaining>0f&&routeStep<route.size){
                    val target=route[routeStep];val distance=hamiltonPosition.distance(target)
                    if(distance<.001f){routeStep++;continue}
                    val heading=Math.toDegrees(atan2((target.x-hamiltonPosition.x).toDouble(),(target.z-hamiltonPosition.z).toDouble())).toFloat()
                    hamiltonFacing=approachAngle(hamiltonFacing,heading,dt*240f)
                    val step=min(remaining,distance);hamiltonPosition=hamiltonPosition.lerp(target,step/distance);remaining-=step
                    if(step>=distance-.001f)routeStep++
                }
                hamilton.transform.setPosition(hamiltonPosition.vector()).setEulerAngles(EulerAngles(0f,hamiltonFacing,0f))
                hamilton.pose(clock,true,false)
                if(routeStep>=route.size){ui.phase=PerformancePhase.TURNING;turnTime=0f;turnStart=hamiltonFacing;hamilton.pose(0f,false,false)}
            }
            PerformancePhase.TURNING -> {
                turnTime+=dt;val t=(turnTime/.55f).coerceIn(0f,1f);val eased=t*t*(3-2*t)
                hamiltonFacing=turnStart+angleDelta(turnStart,0f)*eased
                hamilton.transform.setEulerAngles(EulerAngles(0f,hamiltonFacing,0f))
                if(t>=1f){
                    hamiltonFacing=0f;hamilton.transform.setEulerAngles(EulerAngles(0f,0f,0f))
                    actors[ui.selected?.role]?.transform?.setEulerAngles(EulerAngles(0f,0f,0f))
                    val group=performanceSubjects()
                    val all=actorEnvelopes()
                    viewRig?.focus(performanceFrame(all.filter{it.role in group},all.filter{it.role !in group},biographyHeight))
                    clearHover();ui.phase=PerformancePhase.FRAMING
                }
            }
            PerformancePhase.FRAMING -> if(viewRig?.moving!=true)playSelection()
            PerformancePhase.PLAYING -> {
                val song=ui.selected?.music?:return
                val time=audio.elapsed
                actors.values.forEach {it.pose(time,false,it.member.role in song.singers)}
                captionTick+=dt
                if(captionTick>=.08f){captionTick=0f;ui.lyric=song.cues.firstOrNull{time>=it.start&&time<it.end};ui.progress=(time/song.duration).coerceIn(0f,1f)}
            }
            else -> Unit
        }
        renderBiography()
        renderLyrics()
    }
    /** Panel stays in the theater frame so turning actors never turn the text away.
     * Its bottom edge clears the measured model bounds, including the king's crown. */
    private fun renderBiography() {
        val card=biographyCard ?: return
        val actor=actors[ui.selected?.role]
        val visible=actor!=null
        if(card.enabled!=visible)card.enabled=visible
        if(!visible)return
        val head=actor!!.root.convertPositionTo(Vector3(0f,actor.member.max.y,0f),world)
        val yaw=viewRig?.subjectYaw?:0f
        val right=rotateY(Vector3(1f,0f,0f),yaw)
        val normal=rotateY(Vector3(0f,0f,1f),yaw)
        val base=Vector3(head.x,head.y+BIOGRAPHY_HEAD_GAP,head.z+.15f)
        val eye=viewRig?.framingEye ?: Vector3(0f,2.07f,9.5f)
        fun dot(a:Vector3,b:Vector3)=a.x*b.x+a.y*b.y+a.z*b.z
        val faces=actors.values.map { other ->
            other.root.convertPositionTo(Vector3(0f,other.member.max.y-.2f,0f),world)
        }
        // Test each face's projected centre against the card, including gallery actors.
        val offset=listOf(0f,-.6f,.6f,-1.2f,1.2f,-1.8f,1.8f,-2.4f,2.4f).minBy { shift ->
            val candidate=base+right*shift
            val overlaps=faces.count { face ->
                val ray=face-eye;val denominator=dot(ray,normal)
                if(abs(denominator)<.01f)false else {
                    val t=dot(candidate-eye,normal)/denominator
                    val hit=eye+ray*t-candidate
                    val margin=.28f*abs(t)+.08f
                    t>0f && abs(dot(hit,right))<BIOGRAPHY_WIDTH_METRES/2f+margin &&
                        hit.y > -margin && hit.y < biographyHeight+margin
                }
            }
            overlaps*100f+abs(shift)
        }
        card.components[TransformComponent::class.java]?.setPosition(base+right*offset)
        card.components[TransformComponent::class.java]?.setEulerAngles(EulerAngles(0f,viewRig?.subjectYaw?:0f,0f))
    }
    fun measureBiography(role:String,heightMetres:Float){
        if(heightMetres>0f && role==ui.selected?.role){biographyHeight=heightMetres;biographyMeasuredRole=role}
    }
    private fun performanceSubjects():Set<String> =
        (listOfNotNull("Hamilton",ui.selected?.role)+(ui.selected?.music?.singers?:emptyList())).toSet()
    private fun actorEnvelopes(roles:Set<String> = actors.keys)=actors.values.filter{it.member.role in roles}.map { actor ->
        val points=listOf(actor.member.min.x,actor.member.max.x).flatMap{x->
            listOf(actor.member.min.y,actor.member.max.y).flatMap{y->
                listOf(actor.member.min.z,actor.member.max.z).map{z->actor.root.convertPositionTo(Vector3(x,y,z),world)}
            }
        }
        ActorEnvelope(actor.member.role,Vector3(points.minOf{it.x},points.minOf{it.y},points.minOf{it.z}),
            Vector3(points.maxOf{it.x},points.maxOf{it.y},points.maxOf{it.z}))
    }
    val subtitlesVisible:Boolean get()=ui.phase==PerformancePhase.PLAYING && ui.lyric!=null
    private fun renderLyrics(){
        val panel=lyricsPanel?:return
        captions?.show(ui.lyric.takeIf{subtitlesVisible})
        val visible=subtitlesVisible
        if(panel.enabled!=visible)panel.enabled=visible
        if(!visible)return
        val members=performanceSubjects()
        val group=actorEnvelopes(members)
        val centerX=(group.minOf{it.min.x}+group.maxOf{it.max.x})/2f
        val frontZ=group.maxOf{it.max.z}
        val floor=group.minOf{it.min.y}
        val yaw=viewRig?.subjectYaw?:0f
        val front=rotateY(Vector3(0f,0f,1.2f),yaw)
        panel.components[TransformComponent::class.java]?.setPosition(Vector3(centerX+front.x,floor+.3f,frontZ+front.z))
            ?.setEulerAngles(EulerAngles(0f,yaw,0f))
    }
    fun hitRole(origin:Vector3,end:Vector3):String? {
        if(!ready)return null
        return actors.values.mapNotNull { actor ->
            val a=actor.root.convertPositionFrom(origin,null)
            val b=actor.root.convertPositionFrom(end,null)
            hitBox(a,b-a,actor.member.min,actor.member.max)?.let{actor.member.role to it}
        }.minByOrNull{it.second}?.first
    }
    fun updateHover(role:String?,tracked:Boolean,now:Long) {
        trackedHover=tracked
        hover.update(role?.takeIf(::canHover),now)
    }
    // Match the actual singing/mouth-animation set, including Hamilton and ensemble partners.
    // Keep collision and tapping intact; singers still occlude the people behind them.
    private fun canHover(role:String)=role in actors &&
        !(ui.phase==PerformancePhase.PLAYING && role in (ui.selected?.music?.singers ?: emptyList()))
    private fun renderHover(now:Long) {
        if(hover.role?.let{!canHover(it)}==true || hover.cardRole?.let{!canHover(it)}==true)hover.clear()
        hover.update(hover.role,now)
        actors.forEach{(role,actor)->
            val enabled=canHover(role)
            actor.highlight(if(enabled && role==hover.role)hover.highlight else 0f,enabled && !trackedHover)
        }
        ui.hoverCardRole=hover.cardRole
        val a=hover.cardAlpha;ui.hoverCardAlpha=a*a*(3f-2f*a)
        val actor=actors[hover.cardRole]
        nameCard?.let { card ->
            val visible=actor!=null && ui.hoverCardAlpha>.001f
            if(card.enabled!=visible)card.enabled=visible
            if(visible) {
                val p=actor!!.root.convertPositionTo(Vector3.ZERO,world)
                card.components[TransformComponent::class.java]?.setPosition(Vector3(p.x,p.y+actor.member.max.y*.53f,p.z+.67f))
            }
        }
    }
    private fun clearHover(){hover.clear();ui.hoverCardRole=null;ui.hoverCardAlpha=0f;nameCard?.enabled=false;actors.forEach{(role,actor)->actor.highlight(0f,canHover(role) && !trackedHover)}}
    private fun playSelection() {
        val selected=ui.selected?:return
        ui.phase=PerformancePhase.PREPARING
        actors[selected.role]?.transform?.setEulerAngles(EulerAngles(0f,0f,0f))
        audio.play(selected.music,onStarted={
            if(!closed){ui.phase=PerformancePhase.PLAYING;captionTick=0f;ui.lyric=selected.music.cues.firstOrNull{audio.elapsed>=it.start && audio.elapsed<it.end};renderLyrics();renderHover(SystemClock.elapsedRealtime());Log.i("HamiltonPerformance","PLAYING role=${selected.role} facing=$hamiltonFacing x=${hamiltonPosition.x} y=${hamiltonPosition.y} z=${hamiltonPosition.z}")}
        },onEnded={if(!closed){ui.phase=PerformancePhase.FINISHED;ui.lyric=null;ui.progress=1f;resetPoses();renderLyrics();renderHover(SystemClock.elapsedRealtime())}},onError={if(!closed){ui.error=it;ui.phase=PerformancePhase.ERROR;ui.lyric=null;resetPoses();renderLyrics();renderHover(SystemClock.elapsedRealtime())}})
    }
    private fun resetPoses(){actors.values.forEach{it.pose(0f,false,false)}}
    fun close(){if(closed)return;closed=true;ready=false;viewRig?.close();hoverTracking.close();audio.close();if(PerformanceSystem.active===this)PerformanceSystem.active=null}
    /** Called after the owning scene hierarchy is destroyed. */
    fun releaseResources(){captions?.close();captions=null;shapes.forEach{it.close()};shapes.clear();physics?.close();physics=null;actors.clear();actorPool.clear()}
}
class PerformanceSystem:com.pico.spatial.core.ecs.System() {
    private var samples=0
    override fun update(context:SceneUpdateContext){
        active?.let {
            if(samples++<3)Log.i("HamiltonPerformance","FRAME dt=${context.deltaTime} sceneMatch=${it.world?.scene==context.scene} worldScene=${it.world?.scene} scene=${context.scene}")
            if(it.world?.scene==context.scene)it.advanceFrame(java.lang.System.nanoTime())
        }
    }
    companion object {var active:PerformanceDirector?=null}
}
private fun StagePoint.vector()=Vector3(x,y,z)
private fun angleDelta(from:Float,to:Float):Float=((to-from+540f)%360f)-180f
private fun approachAngle(from:Float,to:Float,step:Float)=from+angleDelta(from,to).coerceIn(-step,step)

private fun collectGlowMaterials(entity:Entity):List<PhysicallyBasedMaterial> =
    (entity.components[ModelComponent::class.java]?.materials?.filterIsInstance<PhysicallyBasedMaterial>() ?: emptyList()) + entity.getChildren().flatMap(::collectGlowMaterials)
