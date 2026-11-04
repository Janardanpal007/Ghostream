package com.fastdownloader.app.browser

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A saved sign-in: the site (its host name), the user name and the password. */
@Serializable
data class Login(
    val id: String,
    val site: String,
    val username: String,
    val password: String,
    val created: Long,
    val used: Long = 0,
)

/** Everything the password manager keeps: the sign-ins, sites never to save for, and whether to offer saving. */
@Serializable
data class Vault(
    val logins: List<Login> = emptyList(),
    val never: List<String> = emptyList(),
    val offer: Boolean = true,
)

/** A sign-in a page just sent, waiting for the user to save it (or not). */
data class PendingLogin(val site: String, val username: String, val password: String, val at: Long, val update: Login? = null)

/**
 * The browser's password manager. The vault is one file, encrypted with AES-256-GCM under a key that lives in the
 * phone's secure key store (Android Keystore) and never leaves it, so the file is useless off this phone.
 * Seeing and filling passwords asks for the fingerprint (or the screen lock): see ui/Passwords.kt.
 */
object Passwords {
    private const val ALIAS = "fd_passwords"
    private lateinit var file: File
    private val json = Json { ignoreUnknownKeys = true }
    private val _vault = MutableStateFlow(Vault())
    val vault: StateFlow<Vault> = _vault.asStateFlow()

    /** Until when the vault stays unlocked after a fingerprint (0: locked). */
    @Volatile var unlockedUntil = 0L
    const val UNLOCK_MS = 5 * 60 * 1000L
    val unlocked get() = System.currentTimeMillis() < unlockedUntil
    fun unlock() { unlockedUntil = System.currentTimeMillis() + UNLOCK_MS }
    fun lock() { unlockedUntil = 0 }

    fun init(ctx: Context) {
        if (::file.isInitialized) return
        file = File(ctx.filesDir, "passwords.bin")
        if (file.exists()) {
            // A second try: the phone's key store can be briefly unavailable just after starting or updating.
            val v = read(file) ?: run { Thread.sleep(300); read(file) }
            if (v != null) _vault.value = v
            // Still unreadable: keep the file aside (never overwritten) and start empty; it's tried again below.
            else file.renameTo(File(ctx.filesDir, "passwords.unreadable.${System.currentTimeMillis()}"))
        }
        // Files put aside on an earlier start: added back as soon as the key store reads them.
        ctx.filesDir.listFiles { f -> f.name.startsWith("passwords.unreadable") }?.forEach { f ->
            val back = read(f) ?: return@forEach
            merge(back)
            f.delete()
        }
        restoreFromBackup(ctx)
    }

    private fun read(f: File): Vault? =
        runCatching { json.decodeFromString(Vault.serializer(), String(decrypt(f.readBytes()))) }.getOrNull()

    private fun merge(back: Vault) = change { v ->
        val have = v.logins.map { it.site to it.username }.toSet()
        v.copy(logins = v.logins + back.logins.filter { (it.site to it.username) !in have }, never = (v.never + back.never).distinct())
    }

    // ---------- backup to the user's Google account (backup/AppBackup.kt) ----------

    const val RESTORE_FILE = "passwords.restore"

    /** The vault as the backup carries it; only while a backup runs (Android encrypts the backup end to end). */
    fun writeForBackup(out: File) {
        if (_vault.value.logins.isEmpty() && _vault.value.never.isEmpty()) return
        out.writeText(json.encodeToString(Vault.serializer(), _vault.value))
    }

    /** Passwords brought back from the backup on a new phone: added to the vault, encrypted with this phone's key. */
    @Synchronized
    fun restoreFromBackup(ctx: Context) {
        val f = File(ctx.filesDir, RESTORE_FILE)
        if (!f.exists()) return
        if (!::file.isInitialized) file = File(ctx.filesDir, "passwords.bin")
        runCatching { merge(json.decodeFromString(Vault.serializer(), f.readText())) }
        f.delete()
    }

    /** "accounts.google.com" for the page's address; www. is dropped so both forms find the same sign-ins. */
    fun siteOf(url: String): String? = Omnibox.hostOf(url)?.lowercase()?.removePrefix("www.")

    fun forSite(url: String): List<Login> {
        val site = siteOf(url) ?: return emptyList()
        return _vault.value.logins.filter { it.site == site }.sortedByDescending { maxOf(it.used, it.created) }
    }

    /** What to ask after a page sent [username] and [password]: save, update, or nothing (already saved / never). */
    fun pending(url: String, username: String, password: String): PendingLogin? {
        val site = siteOf(url) ?: return null
        val v = _vault.value
        if (!v.offer || password.isBlank() || site in v.never) return null
        val same = v.logins.firstOrNull { it.site == site && it.username == username }
        if (same != null && same.password == password) return null
        return PendingLogin(site, username, password, System.currentTimeMillis(), update = same)
    }

    fun save(p: PendingLogin) = change { v ->
        val now = System.currentTimeMillis()
        val others = v.logins.filterNot { it.site == p.site && it.username == p.username }
        val old = p.update ?: v.logins.firstOrNull { it.site == p.site && it.username == p.username }
        v.copy(logins = listOf(Login(old?.id ?: UUID.randomUUID().toString(), p.site, p.username, p.password, old?.created ?: now, now)) + others)
    }

    fun used(id: String) = change { v -> v.copy(logins = v.logins.map { if (it.id == id) it.copy(used = System.currentTimeMillis()) else it }) }

    fun delete(id: String) = change { v -> v.copy(logins = v.logins.filterNot { it.id == id }) }

    fun never(site: String) = change { v -> v.copy(never = (v.never + site).distinct()) }

    fun allowAgain(site: String) = change { v -> v.copy(never = v.never - site) }

    fun setOffer(on: Boolean) = change { v -> v.copy(offer = on) }

    private fun change(f: (Vault) -> Vault) {
        _vault.update(f)
        val bytes = encrypt(json.encodeToString(Vault.serializer(), _vault.value).toByteArray())
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(file)
    }

    // ---------- encryption ----------

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /** [iv length][iv][ciphertext with tag] */
    private fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val iv = c.iv
        return byteArrayOf(iv.size.toByte()) + iv + c.doFinal(plain)
    }

    private fun decrypt(data: ByteArray): ByteArray {
        val n = data[0].toInt()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 1, n))
        return c.doFinal(data, 1 + n, data.size - 1 - n)
    }
}
