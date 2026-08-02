package com.sizwe.tvremote.adb

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyPair

/**
 * Persists the ADB identity across launches.
 *
 * This is the fix for the most annoying Phase 1 failure mode: a fresh key pair on every launch
 * means the TV pops "Allow debugging from this computer?" every single time, and "Always allow"
 * never sticks because the fingerprint keeps changing. Generate once, reuse forever.
 *
 * The key lives in app-private storage (`filesDir/adb/`), so it is readable only by this app and
 * is wiped on uninstall. It is deliberately *not* in the Android Keystore: signing needs
 * `RSA/ECB/NoPadding` over a caller-supplied pad, which hardware-backed keys refuse.
 */
class AdbKeyStore(context: Context) {

    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "adb")
    private val privateKeyFile = File(directory, "adbkey")
    private val publicKeyFile = File(directory, "adbkey.pub")

    private val mutex = Mutex()

    @Volatile
    private var cached: KeyPair? = null

    /** Loads the stored pair, generating and saving one the first time. */
    suspend fun keyPair(): KeyPair = mutex.withLock {
        cached?.let { return it }
        val pair = withContext(Dispatchers.IO) { loadOrCreate() }
        cached = pair
        pair
    }

    /**
     * Throws away the identity so the next connect asks the TV for authorisation again.
     * Exposed as "Forget this TV / re-authorise" in settings, for the case where the user tapped
     * Deny once and the TV then silently refuses forever.
     */
    suspend fun reset() = mutex.withLock {
        withContext(Dispatchers.IO) {
            privateKeyFile.delete()
            publicKeyFile.delete()
        }
        cached = null
    }

    val exists: Boolean
        get() = privateKeyFile.exists() && publicKeyFile.exists()

    private fun loadOrCreate(): KeyPair {
        if (privateKeyFile.exists() && publicKeyFile.exists()) {
            runCatching {
                return AdbCrypto.decodeKeyPair(privateKeyFile.readBytes(), publicKeyFile.readBytes())
            }.onFailure {
                // Corrupt or partially written key: regenerate rather than wedging the app.
                Log.w(TAG, "Stored ADB key unreadable, regenerating", it)
            }
        }

        val pair = AdbCrypto.generateKeyPair()
        directory.mkdirs()
        writeAtomically(privateKeyFile, pair.private.encoded)
        writeAtomically(publicKeyFile, pair.public.encoded)
        Log.i(TAG, "Generated a new ADB identity at ${directory.absolutePath}")
        return pair
    }

    /** Write to a temp file then rename, so a kill mid-write cannot leave half a key behind. */
    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            target.writeBytes(bytes)
            temp.delete()
        }
    }

    private companion object {
        const val TAG = "AdbKeyStore"
    }
}
