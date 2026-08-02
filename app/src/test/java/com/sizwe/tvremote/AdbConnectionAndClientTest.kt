package com.sizwe.tvremote

import android.content.Context
import com.sizwe.tvremote.adb.AdbClient
import com.sizwe.tvremote.adb.AdbConnection
import com.sizwe.tvremote.adb.AdbCrypto
import com.sizwe.tvremote.adb.AdbKeyStore
import com.sizwe.tvremote.adb.AdbMessage
import com.sizwe.tvremote.adb.AdbProtocol
import com.sizwe.tvremote.adb.AdbTransport
import com.sizwe.tvremote.adb.readAdbMessage
import com.sizwe.tvremote.adb.writeAdbMessage
import com.sizwe.tvremote.core.ConnectionState
import com.sizwe.tvremote.core.RemoteKey
import com.sizwe.tvremote.core.RemoteTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket

class AdbConnectionAndClientTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testScope: CoroutineScope
    private lateinit var testContext: Context
    private lateinit var keyStore: AdbKeyStore
    private var serverSocket: ServerSocket? = null

    private class TestContext(private val filesDirFile: File) : Context() {
        override fun getFilesDir(): File = filesDirFile
        override fun getApplicationContext(): Context = this
        // Abstract methods needed to compile and run
        override fun getAssets(): android.content.res.AssetManager? = null
        override fun getResources(): android.content.res.Resources? = null
        override fun getPackageManager(): android.content.pm.PackageManager? = null
        override fun getContentResolver(): android.content.ContentResolver? = null
        override fun getMainLooper(): android.os.Looper? = null
        override fun setTheme(resid: Int) {}
        override fun getTheme(): android.content.res.Resources.Theme? = null
        override fun getClassLoader(): ClassLoader? = null
        override fun getPackageName(): String = "com.sizwe.tvremote.test"
        override fun getApplicationInfo(): android.content.pm.ApplicationInfo? = null
        override fun getPackageResourcePath(): String? = null
        override fun getPackageCodePath(): String? = null
        override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences? = null
        override fun moveSharedPreferencesFrom(sourceContext: Context?, name: String?): Boolean = false
        override fun deleteSharedPreferences(name: String?): Boolean = false
        override fun openFileInput(name: String?): java.io.FileInputStream? = null
        override fun openFileOutput(name: String?, mode: Int): java.io.FileOutputStream? = null
        override fun deleteFile(name: String?): Boolean = false
        override fun getFileStreamPath(name: String?): File? = null
        override fun getDataDir(): File? = null
        override fun getCacheDir(): File? = null
        override fun getCodeCacheDir(): File? = null
        override fun getExternalFilesDir(type: String?): File? = null
        override fun getExternalFilesDirs(type: String?): Array<File>? = null
        override fun getExternalCacheDir(): File? = null
        override fun getExternalCacheDirs(): Array<File>? = null
        override fun getObbDir(): File? = null
        override fun getObbDirs(): Array<File>? = null
        override fun getNoBackupFilesDir(): File? = null
        override fun getExternalMediaDirs(): Array<File>? = null
        override fun fileList(): Array<String>? = null
        override fun getDir(name: String?, mode: Int): File? = null
        override fun openOrCreateDatabase(name: String?, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?): android.database.sqlite.SQLiteDatabase? = null
        override fun openOrCreateDatabase(name: String?, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?, errorHandler: android.database.DatabaseErrorHandler?): android.database.sqlite.SQLiteDatabase? = null
        override fun moveDatabaseFrom(sourceContext: Context?, name: String?): Boolean = false
        override fun deleteDatabase(name: String?): Boolean = false
        override fun getDatabasePath(name: String?): File? = null
        override fun databaseList(): Array<String>? = null
        override fun getWallpaper(): android.graphics.drawable.Drawable? = null
        override fun peekWallpaper(): android.graphics.drawable.Drawable? = null
        override fun getWallpaperDesiredMinimumWidth(): Int = 0
        override fun getWallpaperDesiredMinimumHeight(): Int = 0
        override fun setWallpaper(bitmap: android.graphics.Bitmap?) {}
        override fun setWallpaper(data: java.io.InputStream?) {}
        override fun clearWallpaper() {}
        override fun startActivity(intent: android.content.Intent?) {}
        override fun startActivity(intent: android.content.Intent?, options: android.os.Bundle?) {}
        override fun startActivities(intents: Array<out android.content.Intent>?) {}
        override fun startActivities(intents: Array<out android.content.Intent>?, options: android.os.Bundle?) {}
        override fun startIntentSender(intent: android.content.IntentSender?, fillInIntent: android.content.Intent?, flagsMask: Int, flagsValues: Int, extraFlags: Int) {}
        override fun startIntentSender(intent: android.content.IntentSender?, fillInIntent: android.content.Intent?, flagsMask: Int, flagsValues: Int, extraFlags: Int, options: android.os.Bundle?) {}
        override fun sendBroadcast(intent: android.content.Intent?) {}
        override fun sendBroadcast(intent: android.content.Intent?, receiverPermission: String?) {}
        override fun sendOrderedBroadcast(intent: android.content.Intent?, receiverPermission: String?) {}
        override fun sendOrderedBroadcast(intent: android.content.Intent, receiverPermission: String?, resultReceiver: android.content.BroadcastReceiver?, scheduler: android.os.Handler?, initialCode: Int, initialData: String?, initialExtras: android.os.Bundle?) {}
        override fun sendBroadcastAsUser(intent: android.content.Intent?, user: android.os.UserHandle?) {}
        override fun sendBroadcastAsUser(intent: android.content.Intent?, user: android.os.UserHandle?, receiverPermission: String?) {}
        override fun sendOrderedBroadcastAsUser(intent: android.content.Intent?, user: android.os.UserHandle?, receiverPermission: String?, resultReceiver: android.content.BroadcastReceiver?, scheduler: android.os.Handler?, initialCode: Int, initialData: String?, initialExtras: android.os.Bundle?) {}
        override fun sendStickyBroadcast(intent: android.content.Intent?) {}
        override fun sendStickyOrderedBroadcast(intent: android.content.Intent?, resultReceiver: android.content.BroadcastReceiver?, scheduler: android.os.Handler?, initialCode: Int, initialData: String?, initialExtras: android.os.Bundle?) {}
        override fun removeStickyBroadcast(intent: android.content.Intent?) {}
        override fun sendStickyBroadcastAsUser(intent: android.content.Intent?, user: android.os.UserHandle?) {}
        override fun sendStickyOrderedBroadcastAsUser(intent: android.content.Intent?, user: android.os.UserHandle?, resultReceiver: android.content.BroadcastReceiver?, scheduler: android.os.Handler?, initialCode: Int, initialData: String?, initialExtras: android.os.Bundle?) {}
        override fun removeStickyBroadcastAsUser(intent: android.content.Intent?, user: android.os.UserHandle?) {}
        override fun registerReceiver(receiver: android.content.BroadcastReceiver?, filter: android.content.IntentFilter?): android.content.Intent? = null
        override fun registerReceiver(receiver: android.content.BroadcastReceiver?, filter: android.content.IntentFilter?, flags: Int): android.content.Intent? = null
        override fun registerReceiver(receiver: android.content.BroadcastReceiver?, filter: android.content.IntentFilter?, broadcastPermission: String?, scheduler: android.os.Handler?): android.content.Intent? = null
        override fun registerReceiver(receiver: android.content.BroadcastReceiver?, filter: android.content.IntentFilter?, broadcastPermission: String?, scheduler: android.os.Handler?, flags: Int): android.content.Intent? = null
        override fun unregisterReceiver(receiver: android.content.BroadcastReceiver?) {}
        override fun startService(service: android.content.Intent?): android.content.ComponentName? = null
        override fun startForegroundService(service: android.content.Intent?): android.content.ComponentName? = null
        override fun stopService(service: android.content.Intent?): Boolean = false
        override fun bindService(service: android.content.Intent, conn: android.content.ServiceConnection, flags: Int): Boolean = false
        override fun unbindService(conn: android.content.ServiceConnection) {}
        override fun startInstrumentation(className: android.content.ComponentName, profileFile: String?, arguments: android.os.Bundle?): Boolean = false
        override fun getSystemService(name: String): Any? = null
        override fun getSystemServiceName(serviceClass: Class<*>): String? = null
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int = 0
        override fun checkCallingPermission(permission: String): Int = 0
        override fun checkCallingOrSelfPermission(permission: String): Int = 0
        override fun checkSelfPermission(permission: String): Int = 0
        override fun enforcePermission(permission: String, pid: Int, uid: Int, message: String?) {}
        override fun enforceCallingPermission(permission: String, message: String?) {}
        override fun enforceCallingOrSelfPermission(permission: String, message: String?) {}
        override fun grantUriPermission(toPackage: String?, uri: android.net.Uri?, modeFlags: Int) {}
        override fun revokeUriPermission(uri: android.net.Uri?, modeFlags: Int) {}
        override fun revokeUriPermission(toPackage: String?, uri: android.net.Uri?, modeFlags: Int) {}
        override fun checkUriPermission(uri: android.net.Uri?, pid: Int, uid: Int, modeFlags: Int): Int = 0
        override fun checkCallingUriPermission(uri: android.net.Uri?, modeFlags: Int): Int = 0
        override fun checkCallingOrSelfUriPermission(uri: android.net.Uri?, modeFlags: Int): Int = 0
        override fun checkUriPermission(uri: android.net.Uri?, readPermission: String?, writePermission: String?, pid: Int, uid: Int, modeFlags: Int): Int = 0
        override fun enforceUriPermission(uri: android.net.Uri?, pid: Int, uid: Int, modeFlags: Int, message: String?) {}
        override fun enforceCallingUriPermission(uri: android.net.Uri?, modeFlags: Int, message: String?) {}
        override fun enforceCallingOrSelfUriPermission(uri: android.net.Uri?, modeFlags: Int, message: String?) {}
        override fun enforceUriPermission(uri: android.net.Uri?, readPermission: String?, writePermission: String?, pid: Int, uid: Int, modeFlags: Int, message: String?) {}
        override fun createPackageContext(packageName: String?, flags: Int): Context? = null
        override fun createContextForSplit(splitName: String?): Context? = null
        override fun createConfigurationContext(overrideConfiguration: android.content.res.Configuration): Context? = null
        override fun createDisplayContext(display: android.view.Display): Context? = null
        override fun createDeviceProtectedStorageContext(): Context? = null
        override fun isDeviceProtectedStorage(): Boolean = false
    }

    @Before
    fun setUp() {
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        testContext = TestContext(tempFolder.newFolder())
        keyStore = AdbKeyStore(testContext)
        serverSocket = ServerSocket(0)
    }

    @After
    fun tearDown() {
        serverSocket?.close()
        testScope.cancel()
    }

    @Test
    fun `handshake completes and onAwaitingAuthorization is triggered`() = runBlocking {
        val port = serverSocket!!.localPort
        val keyPair = keyStore.keyPair()

        val authorizationTriggered = CompletableDeferred<Boolean>()
        var serverError: Throwable? = null

        // 1. Start Mock ADB Server
        val serverJob = testScope.launch {
            try {
                val clientSocket = serverSocket!!.accept()
                val input = BufferedInputStream(clientSocket.getInputStream())
                val output = BufferedOutputStream(clientSocket.getOutputStream())

                try {
                    // Read CNXN connect sent by client
                    val clientCnxn = input.readAdbMessage()
                    assertEquals(AdbProtocol.A_CNXN, clientCnxn.command)

                    // Send AUTH token challenge
                    val dummyToken = ByteArray(20) { it.toByte() }
                    output.writeAdbMessage(AdbMessage(AdbProtocol.A_AUTH, AdbProtocol.AUTH_TYPE_TOKEN, 0, dummyToken))

                    // Read AUTH SIGNATURE response
                    val signatureMsg = input.readAdbMessage()
                    assertEquals(AdbProtocol.A_AUTH, signatureMsg.command)
                    assertEquals(AdbProtocol.AUTH_TYPE_SIGNATURE, signatureMsg.arg0)

                    // Simulate key is not yet known by server: send AUTH token again to request public key
                    output.writeAdbMessage(AdbMessage(AdbProtocol.A_AUTH, AdbProtocol.AUTH_TYPE_TOKEN, 0, dummyToken))

                    // Read AUTH RSAPUBLICKEY response
                    val pubKeyMsg = input.readAdbMessage()
                    assertEquals(AdbProtocol.A_AUTH, pubKeyMsg.command)
                    assertEquals(AdbProtocol.AUTH_TYPE_RSA_PUBLIC_KEY, pubKeyMsg.arg0)

                    // Finally, send CNXN back indicating successful authorization and connection
                    val banner = "device::ro.product.name=TestTVName;ro.product.model=TestTVModel"
                    output.writeAdbMessage(AdbMessage(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, AdbProtocol.MAX_PAYLOAD, banner.toByteArray()))
                } finally {
                    clientSocket.close()
                }
            } catch (t: Throwable) {
                serverError = t
                t.printStackTrace()
            }
        }

        // 2. Connect client and verify callback
        try {
            val adbConnection = AdbConnection.connect(
                host = "127.0.0.1",
                port = port,
                keyPair = keyPair,
                connectTimeoutMs = 1000,
                handshakeTimeoutMs = 5000,
                onAwaitingAuthorization = {
                    authorizationTriggered.complete(true)
                }
            )

            assertTrue(withTimeout(2000) { authorizationTriggered.await() })
            assertEquals("TestTVModel", parseDeviceLabel(adbConnection.deviceBanner))

            adbConnection.close()
        } catch (e: Throwable) {
            if (serverError != null) {
                throw serverError!!
            }
            throw e
        }
        serverJob.join()
        if (serverError != null) throw serverError!!
    }

    @Test
    fun `one keyevent changes the volume`() = runBlocking {
        val port = serverSocket!!.localPort
        val keyPair = keyStore.keyPair()

        val keyEventCommandDeferred = CompletableDeferred<String>()
        var serverError: Throwable? = null

        // 1. Start Mock ADB Server
        val serverJob = testScope.launch {
            try {
                val clientSocket = serverSocket!!.accept()
                val input = BufferedInputStream(clientSocket.getInputStream())
                val output = BufferedOutputStream(clientSocket.getOutputStream())

                try {
                    // Handshake (Direct trust, assume key is known to speed up test)
                    input.readAdbMessage() // CNXN
                    val dummyToken = ByteArray(20) { it.toByte() }
                    output.writeAdbMessage(AdbMessage(AdbProtocol.A_AUTH, AdbProtocol.AUTH_TYPE_TOKEN, 0, dummyToken))
                    input.readAdbMessage() // AUTH SIGNATURE
                    val banner = "device::ro.product.name=TestTVName;ro.product.model=TestTVModel"
                    output.writeAdbMessage(AdbMessage(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, AdbProtocol.MAX_PAYLOAD, banner.toByteArray()))

                    // Wait for the client to open a stream for the shell command
                    val openStreamMsg = input.readAdbMessage()
                    assertEquals(AdbProtocol.A_OPEN, openStreamMsg.command)
                    val destination = openStreamMsg.payloadAsString
                    keyEventCommandDeferred.complete(destination)
                } finally {
                    clientSocket.close()
                }
            } catch (t: Throwable) {
                serverError = t
                t.printStackTrace()
            }
        }

        // 2. Client initiates connection and sends key event
        try {
            val client = AdbClient(keyStore, testScope, AdbClient.Config(connectTimeoutMs = 1000, authTimeoutMs = 1000))
            val transport = AdbTransport(client)

            val connectResult = transport.connect(RemoteTarget.Network("127.0.0.1", port, "TestTV"))
            assertTrue(connectResult.isSuccess)

            // Send VOLUME_UP key event
            val sendResult = transport.sendKey(RemoteKey.VOLUME_UP)
            assertTrue(sendResult.isSuccess)

            // Verify command sent to the server
            val commandReceived = withTimeout(2000) { keyEventCommandDeferred.await() }
            assertEquals("shell:input keyevent 24", commandReceived)

            transport.disconnect()
        } catch (e: Throwable) {
            if (serverError != null) throw serverError!!
            throw e
        }
        serverJob.join()
        if (serverError != null) throw serverError!!
    }

    @Test
    fun `reconnect recover triggers on connection loss`() = runBlocking {
        val port = serverSocket!!.localPort
        val keyPair = keyStore.keyPair()

        // We use extremely low backoff settings to speed up JVM unit tests
        val lowBackoffConfig = AdbClient.Config(
            connectTimeoutMs = 500,
            authTimeoutMs = 1000,
            keepAliveIntervalMs = 500,
            maxReconnectAttempts = 3,
            backoffStartMs = 10,
            backoffMaxMs = 50
        )

        var clientSocket: Socket? = null
        var serverError: Throwable? = null

        // 1. Start Mock Server that accepts then drops the socket
        val serverJob = testScope.launch {
            try {
                // First accept
                clientSocket = serverSocket!!.accept()
                val input = BufferedInputStream(clientSocket!!.getInputStream())
                val output = BufferedOutputStream(clientSocket!!.getOutputStream())

                // Handshake (Direct trust)
                input.readAdbMessage() // CNXN
                val dummyToken = ByteArray(20) { it.toByte() }
                output.writeAdbMessage(AdbMessage(AdbProtocol.A_AUTH, AdbProtocol.AUTH_TYPE_TOKEN, 0, dummyToken))
                input.readAdbMessage() // AUTH SIGNATURE
                val banner = "device::ro.product.name=TestTVName;ro.product.model=TestTVModel"
                output.writeAdbMessage(AdbMessage(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, AdbProtocol.MAX_PAYLOAD, banner.toByteArray()))

                // Keep connection open briefly, then drop it
                delay(100)
                clientSocket!!.close()
            } catch (t: Throwable) {
                serverError = t
                t.printStackTrace()
            }
        }

        // 2. Initiate AdbClient
        try {
            val client = AdbClient(keyStore, testScope, lowBackoffConfig)
            val connectResult = client.connect("127.0.0.1", port)
            assertTrue(connectResult.isSuccess)

            // Wait until connection state goes to Reconnecting after the socket close
            val reconnectState = client.state.filterIsInstance<ConnectionState.Reconnecting>()
                .take(1)
                .first()

            assertEquals(1, reconnectState.attempt)
            assertTrue(reconnectState.nextRetryInMs >= 10)

            client.disconnect()
        } catch (e: Throwable) {
            if (serverError != null) throw serverError!!
            throw e
        }
        serverJob.join()
        if (serverError != null) throw serverError!!
    }

    private fun parseDeviceLabel(banner: String): String? {
        val model = banner.split(";")
            .firstOrNull { it.trim().startsWith("ro.product.model=") }
            ?.substringAfter("=")
            ?.trim()
        return model?.takeIf { it.isNotBlank() }
    }
}
