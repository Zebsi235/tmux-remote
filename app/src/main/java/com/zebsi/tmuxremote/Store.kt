package com.zebsi.tmuxremote

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.trilead.ssh2.crypto.keys.Ed25519KeyPairGenerator
import com.trilead.ssh2.crypto.keys.Ed25519PrivateKey
import com.trilead.ssh2.crypto.keys.Ed25519PublicKey
import java.security.KeyPair
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/** How a host logs in. */
object Auth {
    const val APP_KEY = 0
    const val IMPORTED_KEY = 1
    const val PASSWORD = 2
}

data class Host(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var host: String = "",
    var port: Int = 22,
    var user: String = "",
    var auth: Int = Auth.APP_KEY,
    /** Encrypted password or private key text, by [auth]. */
    var secret: String = "",
    /** Encrypted passphrase of an imported key (may be empty). */
    var passphrase: String = "",
    /** "algorithm base64key" of the server, learned on first connect. */
    var hostKey: String = "",
    var lastSession: String = "",
    /** Names of sessions that send a notification when they go quiet or ring the bell. */
    val watched: MutableSet<String> = LinkedHashSet(),
) {
    val label: String get() = name.ifBlank { "$user@$host" }

    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("host", host); put("port", port); put("user", user)
        put("auth", auth); put("secret", secret); put("passphrase", passphrase)
        put("hostKey", hostKey); put("lastSession", lastSession)
        put("watched", JSONArray(watched.toList()))
    }

    companion object {
        fun fromJson(o: JSONObject) = Host(
            id = o.getString("id"), name = o.optString("name"), host = o.optString("host"),
            port = o.optInt("port", 22), user = o.optString("user"), auth = o.optInt("auth"),
            secret = o.optString("secret"), passphrase = o.optString("passphrase"),
            hostKey = o.optString("hostKey"), lastSession = o.optString("lastSession"),
            watched = LinkedHashSet<String>().apply {
                val arr = o.optJSONArray("watched")
                if (arr != null) for (i in 0 until arr.length()) add(arr.getString(i))
            },
        )
    }
}

/** Hosts, settings and secrets. Secrets are encrypted with a key that lives in the Android keystore. */
object Store {
    private lateinit var prefs: SharedPreferences
    private const val KEY_ALIAS = "tmuxremote-secrets"

    fun init(ctx: Context) {
        if (!::prefs.isInitialized) prefs = ctx.applicationContext.getSharedPreferences("store", Context.MODE_PRIVATE)
    }

    // ---- hosts ----

    fun hosts(): MutableList<Host> {
        val arr = JSONArray(prefs.getString("hosts", "[]"))
        return MutableList(arr.length()) { Host.fromJson(arr.getJSONObject(it)) }
    }

    fun host(id: String): Host? = hosts().firstOrNull { it.id == id }

    fun saveHost(h: Host) {
        val list = hosts()
        val i = list.indexOfFirst { it.id == h.id }
        if (i >= 0) list[i] = h else list.add(h)
        write(list)
    }

    fun deleteHost(id: String) = write(hosts().filter { it.id != id })

    private fun write(list: List<Host>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("hosts", arr.toString()).apply()
    }

    // ---- settings ----

    var fontSizeSp: Int
        get() = prefs.getInt("fontSize", 12)
        set(v) = prefs.edit().putInt("fontSize", v.coerceIn(6, 30)).apply()

    var showTopBar: Boolean
        get() = prefs.getBoolean("topBar", true)
        set(v) = prefs.edit().putBoolean("topBar", v).apply()

    /** The "run unrestricted" question was shown once. */
    var askedBattery: Boolean
        get() = prefs.getBoolean("askedBattery", false)
        set(v) = prefs.edit().putBoolean("askedBattery", v).apply()

    /** Read out the short spoken line of each reply in the session on screen. */
    var speakReplies: Boolean
        get() = prefs.getBoolean("speakReplies", false)
        set(v) = prefs.edit().putBoolean("speakReplies", v).apply()

    /** Dictation language: "de" or "en". */
    var speechLang: String
        get() = prefs.getString("speechLang", null) ?: if (java.util.Locale.getDefault().language == "de") "de" else "en"
        set(v) = prefs.edit().putString("speechLang", v).apply()

    // ---- secrets ----

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        val out = c.iv + c.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(enc: String): String {
        if (enc.isEmpty()) return ""
        val raw = Base64.decode(enc, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, raw, 0, 12))
        return String(c.doFinal(raw, 12, raw.size - 12))
    }

    // ---- the app's own ed25519 key ----

    /** The app's key pair; made on first use. */
    @Synchronized
    fun appKeyPair(): KeyPair {
        val seedEnc = prefs.getString("appKeySeed", "") ?: ""
        val pubB64 = prefs.getString("appKeyPub", "") ?: ""
        if (seedEnc.isNotEmpty() && pubB64.isNotEmpty()) {
            val seed = Base64.decode(decrypt(seedEnc), Base64.NO_WRAP)
            return KeyPair(Ed25519PublicKey(Base64.decode(pubB64, Base64.NO_WRAP)), Ed25519PrivateKey(seed))
        }
        val gen = Ed25519KeyPairGenerator()
        gen.initialize(255, SecureRandom())
        val kp = gen.generateKeyPair()
        val priv = kp.private as Ed25519PrivateKey
        val pub = kp.public as Ed25519PublicKey
        prefs.edit()
            .putString("appKeySeed", encrypt(Base64.encodeToString(priv.seed, Base64.NO_WRAP)))
            .putString("appKeyPub", Base64.encodeToString(pub.abyte, Base64.NO_WRAP))
            .apply()
        return kp
    }

    /** The line for ~/.ssh/authorized_keys on the host. */
    fun appPublicKeyLine(): String {
        val pub = (appKeyPair().public as Ed25519PublicKey).abyte
        return TmuxScripts.ed25519PublicKeyLine(pub, "tmux-remote-phone")
    }
}
