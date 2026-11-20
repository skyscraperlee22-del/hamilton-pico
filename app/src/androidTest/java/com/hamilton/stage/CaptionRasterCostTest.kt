package com.hamilton.stage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hamilton.stage.performance.LyricCue
import com.hamilton.stage.performance.StageCatalog
import org.junit.Test
import org.junit.runner.RunWith

/** Isolate the v1.7.2 CPU raster cost, using identical text and interleaved trials. */
@RunWith(AndroidJUnit4::class)
class CaptionRasterCostTest {
    @Test fun compareOriginalCaptionShadow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cues = StageCatalog.load(context).scenes.first().actors.first { it.role == "Burr" }.music.cues
        val samples = arrayOf(mutableListOf<Double>(), mutableListOf<Double>())
        repeat(3) { round ->
            for ((index, cue) in cues.withIndex()) {
                val first = (round + index) % 2
                for (option in listOf(first, 1 - first)) {
                    val start = android.os.SystemClock.elapsedRealtimeNanos()
                    val bitmap = draw(cue, option == 1)
                    val ms = (android.os.SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                    bitmap.recycle()
                    if (round > 0) samples[option].add(ms)
                }
            }
        }
        for ((index, times) in samples.withIndex()) {
            times.sort()
            Log.i("HamiltonPerformance", "RASTER shadow=${index == 1} samples=${times.size} " +
                "medianMs=${(times[(times.size-1)/2] + times[times.size/2])/2} " +
                "p95Ms=${times[kotlin.math.ceil(times.size * .95).toInt() - 1]}")
        }
    }

    private fun draw(next: LyricCue, shadow: Boolean): Bitmap {
        val bitmap=Bitmap.createBitmap(2000,348,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap)
        fun layout(value:String,size:Float,color:Int):android.text.StaticLayout {
            val paint=android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize=size;this.color=color;typeface=android.graphics.Typeface.DEFAULT
                if (shadow) setShadowLayer(8f,0f,4f,0xD9000000.toInt())
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
}
