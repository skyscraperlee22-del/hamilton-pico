package com.hamilton.stage.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import com.hamilton.stage.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        launch(::mainApp)
    }
}
