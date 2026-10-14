package com.hamilton.stage

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hamilton.stage.content.StageLoadState
import com.hamilton.stage.platform.LaunchActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StageLaunchTest {
    @Test fun fullStageLoadsNativeGeometry() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.runOnMainSync {
            context.startActivity(Intent(context, LaunchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val deadline = SystemClock.elapsedRealtime() + 60000
        while (!StageLoadState.ready && StageLoadState.failure == null && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100)
        }
        assertNull("Native Stage loading error", StageLoadState.failure)
        assertTrue("Stage did not finish loading in 60 seconds", StageLoadState.ready)
        assertEquals("All 17 material batches must load into native ECS", 17, StageLoadState.modelBatches)
    }
}
