package com.hamilton.stage

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hamilton.stage.content.StageLoadState
import com.hamilton.stage.performance.*
import com.hamilton.stage.platform.LaunchActivity
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.HoverEffectComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.ui.foundation.gesture.data.InteractionKind
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.core.math.Quat
import com.pico.spatial.tracking.eye.EyePose
import com.pico.spatial.tracking.controller.ControllerPose
import com.pico.spatial.tracking.controller.ControllerTrackingData
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PerformanceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private fun await(timeout:Long=60000,predicate:()->Boolean){
        val end=SystemClock.elapsedRealtime()+timeout
        while(SystemClock.elapsedRealtime()<end){var okay=false;instrumentation.runOnMainSync{okay=predicate()};if(okay)return;SystemClock.sleep(80)}
        fail("Timed out: phase=${PerformanceSystem.active?.ui?.phase} frames=${PerformanceSystem.active?.frameCount} position=${PerformanceSystem.active?.hamiltonPosition} bioRole=${PerformanceSystem.active?.biographyMeasuredRole} bioHeight=${PerformanceSystem.active?.biographyHeight}")
    }
    private fun launch():PerformanceDirector {
        // Device tests run on the emulator; pregrant its test permission so the system dialog
        // does not take Full Space away from the scene being exercised.
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName,EYE_PERMISSION)
        instrumentation.runOnMainSync{instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext,LaunchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
        await{StageLoadState.ready&&PerformanceSystem.active?.ready==true}
        val director=PerformanceSystem.active!!
        instrumentation.runOnMainSync { director.chooseYear(if(director.ui.yearIndex==0)1 else 0);director.chooseYear(0);director.stopPerformance() }
        return director
    }
    @Test fun dwellRequires750msAndResetsOnNewCharacter(){
        val hover=HoverTransition()
        hover.update("Burr",1000)
        for(t in 1050L..1749L step 50)hover.update("Burr",t)
        hover.update("Burr",1749)
        assertEquals(0f,hover.cardAlpha,.001f)
        assertEquals(1f,hover.highlight,.001f)
        hover.update("Burr",1750)
        hover.update("Burr",1850)
        assertTrue(hover.cardAlpha>0f && hover.cardAlpha<1f)
        assertTrue(hover.highlight>0f && hover.highlight<1f)
        for(t in 1950L..2150L step 50)hover.update("Burr",t)
        assertEquals(1f,hover.cardAlpha,.001f)
        assertEquals(0f,hover.highlight,.001f)
        hover.update(null,2200)
        assertTrue(hover.cardAlpha>0f && hover.cardAlpha<1f)
        for(t in 2250L..2500L step 50)hover.update(null,t)
        assertNull(hover.cardRole)
        hover.update("Eliza",2550)
        hover.update("Peggy",2650)
        assertEquals("Peggy",hover.role)
        assertEquals(0f,hover.cardAlpha,.001f)
        hover.update("Peggy",3399)
        assertEquals(0f,hover.cardAlpha,.001f)
    }
    @Test fun restingControllerDoesNotStealEyeDwell(){
        val eyes=EyePose(Vector3(1f,1.6f,0f),Quat.identity())
        val controller=ControllerTrackingData(null,ControllerPose(Vector3(3f,1f,0f),Quat.identity()),1L)
        val hit:(Vector3,Vector3)->String?={origin,_->when(origin.x){1f->"Eliza";3f->"Burr";else->null}}
        val hover=HoverTransition()
        for(now in 1000L..2200L step 50){
            val input=resolveHoverInput(eyes,controller,hit)
            assertEquals("eye",input.source)
            hover.update(input.role,now)
        }
        assertEquals("Eliza",hover.cardRole)
        assertEquals(1f,hover.cardAlpha,.001f)
        assertEquals(0f,hover.highlight,.001f)
        // Looking away clears the card; it must not unexpectedly jump to the idle controller.
        val away=resolveHoverInput(EyePose(Vector3(2f,1.6f,0f),Quat.identity()),controller,hit)
        assertEquals("eye",away.source)
        assertNull(away.role)
        for(now in 2250L..2600L step 50)hover.update(away.role,now)
        assertNull(hover.cardRole)
        val fallback=resolveHoverInput(null,controller,hit)
        assertEquals("controller",fallback.source)
        assertEquals("Burr",fallback.role)
        assertFalse(resolveHoverInput(null,null,hit).tracked)
    }
    @Test fun routesAndLocalLyricsCoverAllSixYears(){
        val catalog=StageCatalog.load(instrumentation.targetContext)
        assertEquals(listOf(1776,1780,1781,1789,1791,1800),catalog.years.map{it.year})
        assertEquals(14,catalog.models.size)
        assertEquals(31,catalog.scenes.sumOf{it.actors.size})
        for(scene in catalog.scenes){
        val navigator=StageNavigation(scene.actors)
        for(actor in scene.actors){
            assertTrue(actor.music.cues.all{it.en.isNotBlank()&&it.zh.isNotBlank()&&it.start>=0&&it.end>it.start&&it.end<=actor.music.duration+.1f})
            instrumentation.targetContext.assets.openFd(actor.music.file).use{assertTrue(it.length>10000)}
            val there=navigator.route(navigator.home,actor.role)
            val end=there.lastOrNull()?:navigator.home
            if(actor.role=="GeorgeIII"){
                assertEquals(2.8f,end.y,.001f)
                assertTrue(there.any{it.x>6f&&it.y<.1f})
                assertTrue(there.any{it.x>6f&&it.y>2f&&it.z>0f})
            }else assertEquals(.04f,end.y,.001f)
            // Every actor-to-actor change must find a route, including returning from the gallery.
            for(next in scene.actors)navigator.route(end,next.role)
            val retriever=android.media.MediaMetadataRetriever()
            try {
                instrumentation.targetContext.assets.openFd(actor.music.file).use{retriever.setDataSource(it.fileDescriptor,it.startOffset,it.length)}
                val duration=retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toFloat()/1000f
                assertEquals(actor.music.id,actor.music.duration,duration,.15f)
            }finally{retriever.release()}
        }
        }
    }
    @Test fun missingEntityGazeTapStartsTheRealPerformance(){
        val director=launch()
        instrumentation.runOnMainSync{
            director.hoverTracking.pause()
            director.chooseYear(1);director.chooseYear(0)
            val actor=director.actors.getValue("Burr").root
            val end=actor.convertPositionTo(Vector3(0f,1f,0f),null)
            // Burr initially faces toward Hamilton. Use a clear audience-side ray instead
            // of following Burr's rotated +Z through Hamilton's foreground collider.
            val origin=end+Vector3(0f,0f,4f)
            assertTrue(director.selectFromTap(null,InteractionKind.GazePinch){director.hitTapRole(origin,end)})
            assertEquals("Burr",director.ui.selected?.role)
        }
        await(25000){director.ui.phase==PerformancePhase.PLAYING}
        assertFalse(director.viewRig!!.moving)
        await(5000){director.ui.lyric!=null}
        val hold=InstrumentationRegistry.getArguments().getString("captureHoldMs")?.toLongOrNull() ?: 0L
        if(hold>0){android.util.Log.i("HamiltonTapQA","READY_FOR_CAPTURE");val until=SystemClock.elapsedRealtime()+4000;await(6000){SystemClock.elapsedRealtime()>=until}
            instrumentation.runOnMainSync { android.util.Log.i("HamiltonCaptionQA","cueStart=${director.ui.lyric?.start} phase=${director.ui.phase}") }
            capture("caption-render-check");val later=SystemClock.elapsedRealtime()+6000;await(8000){SystemClock.elapsedRealtime()>=later};capture("caption-later-check")}
        instrumentation.runOnMainSync{director.stopPerformance();director.chooseYear(1);director.chooseYear(0)}
    }
    @Test fun tapFallbackRespectsEmptySpaceOtherUiAndTheVisibleNameCard(){
        val director=launch()
        instrumentation.runOnMainSync{
            director.hoverTracking.pause()
            director.chooseYear(1);director.chooseYear(0)
            for(kind in InteractionKind.entries.filter{it!=InteractionKind.GazePinch}){
                assertFalse(director.selectFromTap(null,kind){fail("Only gaze pinch can use gaze fallback");"Burr"})
            }
            assertFalse(director.selectFromTap(null,InteractionKind.GazePinch){null})
            assertFalse(director.selectFromTap(director.biographyCard,InteractionKind.GazePinch){fail("UI must not fall through");"Burr"})
            assertNull(director.ui.selected)
            // The name plate extends past the body. Tapping its visible edge must work too.
            val now=SystemClock.elapsedRealtime()
            director.updateHover("Hamilton",true,now-1300)
            for(t in (now-1250)..now step 50)director.updateHover("Hamilton",true,t)
            director.tick(0f)
            val card=director.nameCard!!
            assertTrue(card.enabled)
            val child=Entity()
            card.addChild(child)
            assertEquals("Hamilton",director.roleFor(child))
            child.destroy()
            val center=card.convertPositionTo(Vector3.ZERO,director.world)
            val aim=director.world!!.convertPositionTo(center+Vector3(.67f,0f,0f),null)
            val origin=director.world!!.convertPositionTo(center+Vector3(.67f,0f,4f),null)
            assertEquals("Hamilton",director.hitTapRole(origin,aim))
            assertTrue(director.selectFromTap(null,InteractionKind.GazePinch){director.hitTapRole(origin,aim)})
            assertEquals("Hamilton",director.ui.selected?.role)
            director.chooseYear(1)
            assertFalse(director.selectFromTap(null,InteractionKind.GazePinch){"GeorgeIII"})
            director.chooseYear(0);director.stopPerformance()
        }
    }
    @Test fun ensembleHoverRestoresWhenSongCompletes(){
        val director=launch()
        instrumentation.runOnMainSync{
            director.hoverTracking.pause()
            director.chooseYear(0)
            director.stopPerformance()
            director.select("Peggy")
        }
        await(25000){director.ui.phase==PerformancePhase.PLAYING}
        instrumentation.runOnMainSync{
            director.updateHover(null,false,SystemClock.elapsedRealtime())
            director.tick(0f)
            for(role in listOf("Eliza","Angelica","Peggy"))assertNull(director.actors.getValue(role).root.components[HoverEffectComponent::class.java])
            assertNotNull(director.actors.getValue("Burr").root.components[HoverEffectComponent::class.java])
        }
        await(15000){director.ui.phase==PerformancePhase.FINISHED}
        instrumentation.runOnMainSync{
            for(role in listOf("Eliza","Angelica","Peggy"))assertNotNull(director.actors.getValue(role).root.components[HoverEffectComponent::class.java])
        }
    }
    @Test fun biographyFollowsActorsAndHidesOnYearChange(){
        val director=launch()
        instrumentation.runOnMainSync{
            director.hoverTracking.pause()
            director.chooseYear(1)
            assertFalse(director.biographyCard!!.enabled)
            director.chooseYear(0)
            assertFalse(director.biographyCard!!.enabled)
            for((role,actor) in director.actors){
                director.select(role)
                val card=director.biographyCard!!
                assertTrue(card.enabled)
                val center=card.convertPositionTo(Vector3.ZERO,director.world)
                val head=actor.root.convertPositionTo(Vector3(0f,actor.member.max.y,0f),director.world)
                assertTrue(kotlin.math.abs(head.x-center.x)<=2.401f)
                // Native WRAP_CONTENT panel is bottom-anchored, regardless of text height.
                assertEquals(.55f,center.y-head.y,.001f)
                assertTrue(center.z>head.z)
                assertFalse(director.subtitlesVisible);assertFalse(director.lyricsPanel!!.enabled)
            }
            // Hamilton may be chosen while already away from his initial mark. The card
            // must track his real motion, not the static cast catalog coordinates.
            director.select("Burr")
            director.tick(.5f)
            director.select("Hamilton")
            director.tick(.5f)
            val player=director.actors.getValue("Hamilton").root
            val feet=player.convertPositionTo(Vector3.ZERO,director.world)
            val center=director.biographyCard!!.convertPositionTo(Vector3.ZERO,director.world)
            assertTrue(kotlin.math.abs(feet.x-center.x)<=2.401f)
            assertEquals(feet.z+.15f,center.z,.001f)
            director.chooseYear(2)
            assertFalse(director.biographyCard!!.enabled)
            assertFalse(director.subtitlesVisible);assertFalse(director.lyricsPanel!!.enabled)
            director.chooseYear(0)
            director.stopPerformance()
        }
    }
    @Test fun framingFitsThePerformersAndAvoidsForegroundCast(){
        val duet=listOf(
            ActorEnvelope("Hamilton",Vector3(-1.3f,0f,-.3f),Vector3(-.1f,1.8f,.3f)),
            ActorEnvelope("Burr",Vector3(.1f,0f,-.3f),Vector3(1.3f,1.8f,.3f)))
        val straight=performanceFrame(duet,emptyList(),1.1f)
        assertEquals(0f,straight.center.x,.001f)
        assertEquals(0f,straight.yaw,.001f)
        assertTrue(straight.eye.z>5.8f)
        val blocked=performanceFrame(duet,listOf(ActorEnvelope("Other",Vector3(-.7f,0f,2f),Vector3(.7f,1.8f,2.6f))),1.1f)
        assertTrue(kotlin.math.abs(blocked.yaw)>0f)
        val gallery=duet.map{ActorEnvelope(it.role,it.min+Vector3(3f,2.8f,-4.8f),it.max+Vector3(3f,2.8f,-4.8f))}
        val upstairs=performanceFrame(gallery,emptyList(),1.1f)
        assertEquals(2.8f,upstairs.eye.y-straight.eye.y,.001f)
    }
    @Test fun biographyHeightUsesActualTextLayout(){
        val director=launch()
        instrumentation.runOnMainSync{
            director.chooseYear(1);director.chooseYear(0);director.select("Hamilton");director.stopPerformance()
            // Deliberately different line counts: two real biographies can occupy the same
            // number of lines despite differing character counts.
            director.ui.selected=director.ui.selected!!.copy(sentences=listOf("纽约的青年。","投身革命。"))
        }
        await(8000){director.biographyMeasuredRole=="Hamilton" && director.biographyHeight in .2f.. .8f}
        var shortHeight=0f
        instrumentation.runOnMainSync{
            shortHeight=director.biographyHeight
            director.ui.selected=director.actors.getValue("Hamilton").member
        }
        await(8000){director.biographyHeight>shortHeight+.1f}
        instrumentation.runOnMainSync{director.stopPerformance();director.chooseYear(1);director.chooseYear(0)}
    }
    @Test fun clickWalkTurnSingThenRetargetAndSnap(){
        val director=launch()
        instrumentation.runOnMainSync{
            director.hoverTracking.pause()
            director.chooseYear(0)
            director.updateHover(null,false,SystemClock.elapsedRealtime())
            director.tick(0f)
            assertEquals(9,director.actors.size)
            director.actors.values.forEach{
                assertNotNull(it.root.components[CollisionComponent::class.java])
                assertNotNull(it.root.components[InteractableComponent::class.java])
                assertNotNull(it.root.components[HoverEffectComponent::class.java])
                assertNotNull(it.mouth)
            }
            director.hoverTracking.pause()
            director.updateHover("Burr",true,SystemClock.elapsedRealtime())
        }
        await(4000){director.ui.hoverCardAlpha>.99f}
        instrumentation.runOnMainSync{
            director.select("Burr")
            assertEquals(PerformancePhase.WALKING,director.ui.phase)
            assertNull(director.ui.lyric)
        }
        await(25000){director.ui.phase==PerformancePhase.PLAYING}
        instrumentation.runOnMainSync{
            // A card already visible before singing must disappear immediately on playback.
            assertNull(director.ui.hoverCardRole)
            assertEquals(0f,director.ui.hoverCardAlpha,.001f)
            assertFalse(director.nameCard!!.enabled)
            director.updateHover("Burr",false,SystemClock.elapsedRealtime())
            director.tick(0f)
            for(role in listOf("Burr","Hamilton")){
                val actor=director.actors.getValue(role)
                assertNull(actor.root.components[HoverEffectComponent::class.java])
                assertNotNull(actor.root.components[CollisionComponent::class.java])
                assertNotNull(actor.root.components[InteractableComponent::class.java])
                director.updateHover(role,true,SystemClock.elapsedRealtime())
                director.tick(0f)
                assertNull(director.ui.hoverCardRole)
                assertEquals(0f,director.ui.hoverCardAlpha,.001f)
            }
            director.updateHover(null,false,SystemClock.elapsedRealtime())
            director.tick(0f)
            assertNotNull(director.actors.getValue("Eliza").root.components[HoverEffectComponent::class.java])
            assertEquals(0f,director.hamiltonFacing,.01f)
            assertEquals(.04f,director.hamiltonPosition.y,.01f)
            assertFalse(director.viewRig!!.moving)
            val worldPosition=director.world!!.components[com.pico.spatial.core.ecs.TransformComponent::class.java]!!.position
            assertTrue(kotlin.math.abs(worldPosition.x)>.5f || kotlin.math.abs(worldPosition.z+9.5f)>.5f)
            val burr=director.actors.getValue("Burr").member.position
            assertTrue(director.hamiltonPosition.distance(burr) in 1.1f..1.7f)
        }
        await(5000){director.ui.lyric!=null}
        instrumentation.runOnMainSync{assertTrue(director.ui.lyric!!.zh.isNotBlank())}
        val hold=InstrumentationRegistry.getArguments().getString("captureHoldMs")?.toLongOrNull() ?: 0L
        instrumentation.runOnMainSync{
            // Non-singers still accept dwell while Burr and Hamilton sing.
            val eliza=director.actors.getValue("Eliza").root
            val target=eliza.convertPositionTo(com.pico.spatial.core.math.Vector3(0f,1f,0f),null)
            // Framing changes the audience position. Aim from Eliza's clear front side;
            // another actor may correctly occlude her from the duet's new viewpoint.
            val origin=eliza.convertPositionTo(Vector3(0f,1f,4f),null)
            val role=director.hitRole(origin,target)
            assertEquals("Eliza",role)
            director.updateHover(role,true,SystemClock.elapsedRealtime())
        }
        await(4000){director.ui.hoverCardAlpha>.99f && director.ui.hoverCardRole=="Eliza"}
        instrumentation.runOnMainSync{assertTrue(director.nameCard!!.enabled)}
        if(hold>0L){
            android.util.Log.i("HamiltonVisualQA","READY_FOR_CAPTURE")
            SystemClock.sleep(hold.coerceAtMost(20000L))
        }
        instrumentation.runOnMainSync{
            director.stopPerformance()
            director.updateHover(null,false,SystemClock.elapsedRealtime())
            director.tick(0f)
            for(role in listOf("Burr","Hamilton"))assertNotNull(director.actors.getValue(role).root.components[HoverEffectComponent::class.java])
            director.updateHover("Burr",true,SystemClock.elapsedRealtime())
        }
        await(4000){director.ui.hoverCardRole=="Burr" && director.ui.hoverCardAlpha>.99f}
        instrumentation.runOnMainSync{
            director.select("Lafayette")
            assertEquals("Lafayette",director.ui.selected!!.role)
            assertNull(director.ui.lyric)
            director.beginYearDrag();director.dragYear(-1.7f);director.endYearDrag()
            assertEquals(2,director.ui.yearIndex)
            assertEquals(PerformancePhase.IDLE,director.ui.phase)
            assertEquals(setOf("Hamilton","Lafayette","Washington","Laurens","Mulligan"),director.actors.keys)
            assertTrue(director.actors.values.all{it.root.enabled})
            assertNull(director.ui.selected)
        }
        await(4000){kotlin.math.abs(director.ui.yearPosition-2f)<.005f}
        instrumentation.runOnMainSync{
            director.chooseYear(0)
            assertTrue(director.actors.values.all{it.root.enabled})
            assertEquals(PerformancePhase.IDLE,director.ui.phase)
        }
    }
    private fun capture(name:String){
        // PICO's compositor output is exposed by screencap; UiAutomation.takeScreenshot()
        // only sees the separate, empty Android 2D display on this emulator.
        val directory=java.io.File(instrumentation.targetContext.getExternalFilesDir(null),"six-years-qa").apply{mkdirs()}
        val output=java.io.File(directory,"$name.png")
        instrumentation.uiAutomation.executeShellCommand("screencap -p ${output.absolutePath}").use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
        assertTrue("Native capture missing: $name",output.length()>10000)
        // Gradle removes the tested package after the run; preserve QA captures outside app data.
        for(command in listOf("mkdir -p /sdcard/Download/hamilton-six-years", "cp ${output.absolutePath} /sdcard/Download/hamilton-six-years/$name.png")){
            instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
                java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
            }
        }

    }
    @Test fun allYearsPlayOnlyAfterApproachAndClearOnSwitch(){
        val director=launch()
        val representatives=listOf("Burr","Laurens","Washington","Madison","Maria","Jefferson")
        for((index,role) in representatives.withIndex()){
            instrumentation.runOnMainSync {
                director.hoverTracking.pause()
                director.chooseYear(index)
                assertEquals(director.catalog!!.scene(index).actors.map{it.role}.toSet(),director.actors.keys)
                assertTrue(director.actors.values.all{it.root.enabled && it.mouth!=null})
                if(index>0){
                    val hidden=director.world!!.findEntity("Actor_GeorgeIII")!!
                    assertFalse(hidden.enabled)
                    assertNull(director.roleFor(hidden))
                    assertFalse(director.selectFromTap(hidden,InteractionKind.GazePinch){"Hamilton"})
                }
                assertNull(director.ui.lyric)
                assertFalse(director.subtitlesVisible);assertFalse(director.lyricsPanel!!.enabled)
            }
            await(5000){kotlin.math.abs(director.ui.yearPosition-index.toFloat())<.005f && director.viewRig?.moving==false}
            SystemClock.sleep(1200)
            capture("${director.ui.years[index].year}-overview")
            instrumentation.runOnMainSync{
                assertTrue(director.selectFromTap(director.actors.getValue(role).root,InteractionKind.GazePinch))
                assertFalse(director.subtitlesVisible);assertFalse(director.lyricsPanel!!.enabled)
                assertTrue(director.ui.phase in listOf(PerformancePhase.WALKING,PerformancePhase.TURNING))
            }
            SystemClock.sleep(100)
            capture("${director.ui.years[index].year}-approaching")
            await(30000){
                if(director.ui.phase!=PerformancePhase.PLAYING){
                    assertFalse("Captions visible during ${director.ui.phase}",director.subtitlesVisible)
                    assertNull(director.ui.lyric)
                }
                director.ui.phase==PerformancePhase.PLAYING
            }
            await(5000){director.ui.lyric!=null && director.subtitlesVisible}
            instrumentation.runOnMainSync{
                assertEquals(director.ui.lyric,director.captions!!.displayedCue)
                assertEquals(role,director.ui.selected!!.role)
                assertTrue(director.ui.selected!!.music.id.startsWith(director.ui.years[index].year.toString()))
                assertTrue(director.ui.lyric!!.en.isNotBlank() && director.ui.lyric!!.zh.isNotBlank())
                assertFalse(director.viewRig!!.moving)
                assertEquals(0f,director.hamiltonFacing,.01f)
            }
            SystemClock.sleep(3200)
            capture("${director.ui.years[index].year}-singing")
            instrumentation.runOnMainSync{
                // Switching while the decoder is active must remove all previous performance UI.
                director.chooseYear((index+1)%6)
                assertEquals(PerformancePhase.IDLE,director.ui.phase)
                assertFalse(director.subtitlesVisible);assertFalse(director.lyricsPanel!!.enabled)
                assertFalse(director.biographyCard!!.enabled)
                assertNull(director.ui.selected)
                assertNull(director.ui.lyric)
            }
            SystemClock.sleep(300)
            instrumentation.runOnMainSync{assertEquals(PerformancePhase.IDLE,director.ui.phase)}
        }
        instrumentation.runOnMainSync{
            director.chooseYear(4);director.select("JamesReynolds")
            assertEquals(PerformancePhase.WALKING,director.ui.phase)
            assertFalse(director.subtitlesVisible);assertFalse(director.lyricsPanel!!.enabled)
            director.chooseYear(0)
            assertNull(director.ui.selected)
            assertEquals(PerformancePhase.IDLE,director.ui.phase)
        }
    }

}
