package com.xlollx.songport.providers

import android.content.Context
import android.content.Intent
import com.xlollx.songport.R
import com.xlollx.songport.auth.ServerLoginActivity
import com.xlollx.songport.data.TokenStore
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.ProviderException

/** Campi richiesti dalla schermata di accesso di un servizio "a credenziali" (server personali, username). */
data class LoginForm(
    val needsUrl: Boolean,
    val needsUser: Boolean,
    val needsSecret: Boolean,
    val secretLabelRes: Int = R.string.login_password,
    val hintRes: Int,
)

/**
 * Servizi senza OAuth: server personali (Subsonic/Navidrome, Jellyfin, Plex) e sorgenti che si
 * leggono con il solo nome utente (Last.fm, ListenBrainz). Le credenziali restano cifrate sul
 * telefono come i token OAuth e l'app parla soltanto con il server indicato dall'utente.
 */
abstract class CredentialsProvider : MusicProvider {
    abstract val loginForm: LoginForm

    /** Verifica le credenziali contro il servizio e, se valide, le salva. Lancia se non valide. */
    abstract suspend fun login(ctx: Context, url: String, user: String, secret: String)

    override fun isConfigured(ctx: Context) = true
    override fun isConnected(ctx: Context) = TokenStore(ctx).get(id) != null
    override fun accountName(ctx: Context): String? =
        TokenStore(ctx).get(id)?.let { t -> t.userName.ifBlank { null } ?: t.extra["url"]?.removePrefix("https://")?.removePrefix("http://") }

    override fun startAuth(ctx: Context) {
        ctx.startActivity(
            Intent(ctx, ServerLoginActivity::class.java)
                .putExtra(ServerLoginActivity.EXTRA_PROVIDER, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    override suspend fun completeAuth(ctx: Context, params: Map<String, String>, verifier: String) = Unit
    override fun disconnect(ctx: Context) = TokenStore(ctx).clear(id)

    protected fun creds(ctx: Context): Tokens =
        TokenStore(ctx).get(id) ?: throw ProviderException(ctx.getString(R.string.error_not_connected, displayName))

    protected fun save(ctx: Context, t: Tokens) = TokenStore(ctx).set(id, t)

    /** "navidrome.casa.lan:4533" -> "https://navidrome.casa.lan:4533", senza barra finale. */
    protected fun normalizeUrl(url: String): String {
        val u = url.trim().trimEnd('/')
        return if (u.startsWith("http://") || u.startsWith("https://")) u else "https://$u"
    }

    protected fun unsupported(ctx: Context): Nothing =
        throw ProviderException(ctx.getString(R.string.error_read_only, displayName))
}
