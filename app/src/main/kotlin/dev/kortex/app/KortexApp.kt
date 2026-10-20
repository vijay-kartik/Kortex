package dev.kortex.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.appcheck.FirebaseAppCheck
import dagger.hilt.android.HiltAndroidApp
import dev.kortex.app.domain.agent.AgentBootstrap
import dev.kortex.app.domain.security.AppLock
import dev.kortex.core.log.AndroidLogger
import dev.kortex.core.log.Logger
import dev.kortex.finance.reminders.FinanceReminders
import dev.kortex.finance.sms.BankSms
import dev.kortex.links.domain.port.LinkThumbnails
import javax.inject.Inject

/** Root of the Hilt dependency graph (modules in `di/`). Registered as android:name in the manifest. */
@HiltAndroidApp
class KortexApp : Application() {

    @Inject lateinit var agentBootstrap: AgentBootstrap
    @Inject lateinit var appLock: AppLock
    @Inject lateinit var linkThumbnails: LinkThumbnails

    override fun onCreate() {
        // Before Hilt builds anything that logs: agent DEBUG/INFO lines carry user data, so only
        // debug builds send them to Logcat.
        if (BuildConfig.DEBUG) AndroidLogger.minLevel = Logger.Level.DEBUG
        super.onCreate()
        // Before anything calls a function: `financeKey` refuses calls App Check can't vouch for.
        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(appCheckProviderFactory())
        // Before any screen or share intake: tools, MCP servers and models are app-wide state.
        agentBootstrap.start()
        // Daily payment reminders; scheduling again keeps the job already queued.
        FinanceReminders.schedule(this)
        // Bank SMS a stopped job left unread; nothing happens while the feature is off.
        BankSms.catchUp(this)
        // Link thumbnails: drops files an interrupted delete left behind and retries failed downloads.
        linkThumbnails.start()
        // App lock times the whole app in the background, not each activity.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = appLock.onAppForegrounded()
            override fun onStop(owner: LifecycleOwner) = appLock.onAppBackgrounded()
        })
    }
}
