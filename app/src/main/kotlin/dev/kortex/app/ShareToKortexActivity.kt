package dev.kortex.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import dev.kortex.app.domain.share.ShareAgentRunner
import dev.kortex.design.KortexTheme
import dev.kortex.core.state.Attachment
import dev.kortex.app.ui.screens.share.ShareComposer
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import kotlinx.coroutines.withContext

/**
 * Landing point for photos/PDFs shared from other apps (gallery, file manager). Renders
 * as a floating composer over the host app — no full app launch: the user adds an
 * instruction, hits send, and [ShareAgentRunner] takes it from there in the background.
 */
@AndroidEntryPoint
class ShareToKortexActivity : ComponentActivity() {

    // Lazy: an invalid share intent finishes before the agent stack is needed.
    @Inject lateinit var shareAgentRunner: Lazy<ShareAgentRunner>

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (intent.action != Intent.ACTION_SEND || uri == null) {
            finish()
            return
        }

        // The completion ping needs POST_NOTIFICATIONS on 13+; ask up front, don't block.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val runner = shareAgentRunner.get()

        setContent {
            KortexTheme {
                ShareComposer(
                    loadAttachment = { readAttachment(uri) },
                    onSend = { text, attachment ->
                        runner.submit(text, attachment)
                        Toast.makeText(
                            this,
                            "Kortex is on it — you'll get a notification.",
                            Toast.LENGTH_SHORT,
                        ).show()
                        finish()
                    },
                    onDismiss = { finish() },
                )
            }
        }
    }

    /** Resolves the shared Uri into an in-memory [Attachment] (name, MIME, base64 bytes). */
    private suspend fun readAttachment(uri: Uri): Attachment? = withContext(Dispatchers.IO) {
        runCatching {
            val mimeType = intent.type?.takeIf { it != "*/*" }
                ?: contentResolver.getType(uri)
                ?: "application/octet-stream"
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@runCatching null
            var filename: String? = null
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val i = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) filename = cursor.getString(i)
                }
            }
            Attachment(
                mimeType = mimeType,
                dataBase64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
                filename = filename,
            )
        }.getOrNull()
    }
}
