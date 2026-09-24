package dev.kortex.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
import dev.kortex.app.domain.agent.AgentBootstrap
import dev.kortex.app.domain.security.AppLock
import javax.inject.Inject

/** Root of the Hilt dependency graph (modules in `di/`). Registered as android:name in the manifest. */
@HiltAndroidApp
class KortexApp : Application() {

    @Inject lateinit var agentBootstrap: AgentBootstrap
    @Inject lateinit var appLock: AppLock

    override fun onCreate() {
        super.onCreate()
        // Before any screen or share intake: tools, MCP servers and models are app-wide state.
        agentBootstrap.start()
        // App lock times the whole app in the background, not each activity.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = appLock.onAppForegrounded()
            override fun onStop(owner: LifecycleOwner) = appLock.onAppBackgrounded()
        })
    }
}
