package dev.kortex.app.data.auth

import android.content.Intent
import dev.kortex.app.data.settings.SettingsStore
import dev.kortex.app.domain.gmail.GmailAccess
import dev.kortex.app.domain.gmail.GmailToken
import kotlinx.coroutines.flow.first

/** [GmailAccess] over the device's Google accounts, with the connected one kept in [settings]. */
class AccountManagerGmailAccess(
    private val auth: GmailAuthManager,
    private val settings: SettingsStore,
) : GmailAccess {

    override suspend fun token(): GmailToken {
        val account = settings.gmailAccountEmail.first()?.trim()?.takeIf { it.isNotBlank() }
            ?: return GmailToken.NotConnected
        return tokenFor(account)
    }

    override suspend fun connect(account: String): GmailToken {
        val trimmed = account.trim()
        return tokenFor(trimmed).also { if (it is GmailToken.Granted) settings.setGmailAccountEmail(trimmed) }
    }

    override fun invalidate(token: String) = auth.invalidateToken(token)

    override fun pickAccountIntent(): Intent = auth.pickGoogleAccountIntent()

    private suspend fun tokenFor(account: String): GmailToken = when (val result = auth.getToken(account)) {
        is GmailAuthManager.AuthResult.Success -> GmailToken.Granted(account, result.token)
        is GmailAuthManager.AuthResult.NeedsConsent -> GmailToken.NeedsConsent(result.intent)
        is GmailAuthManager.AuthResult.Error -> GmailToken.Failed(result.message)
    }
}
