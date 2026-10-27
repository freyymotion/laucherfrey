package com.simples.launcher

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.util.LruCache

data class LauncherApp(val pkg: String, val label: String)

class AppRepository(private val context: Context) {

    private val pm = context.packageManager
    private val iconCache = LruCache<String, Drawable>(64)

    fun load(): List<LauncherApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .asSequence()
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { LauncherApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    fun icon(pkg: String): Drawable? {
        iconCache.get(pkg)?.let { return it }
        val icon = runCatching { pm.getApplicationIcon(pkg) }.getOrNull() ?: return null
        iconCache.put(pkg, icon)
        return icon
    }

    fun clearIcons() {
        iconCache.evictAll()
    }

    fun launch(pkg: String): Boolean {
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return false
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
