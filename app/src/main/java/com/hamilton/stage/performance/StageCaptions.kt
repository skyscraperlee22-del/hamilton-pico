package com.hamilton.stage.performance

import android.graphics.Bitmap
import android.graphics.Canvas
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.resource.*

/** Text-only native scene surface. Rebuild its texture only when the decoder changes cue. */
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
    private var texture:TextureResource?=null
    private var cue:LyricCue?=null
    val displayedCue:LyricCue? get()=cue.takeIf { entity.enabled }
    fun show(next:LyricCue?) {
        entity.enabled=next!=null
        if(next==null || next==cue)return
        cue=next
        val bitmap=Bitmap.createBitmap(2000,348,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap)
        fun layout(value:String,size:Float,color:Int):android.text.StaticLayout {
            val paint=android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize=size;this.color=color;typeface=android.graphics.Typeface.DEFAULT
                setShadowLayer(8f,0f,4f,0xD9000000.toInt())
            }
            return android.text.StaticLayout.Builder.obtain(value,0,value.length,paint,1900)
                .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
        }
        val en=layout(next.en,64f,0xFFFFF1D3.toInt())
        val zh=layout(next.zh,54f,0xFFE9C583.toInt())
        canvas.translate(50f,(348-en.height-zh.height-10)/2f)
        en.draw(canvas);canvas.translate(0f,en.height+10f);zh.draw(canvas)
        val updated=TextureResource.create(bitmap)
        bitmap.recycle()
        material.setBaseColorTexture(updated)
        val previous=texture;texture=updated;previous?.close()
    }
    fun close(){texture?.close();texture=null;material.close();mesh.close()}
}
