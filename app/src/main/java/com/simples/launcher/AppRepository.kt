package com.simples.launcher

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.lang.ref.WeakReference
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class LauncherApp(val pkg: String, val label: String)

class AppRepository(private val context: Context) {

    interface IconListener {
        fun onIconLoaded(pkg: String, icon: Drawable)
    }

    interface AppsListener {
        fun onAppsLoaded(apps: List<LauncherApp>)
    }

    private val pm = context.packageManager
    private val iconCache = LruCache<String, Drawable>(64)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val loader: ExecutorService = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private val versions = HashMap<String, Long>()
    private val inFlight = HashMap<String, Long>()
    private val again = HashSet<String>()
    private var epoch = 0L
    private var sequence = 0L
    private var loadSequence = 0L
    private var closed = false
    private var listener: WeakReference<IconListener>? = null
    private var appsListener: WeakReference<AppsListener>? = null

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

    fun cachedIcon(pkg: String): Drawable? = iconCache.get(pkg)

    fun setAppsListener(listener: AppsListener?) {
        synchronized(lock) { appsListener = listener?.let { WeakReference(it) } }
    }

    fun clearAppsListener() {
        synchronized(lock) { appsListener = null }
    }

    fun requestApps() {
        var taskId = 0L
        synchronized(lock) {
            if (closed) return
            taskId = ++loadSequence
        }
        try {
            loader.execute(Runnable {
                val result = runCatching { load() }.getOrNull()
                if (result != null) {
                    mainHandler.post {
                        val target = synchronized(lock) {
                            if (closed || loadSequence != taskId) null else appsListener?.get()
                        }
                        target?.onAppsLoaded(result)
                    }
                }
            })
        } catch (e: Exception) {
            // closing or rejected: no callback
        }
    }

    fun setIconListener(listener: IconListener?) {
        synchronized(lock) { this.listener = listener?.let { WeakReference(it) } }
    }

    fun clearIconListener() {
        synchronized(lock) { listener = null }
    }

    fun requestIcon(pkg: String) {
        var version = 0L
        var taskEpoch = 0L
        var taskId = 0L
        synchronized(lock) {
            if (closed || iconCache.get(pkg) != null) return
            if (inFlight.containsKey(pkg)) {
                return
            }
            version = versions[pkg] ?: 0L
            taskEpoch = epoch
            taskId = ++sequence
            inFlight[pkg] = taskId
        }
        val repo = WeakReference(this)
        try {
            executor.execute(Runnable {
                repo.get()?.decodeIcon(pkg, version, taskEpoch, taskId)
            })
        } catch (e: Exception) {
            synchronized(lock) {
                if (inFlight[pkg] == taskId) {
                    inFlight.remove(pkg)
                    again.remove(pkg)
                }
            }
        }
    }

    private fun decodeIcon(pkg: String, version: Long, taskEpoch: Long, taskId: Long) {
        var icon: Drawable? = null
        var accepted = false
        try {
            if (Thread.currentThread().isInterrupted) return
            val cached = iconCache.get(pkg)
            icon = cached ?: runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
            if (icon != null) {
                synchronized(lock) {
                    accepted = !closed && (versions[pkg] ?: 0L) == version && epoch == taskEpoch
                    if (accepted && cached == null) iconCache.put(pkg, icon)
                }
            }
        } finally {
            var retry = false
            synchronized(lock) {
                if (inFlight[pkg] == taskId) {
                    inFlight.remove(pkg)
                    retry = again.remove(pkg) && !closed
                }
            }
            if (accepted) {
                val repo = WeakReference(this)
                val result = icon
                if (result != null) {
                    mainHandler.post {
                        repo.get()?.deliverIcon(pkg, version, taskEpoch, result)
                    }
                }
            }
            if (retry) requestIcon(pkg)
        }
    }

    private fun deliverIcon(pkg: String, version: Long, taskEpoch: Long, icon: Drawable) {
        var target: IconListener? = null
        synchronized(lock) {
            if (closed || (versions[pkg] ?: 0L) != version || epoch != taskEpoch) return
            target = listener?.get()
        }
        target?.onIconLoaded(pkg, icon)
    }

    fun clearIcon(pkg: String) {
        synchronized(lock) {
            versions[pkg] = (versions[pkg] ?: 0L) + 1L
            if (inFlight.containsKey(pkg)) again.add(pkg)
            iconCache.remove(pkg)
        }
    }

    fun clearIcons() {
        synchronized(lock) {
            epoch++
            if (inFlight.isNotEmpty()) again.addAll(inFlight.keys)
            iconCache.evictAll()
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            listener = null
            appsListener = null
            inFlight.clear()
            again.clear()
        }
        executor.shutdownNow()
        loader.shutdownNow()
    }

    fun launch(pkg: String): Boolean {
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return false
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
