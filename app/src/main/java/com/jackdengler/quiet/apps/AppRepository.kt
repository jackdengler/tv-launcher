package com.jackdengler.quiet.apps

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

data class AppEntry(val packageName: String, val component: ComponentName, val label: String, val leanback: Boolean)

class AppRepository(private val context: Context) {
    private val pm = context.packageManager

    @Volatile private var cached: List<AppEntry>? = null
    private val images = LruCache<String, ImageBitmap>(96)

    private val _version = MutableStateFlow(0)

    /** Bumps whenever an app is installed, removed or updated. */
    val version: StateFlow<Int> = _version

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                cached = null
                images.evictAll()
                _version.value++
            }
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** Launchable apps (TV launcher entries preferred), sorted by label. */
    suspend fun installed(): List<AppEntry> = cached ?: withContext(Dispatchers.IO) {
        val byPackage = LinkedHashMap<String, AppEntry>()
        for ((category, leanback) in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER to true, Intent.CATEGORY_LAUNCHER to false)) {
            for (ri in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                val ai = ri.activityInfo
                if (ai.packageName == context.packageName || ai.packageName in byPackage) continue
                byPackage[ai.packageName] = AppEntry(
                    ai.packageName, ComponentName(ai.packageName, ai.name), ri.loadLabel(pm).toString(), leanback,
                )
            }
        }
        byPackage.values.sortedBy { it.label.lowercase() }.also { cached = it }
    }

    suspend fun find(packageName: String): AppEntry? = installed().firstOrNull { it.packageName == packageName }

    /** Square icon, rendered at [size] px. */
    suspend fun icon(app: AppEntry, size: Int): ImageBitmap? = image("i:${app.packageName}:$size") {
        pm.getActivityInfo(app.component, 0).loadIcon(pm)?.toBitmap(size, size)
    }

    /** 16:9 TV banner, or null when the app has none. */
    suspend fun banner(app: AppEntry, width: Int): ImageBitmap? = image("b:${app.packageName}:$width") {
        (pm.getActivityInfo(app.component, 0).loadBanner(pm) ?: pm.getApplicationBanner(app.packageName))
            ?.toBitmap(width, width * 9 / 16)
    }

    private suspend fun image(key: String, load: () -> android.graphics.Bitmap?): ImageBitmap? {
        images.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching { load() }.getOrNull()?.also { it.prepareToDraw() }?.asImageBitmap()
        }?.also { images.put(key, it) }
    }

    fun launch(app: AppEntry) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(if (app.leanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        start(intent)
    }

    /** Opens the Play Store page for an app that is not installed. */
    fun openStore(packageName: String) {
        start(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openSystemSettings() {
        start(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun start(intent: Intent) {
        runCatching { context.startActivity(intent) }
    }
}
