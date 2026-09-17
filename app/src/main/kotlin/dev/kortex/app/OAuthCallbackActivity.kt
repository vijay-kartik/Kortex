package dev.kortex.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.kortex.app.data.auth.McpOAuthManager
import dev.kortex.app.di.ApplicationScope
import dev.kortex.core.log.AndroidLogger
import dev.kortex.core.log.e
import dev.kortex.core.log.w
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OAuthCallbackActivity : Activity() {

    /** Hilt can't field-inject a plain [Activity], so dependencies come from the singleton graph. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        @ApplicationScope fun appScope(): CoroutineScope
        fun mcpOAuthManager(): McpOAuthManager
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val uri = intent.data
        if (uri != null) {
            val app = applicationContext
            val deps = EntryPointAccessors.fromApplication(app, Dependencies::class.java)
            deps.appScope().launch {
                try {
                    deps.mcpOAuthManager().handleCallback(uri)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(app, "OAuth linked successfully", Toast.LENGTH_SHORT).show()
                    }
                } catch (err: Exception) {
                    AndroidLogger.e("OAuth", "OAuth link failed: ${err.message}", err)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(app, "OAuth link failed. See logs.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        finish()
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
