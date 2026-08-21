package com.tunlezah.dashcam

import android.app.Application

/**
 * Application entry point. Hosts the manually wired dependency graph
 * ([AppGraph]) — see docs/architecture.md for the DI decision rationale.
 */
class DashCamApplication : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}
