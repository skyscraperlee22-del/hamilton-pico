package com.hamilton.stage.performance

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.resource.*

/** Draw and upload the selected song ahead of playback. Cue changes only bind a texture. */
class StageCaptions() {
    private val mesh=MeshResource.createPlane(5.2f,.9048f)
    private val material=UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
        setCullingMode(MaterialCullingMode.NONE)
        setDepthWrite(false)
    }
    val entity=Entity().apply {
        setName("StageLyrics")
        components[ModelComponent::class.java]=ModelComponent(mesh,material)
        enabled=false
    }
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger()
    private val textures = mutableMapOf<LyricCue, TextureResource>()
    private var song: SongExcerpt? = null
    private var cue: LyricCue? = null
    val displayedCue: LyricCue? get() = cue.takeIf { entity.enabled }

    fun prepare(next: SongExcerpt?) {
        if (next === song) return
        song = next
        val batch = generation.incrementAndGet()
        show(null)
        textures.values.forEach { it.close() }
        textures.clear()
        if (next == null) return
        worker.execute {
            for (line in next.cues.distinct()) {
                if (batch != generation.get()) break
                val texture = try {
                    val bitmap = draw(line)
                    try { TextureResource.create(bitmap) } finally { bitmap.recycle() }
                } catch (error: Exception) {
                    Log.e("HamiltonCaptions", "Could not prepare caption for ${next.id} at ${line.start}", error)
                    continue
                }
                main.post {
                    if (batch == generation.get()) textures[line] = texture else texture.close()
                }
            }
        }
    }

    fun show(next: LyricCue?) {
        if (next == cue) return
        val texture = textures[next]
        if (texture == null) {
            if (entity.enabled) entity.enabled = false
            cue = null
            return
        }
        material.setBaseColorTexture(texture)
        if (!entity.enabled) entity.enabled = true
        cue = next
    }

    private fun draw(next: LyricCue): Bitmap {
        val bitmap=Bitmap.createBitmap(1500,261,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap).apply { scale(.75f, .75f) }
        fun layout(value:String,size:Float,color:Int):android.text.StaticLayout {
            val paint=android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize=size;this.color=color;typeface=android.graphics.Typeface.DEFAULT
                isFakeBoldText = true
            }
            return android.text.StaticLayout.Builder.obtain(value,0,value.length,paint,1900)
                .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
        }
        val en=layout(next.en,64f,0xFFFFF1D3.toInt())
        val zh=layout(next.zh,54f,0xFFE9C583.toInt())
        canvas.translate(50f,(348-en.height-zh.height-10)/2f)
        en.draw(canvas);canvas.translate(0f,en.height+10f);zh.draw(canvas)
        return bitmap
    }

    fun close() {
        generation.incrementAndGet()
        worker.shutdownNow()
        material.close()
        textures.values.forEach { it.close() }
        textures.clear()
        mesh.close()
    }
}
