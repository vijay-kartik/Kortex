package dev.kortex.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import dev.kortex.app.data.files.AttachmentRead
import dev.kortex.app.data.files.readAttachment
import dev.kortex.app.domain.security.AppLock
import dev.kortex.app.domain.share.ShareAgentRunner
import dev.kortex.app.domain.share.SharedFileLoad
import dev.kortex.app.domain.share.SharedUriPolicy
import dev.kortex.app.ui.security.AppLockGate
import dev.kortex.app.ui.security.applyAppLockWindowPolicy
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
class ShareToKortexActivity : FragmentActivity() {

    @Inject lateinit var appLock: AppLock

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
        // Kortex reads the Uri as itself: refuse file paths and Kortex's own providers.
        if (!SharedUriPolicy.isAllowedSource(uri.scheme, uri.authority, packageName, ::providerPackage)) {
            Toast.makeText(this, "Kortex can't open that file.", Toast.LENGTH_SHORT).show()
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

        applyAppLockWindowPolicy(appLock)
        setContent {
            KortexTheme {
                AppLockGate(appLock) {
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
    }

    /** Package that owns content provider [authority], or null if none resolves. */
    @Suppress("DEPRECATION")
    private fun providerPackage(authority: String): String? =
        runCatching { packageManager.resolveContentProvider(authority, 0)?.packageName }.getOrNull()

    /**
     * Resolves the shared Uri into an in-memory [Attachment] (name, MIME, base64 bytes),
     * within the shared attachment size limit.
     */
    private suspend fun readAttachment(uri: Uri): SharedFileLoad = withContext(Dispatchers.IO) {
        // The provider's type, not the caller-supplied intent.type.
        val mimeType = runCatching { contentResolver.getType(uri) }.getOrNull()
            ?.takeIf { SharedUriPolicy.isAllowedMimeType(it) }
            ?: return@withContext SharedFileLoad.Refused("Kortex only takes photos and PDFs.")
        when (val read = contentResolver.readAttachment(uri, mimeType)) {
            is AttachmentRead.Ok -> SharedFileLoad.Loaded(read.attachment)
            is AttachmentRead.TooLarge -> SharedFileLoad.Refused(read.message)
            AttachmentRead.Unreadable ->
                SharedFileLoad.Refused("Couldn't read the shared file. Go back and try sharing it again.")
        }
    }
}
