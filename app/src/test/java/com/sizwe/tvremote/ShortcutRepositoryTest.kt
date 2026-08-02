package com.sizwe.tvremote

import android.content.Context
import com.sizwe.tvremote.core.ConnectionState
import com.sizwe.tvremote.core.RemoteKey
import com.sizwe.tvremote.core.RemoteTarget
import com.sizwe.tvremote.core.RemoteTransport
import com.sizwe.tvremote.core.TransportCapability
import com.sizwe.tvremote.core.TransportType
import com.sizwe.tvremote.data.SettingsRepository
import com.sizwe.tvremote.shortcuts.ShortcutRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ShortcutRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testContext: Context
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var shortcutRepository: ShortcutRepository

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

    private class MockTransport(
        override val type: TransportType,
        private val supportedCapabilities: Set<TransportCapability>,
        private val packages: List<String>
    ) : RemoteTransport {
        override val capabilities: Set<TransportCapability> = supportedCapabilities
        override val state: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Idle)
        override var target: RemoteTarget? = null

        override suspend fun connect(target: RemoteTarget): Result<Unit> = Result.success(Unit)
        override suspend fun disconnect() {}
        override suspend fun sendKey(key: RemoteKey): Result<Unit> = Result.success(Unit)
        override suspend fun listPackages(): Result<List<String>> = Result.success(packages)
    }

    @Before
    fun setUp() {
        testContext = TestContext(tempFolder.newFolder())
        settingsRepository = SettingsRepository(testContext)
        shortcutRepository = ShortcutRepository(settingsRepository)
    }

    @Test
    fun `resolves shortcuts correctly with PACKAGE_QUERY capability`() = runBlocking {
        // Prepare transport that supports PACKAGE_QUERY and has Netflix & YouTube candidate packages installed
        val wifiTransport = MockTransport(
            type = TransportType.ADB,
            supportedCapabilities = setOf(TransportCapability.PACKAGE_QUERY),
            packages = listOf("com.netflix.ninja", "com.google.android.youtube.tv")
        )

        val shortcuts = shortcutRepository.refresh(wifiTransport)

        // Check resolved results
        assertTrue(shortcuts.any { it.id == "netflix" && it.packageName == "com.netflix.ninja" })
        assertTrue(shortcuts.any { it.id == "youtube" && it.packageName == "com.google.android.youtube.tv" })

        // Check if saved to settings repository cache
        val cachedShortcuts = shortcutRepository.cached()
        assertTrue(cachedShortcuts.any { it.id == "netflix" && it.packageName == "com.netflix.ninja" })
        assertTrue(cachedShortcuts.any { it.id == "youtube" && it.packageName == "com.google.android.youtube.tv" })
    }

    @Test
    fun `falls back to cached packages on transport with no PACKAGE_QUERY capability`() = runBlocking {
        // Pre-populate settings cache directly
        settingsRepository.setResolvedPackages(mapOf(
            "netflix" to "com.netflix.ninja",
            "youtube" to "com.google.android.youtube.tv"
        ))

        // Transport with no PACKAGE_QUERY capability (e.g. Bluetooth)
        val btTransport = MockTransport(
            type = TransportType.BLUETOOTH_HID,
            supportedCapabilities = emptySet(),
            packages = emptyList()
        )

        val shortcuts = shortcutRepository.refresh(btTransport)

        // It should resolve from the cached entries
        assertTrue(shortcuts.any { it.id == "netflix" && it.packageName == "com.netflix.ninja" })
        assertTrue(shortcuts.any { it.id == "youtube" && it.packageName == "com.google.android.youtube.tv" })
    }
}
