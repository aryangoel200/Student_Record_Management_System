package edu.iitgoa.attendance

import android.app.Application
import edu.iitgoa.attendance.data.AppContainer

/**
 * Application entry point.
 *
 * Dependencies are wired by hand in [AppContainer] rather than with a DI
 * framework. At this size the graph is about a dozen objects, and a plain
 * container keeps the build free of annotation processing.
 */
class AttendanceApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
