package dev.kortex.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import dev.kortex.core.log.w
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OAuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val uri = intent.data
        if (uri != null) {
            val app = application as KortexApp
            app.container.appScope.launch {
                try {
                    app.container.mcpOAuthManager.handleCallback(uri)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(app, "OAuth linked successfully", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    AndroidLogger.w("OAuth", "Callback failed", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(app, "OAuth link failed: ${e.message}", Toast.LENGTH_SHORT).show()
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
