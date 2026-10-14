package com.hamilton.stage.content

import android.util.Log
import android.view.Choreographer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pico.spatial.core.ecs.AttachmentPanelComponent
import com.pico.spatial.ui.foundation.content.attachmentPanelComponent
import com.pico.spatial.ui.foundation.content.panelSize
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import com.pico.spatial.ui.platform.LengthUnit
import com.hamilton.stage.performance.*
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ImageBasedLightSource
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.SpotLightComponent
import com.pico.spatial.core.ecs.StageEnvironmentLightingComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.light.ShadowFaceCullingMode
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Native-load evidence for the on-device integration test. */
object StageLoadState {
    @Volatile var ready = false
    @Volatile var failure: String? = null
    @Volatile var modelBatches = 0
}

// Stage and GLB use metres, right-handed coordinates and +Y up.
// Lower the theater by 45 cm for a slightly elevated audience view.
// The headset's tracked height remains controlled by the OS.
private val WORLD_OFFSET = Vector3(0f, -0.42f, -9.5f)

@Composable
fun HomeStage() {
    val owned = remember { StageResources() }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val director = remember { PerformanceDirector(context) }
    val converter = LocalPhysicalLengthConverter.current
    val yearScale = 6.8f / converter.dpToLength(1000.dp, LengthUnit.Meters)
    val infoScale = BIOGRAPHY_WIDTH_METRES / converter.dpToLength(480.dp, LengthUnit.Meters)
    val yearPanel = attachmentPanelComponent(size = panelSize(1000.dp, 176.dp)) { YearPanel(director) }
    val infoPanel = attachmentPanelComponent(size = panelSize(480.dp, androidx.compose.ui.unit.Dp.Unspecified),
        alignment = AttachmentPanelComponent.Alignment.BOTTOM_CENTER) { BiographyPanel(director) }
    val nameScale = 1.5f / converter.dpToLength(500.dp, LengthUnit.Meters)
    val namePanel = attachmentPanelComponent(size = panelSize(500.dp, 104.dp)) { HoverNameCard(director) }
    StageHoverPermission(director)
    DisposableEffect(owned) {
        registerSystem<PerformanceSystem>()
        val choreographer = Choreographer.getInstance()
        val framePump = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    director.advanceFrame(frameTimeNanos)
                    choreographer.postFrameCallback(this)
                }
            }
        }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) choreographer.postFrameCallback(framePump)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { director.stopPerformance(); director.hoverTracking.pause(); director.viewRig?.pause(); director.suspendFrames(); choreographer.removeFrameCallback(framePump) }
            if (event == Lifecycle.Event.ON_START) {
                director.hoverTracking.resume()
                director.viewRig?.resume()
                choreographer.removeFrameCallback(framePump)
                choreographer.postFrameCallback(framePump)
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            choreographer.removeFrameCallback(framePump)
            director.close()
            unregisterSystem<PerformanceSystem>()
            owned.close()
            director.releaseResources()
        }
    }
    SpatialView(modifier = Modifier.fillMaxSize().pointerInput(director) {
        // On the headset, GazePinch can arrive as a valid tap with entityUUID=-1.
        // Filtering by entity before dispatch drops that entire gesture.
        detectSpatialTapGesture(context) { value ->
            director.selectFromTap(value.targetEntity, value.interactionKind)
        }
    }, initial = { content, _ ->
        StageLoadState.ready = false
        StageLoadState.failure = null
        try {
            val world = Entity().apply {
                setName("HamiltonTheater")
                components[TransformComponent::class.java]?.setPosition(WORLD_OFFSET)
            }
            val anchor = Entity().apply { setName("AudienceReference"); addChild(world) }
            owned.root = anchor
            val skyMesh = MeshResource.createSphere(80f)
            val skyMaterial = UnlitMaterial.create().apply {
                setBaseColor(Color4(0.018f, 0.011f, 0.007f, 1f))
                setCullingMode(MaterialCullingMode.FRONT)
            }
            owned.skyMesh = skyMesh
            owned.skyMaterial = skyMaterial
            world.addChild(Entity().apply {
                setName("DarkTheaterSky")
                components[ModelComponent::class.java] = ModelComponent(skyMesh, skyMaterial)
            })
            val ambient = withContext(Dispatchers.IO) {
                TextureResource.load("theater-ambient.ktx")
            }
            owned.ambient = ambient
            world.addChild(Entity().apply {
                setName("TheaterAmbient")
                components[StageEnvironmentLightingComponent::class.java] =
                    StageEnvironmentLightingComponent(ImageBasedLightSource.Single(ambient), 8f)
            })
            addLighting(world)
            content.addEntity(anchor)
            // One async load in initial; attach the complete native hierarchy after success.
            val stage = Entity.loadSuspend("asset://hamilton-stage.glb")
            if (owned.closed) {
                stage.destroy()
                return@SpatialView
            }
            stage.setName("HamiltonStageGeometry")
            world.addChild(stage)
            StageLoadState.modelBatches = countMeshes(stage)
            fun addPanel(name: String, panel: AttachmentPanelComponent, position: Vector3, scale: Float): Entity {
                val entity = Entity().apply {
                    setName(name)
                    components[TransformComponent::class.java]?.setPosition(position)?.setScaleVector(Vector3(scale, scale, scale))
                    components[AttachmentPanelComponent::class.java] = panel
                }
                world.addChild(entity)
                return entity
            }
            addPanel("YearTimeline", yearPanel, Vector3(0f, 7.8f, -3.6f), yearScale)
            director.biographyCard = addPanel("CharacterBiography", infoPanel, Vector3.ZERO, infoScale).apply { enabled = false }
            director.captions = StageCaptions().also { director.lyricsPanel=it.entity;world.addChild(it.entity) }
            director.nameCard = addPanel("HoverNameCard", namePanel, Vector3(0f, 1f, 3.3f), nameScale).apply { enabled = false }
            director.load(context, world)
            StageLoadState.ready = true
            Log.i("HamiltonStage", "STAGE_READY meshes=${StageLoadState.modelBatches} units=meters offsetZ=-9.5 fullSpace=true")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            StageLoadState.failure = error.message ?: error.javaClass.simpleName
            Log.e("HamiltonStage", "STAGE_LOAD_FAILED", error)
        }
    })
}

private fun addLighting(world: Entity) {
    // One shadow-casting key and two shadow-free spots keep mobile rendering affordable.
    world.addChild(Entity().apply {
        setName("WarmKey")
        components[TransformComponent::class.java]?.setEulerAngles(EulerAngles(-55f, -25f, 0f))
        components[DirectionalLightComponent::class.java] = DirectionalLightComponent(
            color = Color4(1f, 0.79f, 0.53f, 1f), intensity = 2000f,
            castsShadowEnabled = true,
        ).apply {
            // Respect the inward-facing sky material in the shadow pass.
            shadowFaceCullingMode = ShadowFaceCullingMode.AUTO
        }
    })
    for (x in listOf(-4.5f, 4.5f)) {
        world.addChild(Entity().apply {
            setName(if (x < 0f) "StageSpotLeft" else "StageSpotRight")
            components[TransformComponent::class.java]?.apply {
                setPosition(Vector3(x, 8.3f, 0.2f))
                setEulerAngles(EulerAngles(-72f, 0f, 0f))
            }
            components[SpotLightComponent::class.java] = SpotLightComponent(
                color = Color4(1f, 0.68f, 0.32f, 1f), intensity = 18000f,
                attenuationRadius = 18f, innerAngle = 28f, outerAngle = 50f,
                castsShadowEnabled = false,
            )
        })
    }
}

private fun countMeshes(entity: Entity): Int =
    (if (entity.components[ModelComponent::class.java] != null) 1 else 0) +
        entity.getChildren().sumOf(::countMeshes)

private class StageResources {
    var root: Entity? = null
    var skyMesh: MeshResource? = null
    var skyMaterial: UnlitMaterial? = null
    var ambient: TextureResource? = null
    var closed = false
    fun close() {
        if (closed) return
        closed = true
        StageLoadState.ready = false
        root?.destroy(recursively = true)
        skyMesh?.close()
        skyMaterial?.close()
        ambient?.close()
        root = null
    }
}
