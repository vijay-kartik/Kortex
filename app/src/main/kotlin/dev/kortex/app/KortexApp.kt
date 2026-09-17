package dev.kortex.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.kortex.app.domain.agent.AgentBootstrap
import javax.inject.Inject

/** Root of the Hilt dependency graph (modules in `di/`). Registered as android:name in the manifest. */
@HiltAndroidApp
class KortexApp : Application() {

    @Inject lateinit var agentBootstrap: AgentBootstrap

    override fun onCreate() {
        super.onCreate()
        // Before any screen or share intake: tools, MCP servers and models are app-wide state.
        agentBootstrap.start()
    }
}
