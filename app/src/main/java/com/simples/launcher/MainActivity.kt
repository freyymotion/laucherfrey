package com.simples.launcher

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : Activity() {

    companion object {
        private const val REQ_ROLE = 501
        private const val REQ_WALLPAPER = 502
        private const val WALLPAPER_FILE = "wallpaper.jpg"
        private const val MODE_FOLDERS = 0
        private const val MODE_CATEGORIES = 1
        private const val CATEGORY_NONE = "__uncategorized__"
    }

    private var palette: Palette = Themes.of(ThemeId.DARK)
    private lateinit var repo: AppRepository
    private lateinit var categoryStore: CategoryStore
    private var apps: List<LauncherApp> = emptyList()
    private var fullApps: List<LauncherApp> = emptyList()
    private var folders: MutableList<Folder> = mutableListOf()
    private var activeFolderId: String? = null
    private var contentMode = MODE_FOLDERS
    private var activeCategoryId: String? = null

    private lateinit var home: LinearLayout
    private lateinit var favBox: LinearLayout
    private lateinit var drawer: LinearLayout
    private lateinit var root: FrameLayout
    private lateinit var wallpaperView: ImageView
    private lateinit var wallpaperScrim: View
    private lateinit var grid: GridView
    private lateinit var settingsHost: FrameLayout
    private lateinit var searchInput: EditText
    private lateinit var searchClear: TextView
    private lateinit var emptyView: TextView
    private lateinit var modeRow: LinearLayout
    private lateinit var folderRow: LinearLayout
    private var gridAdapter: AppGridAdapter? = null
    private lateinit var pageIndicator: TextView

    private lateinit var pagesContainer: HorizontalScrollView
    private lateinit var pagesRow: LinearLayout
    private lateinit var diagnosticsPage: ScrollView
    private lateinit var diagnosticsContent: LinearLayout
    private var currentPage = 0
    private val diagnosticsHandler = Handler(Looper.getMainLooper())
    private val diagnosticsUpdateRunnable = Runnable { updateDiagnostics() }
    private var diagnosticsVisible = false
    private var isActivityResumed = false
    private var animatedWallpaper: Drawable? = null
    private var pendingWallpaperUri: Uri? = null
    private var pendingWallpaperIsGif = false

    private val wallpaperExecutor = Executors.newSingleThreadExecutor()
    private var wallpaperVersion = 0
    private var previewVersion = 0

    private data class Folder(val id: String, var name: String, val packages: MutableList<String>)

    private val searchHandler = Handler(Looper.getMainLooper())
    private val searchRunnable = Runnable {
        applyFilter()
        if (::grid.isInitialized) grid.setSelection(0)
    }

    private val prefs by lazy { getSharedPreferences("fav", MODE_PRIVATE) }
    private val serif: Typeface by lazy { Typeface.create("serif", Typeface.NORMAL) }

    private val iconListener = object : AppRepository.IconListener {
        override fun onIconLoaded(pkg: String, icon: Drawable) {
            if (!::favBox.isInitialized) return
            for (i in 0 until favBox.childCount) {
                val row = favBox.getChildAt(i) as? LinearLayout ?: continue
                val image = row.getChildAt(0) as? ImageView ?: continue
                if (image.tag == pkg) image.setImageDrawable(icon)
            }
            if (!::grid.isInitialized || drawer.visibility != View.VISIBLE) return
            for (i in 0 until grid.childCount) {
                val cell = grid.getChildAt(i) as? LinearLayout ?: continue
                val image = cell.getChildAt(0) as? ImageView ?: continue
                if (image.tag == pkg) image.setImageDrawable(icon)
            }
        }
    }

    private val appsListener = object : AppRepository.AppsListener {
        override fun onAppsLoaded(loaded: List<LauncherApp>) {
            if (isFinishing || isDestroyed) return
            fullApps = loaded
            pruneFolders()
            applyFilter()
        }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val changed = intent?.data?.schemeSpecificPart
            if (changed != null) repo.clearIcon(changed) else repo.clearIcons()
            refreshApps()
            showFavs()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        palette = Themes.of(FreyPrefs.theme(this))
        repo = AppRepository(this)
        categoryStore = CategoryStore(this)
        folders = loadFolders()
        repo.setIconListener(iconListener)
        repo.setAppsListener(appsListener)
        configureWindow()
        buildUi()
        loadSavedWallpaper()
        maybeShowFirstRun()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(packageReceiver, filter)
        }
        refreshApps()
        showFavs()
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        if (animatedWallpaper is Animatable) {
            (animatedWallpaper as Animatable).start()
        }
        if (::pagesContainer.isInitialized && currentPage == 1) {
            diagnosticsVisible = true
            updateDiagnostics()
            diagnosticsHandler.removeCallbacks(diagnosticsUpdateRunnable)
            diagnosticsHandler.postDelayed(diagnosticsUpdateRunnable, 2000)
        }
    }

    override fun onPause() {
        isActivityResumed = false
        if (animatedWallpaper is Animatable) {
            (animatedWallpaper as Animatable).stop()
        }
        diagnosticsHandler.removeCallbacks(diagnosticsUpdateRunnable)
        diagnosticsVisible = false
        super.onPause()
    }

    override fun onStop() {
        diagnosticsHandler.removeCallbacks(diagnosticsUpdateRunnable)
        diagnosticsVisible = false
        runCatching { unregisterReceiver(packageReceiver) }
        super.onStop()
    }

    override fun onDestroy() {
        diagnosticsHandler.removeCallbacks(diagnosticsUpdateRunnable)
        wallpaperExecutor.shutdown()
        searchHandler.removeCallbacks(searchRunnable)
        repo.clearIconListener()
        repo.clearAppsListener()
        repo.close()
        
        // Stop and release wallpaper animation
        if (animatedWallpaper is Animatable) {
            (animatedWallpaper as Animatable).stop()
        }
        animatedWallpaper = null
        
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (settingsHost.visibility == View.VISIBLE) closeSettings()
        showHome()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            settingsHost.visibility == View.VISIBLE -> closeSettings()
            drawer.visibility == View.VISIBLE -> showHome()
            ::pagesContainer.isInitialized && currentPage == 1 -> {
                currentPage = 0
                diagnosticsVisible = false
                diagnosticsHandler.removeCallbacks(diagnosticsUpdateRunnable)
                if (::pageIndicator.isInitialized) updatePageIndicator()
                pagesContainer.smoothScrollTo(0, 0)
            }
            else -> super.onBackPressed()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun panel(fill: Int, stroke: Int, radiusDp: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(fill)
        setStroke(maxOf(1, dp(1)), stroke)
    }

    private fun drawerBackground(): Drawable =
        if (FreyPrefs.theme(this) == ThemeId.GLASS) {
            GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xCC090B0F.toInt(), 0xCC171C24.toInt())
            )
        } else {
            background()
        }

    private fun background() = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(palette.bgTop, palette.bgBottom)
    )

    private fun chooseWallpaper() {
        val targets = mutableListOf<Intent>()
        if (Build.VERSION.SDK_INT >= 33) {
            targets.add(Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"))
        }
        targets.add(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        })
        for (intent in targets) {
            if (runCatching { startActivityForResult(intent, REQ_WALLPAPER) }.isSuccess) return
        }
        toast("Nenhum seletor de imagens disponível")
    }

    private fun chooseGif() {
        val targets = mutableListOf<Intent>()
        if (Build.VERSION.SDK_INT >= 33) {
            targets.add(Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/gif"))
        }
        targets.add(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/gif"
        })
        for (intent in targets) {
            if (runCatching { startActivityForResult(intent, REQ_WALLPAPER) }.isSuccess) return
        }
        toast("Nenhum seletor de GIF disponível")
    }

    private fun previewWallpaper(uri: Uri) {
        val version = ++previewVersion
        val isGif = isGifUri(uri)
        wallpaperExecutor.execute {
            if (isGif) {
                val drawable = runCatching {
                    decodeGif(uri)
                }.getOrNull()
                runOnUiThread {
                    if (isFinishing || isDestroyed || version != previewVersion) {
                        if (drawable is Animatable) drawable.stop()
                        return@runOnUiThread
                    }
                    if (drawable == null) {
                        toast("Não foi possível carregar o GIF")
                        pendingWallpaperUri = null
                        pendingWallpaperIsGif = false
                    } else {
                        pendingWallpaperUri = uri
                        pendingWallpaperIsGif = true
                        showWallpaperPreview(drawable)
                    }
                }
            } else {
                val decoded = runCatching {
                    decodeBounded { contentResolver.openInputStream(uri) }
                }.getOrNull()
                val bitmap = if (decoded == null) null else runCatching {
                    orientWallpaper(uri, decoded)
                }.getOrDefault(decoded)
                runOnUiThread {
                    if (isFinishing || isDestroyed || version != previewVersion) {
                        bitmap?.recycle()
                        return@runOnUiThread
                    }
                    if (bitmap == null) {
                        toast("Não foi possível carregar a imagem")
                        pendingWallpaperUri = null
                        pendingWallpaperIsGif = false
                    } else {
                        pendingWallpaperUri = uri
                        pendingWallpaperIsGif = false
                        showWallpaperPreview(BitmapDrawable(resources, bitmap))
                    }
                }
            }
        }
    }

    private fun isGifUri(uri: Uri): Boolean {
        return try {
            val mime = contentResolver.getType(uri)
            mime?.let { it == "image/gif" } ?: uri.lastPathSegment?.lowercase()?.endsWith(".gif") == true
        } catch (e: Exception) {
            uri.lastPathSegment?.lowercase()?.endsWith(".gif") == true
        }
    }

    private fun decodeBounded(streamFactory: () -> InputStream?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = streamFactory() ?: return null
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = wallpaperSample(bounds.outWidth, bounds.outHeight)
        }
        return streamFactory()?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun wallpaperSample(outWidth: Int, outHeight: Int): Int {
        val screen = maxOf(
            resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels
        )
        val maxDim = minOf(screen, 2048)
        var sample = 1
        while (outWidth / sample > maxDim || outHeight / sample > maxDim) sample *= 2
        return sample
    }

    private fun orientWallpaper(uri: Uri, bitmap: Bitmap): Bitmap {
        if (Build.VERSION.SDK_INT < 24) return bitmap
        val orientation = runCatching {
            contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        }.getOrNull() ?: return bitmap
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrNull()
        if (rotated != null && rotated !== bitmap) {
            bitmap.recycle()
            return rotated
        }
        return bitmap
    }

    private fun showWallpaperPreview(drawable: Drawable) {
        var applied = false
        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(drawable)
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(preview, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                minOf(dp(360), resources.displayMetrics.heightPixels / 2)
            ))
        }
        val dialog = AlertDialog.Builder(this, dialogStyle())
            .setTitle("Pré-visualização")
            .setView(box)
            .setPositiveButton("Aplicar") { _, _ ->
                applied = true
                applyWallpaper(drawable)
            }
            .setNegativeButton("Cancelar", null)
            .create()
        dialog.setOnDismissListener {
            if (!applied) {
                preview.setImageDrawable(null)
                if (drawable is BitmapDrawable) {
                    val bmp = drawable.bitmap
                    if (!bmp.isRecycled) bmp.recycle()
                } else if (drawable is Animatable) {
                    drawable.stop()
                }
                // Clear pending URI on cancel/dismiss
                pendingWallpaperUri = null
                pendingWallpaperIsGif = false
            }
        }
        dialog.show()
    }

    private fun isValidWallpaper(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        return runCatching {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
            options.outWidth > 0 && options.outHeight > 0
        }.getOrDefault(false)
    }

    private fun isGifFile(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        val name = file.name.lowercase()
        if (name.endsWith(".gif")) return true
        return try {
            file.inputStream().use { stream ->
                val header = ByteArray(6)
                stream.read(header)
                val headerStr = String(header, Charsets.US_ASCII)
                headerStr.startsWith("GIF87a") || headerStr.startsWith("GIF89a")
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun decodeGif(uri: Uri): Drawable? {
        return if (Build.VERSION.SDK_INT >= 28) {
            runCatching {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeDrawable(source)
            }.getOrNull()
        } else {
            runCatching {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val tempFile = File(filesDir, "temp_wallpaper.gif")
                    tempFile.outputStream().use { output ->
                        stream.copyTo(output)
                    }
                    MovieDrawable(tempFile.absolutePath)
                }
            }.getOrNull()
        }
    }

    private inner class MovieDrawable(private val filePath: String) : Drawable(), Animatable {
        private val movie = Movie.decodeFile(filePath)
        private var startTime = 0L
        private var running = false
        private val paint = Paint()

        override fun draw(canvas: Canvas) {
            if (movie == null || !running) return
            val dstRect = Rect()
            copyBounds(dstRect)
            
            // Protect against zero/empty bounds
            if (dstRect.isEmpty()) return
            
            // Calculate CENTER_CROP rect with protection against zero dimensions
            val movieWidth = movie.width().toFloat()
            val movieHeight = movie.height().toFloat()
            if (movieWidth <= 0f || movieHeight <= 0f) return
            
            val viewWidth = dstRect.width().toFloat()
            val viewHeight = dstRect.height().toFloat()
            if (viewWidth <= 0f || viewHeight <= 0f) return
            
            val scale = maxOf(viewWidth / movieWidth, viewHeight / movieHeight)
            val scaledWidth = movieWidth * scale
            val scaledHeight = movieHeight * scale
            val left = dstRect.centerX() - scaledWidth / 2
            val top = dstRect.centerY() - scaledHeight / 2
            
            val now = SystemClock.uptimeMillis()
            if (startTime == 0L) startTime = now
            val elapsed = now - startTime
            val duration = movie.duration()
            if (duration > 0) {
                val relTime = elapsed % duration
                movie.setTime(relTime.toInt())
            }
            canvas.save()
            canvas.translate(left, top)
            canvas.scale(scale, scale)
            movie.draw(canvas, 0f, 0f, paint)
            canvas.restore()
            
            if (running) {
                invalidateSelf()
            }
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth(): Int = movie?.width() ?: 0
        override fun getIntrinsicHeight(): Int = movie?.height() ?: 0

        override fun start() {
            running = true
            startTime = 0L
            invalidateSelf()
        }

        override fun stop() {
            running = false
        }

        override fun isRunning(): Boolean = running
    }

    private fun applyWallpaper(drawable: Drawable) {
        val version = ++wallpaperVersion
        val isGif = pendingWallpaperIsGif || drawable is MovieDrawable || (Build.VERSION.SDK_INT >= 28 && drawable is AnimatedImageDrawable)

        wallpaperExecutor.execute {
            val extension = if (isGif) ".gif" else ".jpg"
            val target = File(filesDir, "wallpaper$extension")
            val temp = File(filesDir, "wallpaper$extension.tmp")
            val backup = File(filesDir, "wallpaper$extension.bak")
            val backupTemp = File(filesDir, "wallpaper$extension.bak.tmp")

            var saved = false
            if (isGif && pendingWallpaperUri != null) {
                // Save GIF original file from URI
                saved = runCatching {
                    contentResolver.openInputStream(pendingWallpaperUri!!)?.use { input ->
                        FileOutputStream(temp).use { output ->
                            input.copyTo(output)
                            output.flush()
                        }
                    }
                    temp.isFile && temp.length() > 0L
                }.getOrDefault(false)
            } else if (drawable is BitmapDrawable) {
                val bitmap = drawable.bitmap
                saved = runCatching {
                    FileOutputStream(temp).use { out ->
                        val compressed = bitmap.compress(
                            Bitmap.CompressFormat.JPEG, 92, out
                        )
                        out.flush()
                        compressed && temp.length() > 0L
                    }
                }.getOrDefault(false)
            }

            if (saved && (isValidWallpaper(target) || isGifFile(target))) {
                val copied = runCatching {
                    target.inputStream().use { input ->
                        backupTemp.outputStream().use { output ->
                            input.copyTo(output)
                            output.flush()
                        }
                    }
                    isValidWallpaper(backupTemp) || isGifFile(backupTemp)
                }.getOrDefault(false)

                if (!copied) {
                    saved = false
                } else if (backup.exists() && !backup.delete()) {
                    saved = false
                } else if (!backupTemp.renameTo(backup)) {
                    saved = false
                } else if (!target.delete()) {
                    saved = false
                }
            } else if (saved && target.exists()) {
                if (!target.delete()) saved = false
            }

            if (saved && !temp.renameTo(target)) {
                saved = false
            }

            if (!saved && (isValidWallpaper(backup) || isGifFile(backup)) && !(isValidWallpaper(target) || isGifFile(target))) {
                runCatching { backup.copyTo(target, overwrite = true) }
            }

            if (temp.exists()) temp.delete()
            if (backupTemp.exists()) backupTemp.delete()

            runOnUiThread {
                if (isFinishing || isDestroyed || version != wallpaperVersion) {
                    if (drawable is BitmapDrawable && !drawable.bitmap.isRecycled) drawable.bitmap.recycle()
                    if (drawable is Animatable) drawable.stop()
                    return@runOnUiThread
                }

                if (!saved) {
                    if (drawable is BitmapDrawable && !drawable.bitmap.isRecycled) drawable.bitmap.recycle()
                    if (drawable is Animatable) drawable.stop()
                    toast("Não foi possível salvar")
                    return@runOnUiThread
                }

                FreyPrefs.setWallpaper(this, target.absolutePath)
                showWallpaper(drawable)
                toast("Papel de parede aplicado")
            }

            // Clear pending URI after use
            pendingWallpaperUri = null
            pendingWallpaperIsGif = false
        }
    }

    private fun loadSavedWallpaper() {
        val path = FreyPrefs.wallpaper(this)
        if (path.isNullOrEmpty()) {
            showDefaultBackground()
            return
        }

        val file = File(path)
        val version = wallpaperVersion
        val isGif = isGifFile(file)

        wallpaperExecutor.execute {
            var source = file

            if (!isValidWallpaper(source) && !isGif) {
                val backup = File(filesDir, if (isGifFile(File(filesDir, "wallpaper.gif"))) "wallpaper.gif.bak" else "wallpaper.jpg.bak")
                val restoreTemp = File(filesDir, if (isGifFile(File(filesDir, "wallpaper.gif"))) "wallpaper.gif.restore.tmp" else "wallpaper.jpg.restore.tmp")

                if (isValidWallpaper(backup) || isGifFile(backup)) {
                    val restored = runCatching {
                        if (restoreTemp.exists()) restoreTemp.delete()

                        backup.copyTo(restoreTemp, overwrite = true)

                        if (!isValidWallpaper(restoreTemp) && !isGifFile(restoreTemp)) {
                            restoreTemp.delete()
                            false
                        } else {
                            if (file.exists() && !file.delete()) {
                                restoreTemp.delete()
                                false
                            } else {
                                restoreTemp.renameTo(file)
                            }
                        }
                    }.getOrDefault(false)

                    if (restored && (isValidWallpaper(file) || isGifFile(file))) {
                        source = file
                    } else {
                        source = backup
                    }
                }
            }

            val drawable: Drawable? = if (isGif) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= 28) {
                        val source = ImageDecoder.createSource(source)
                        ImageDecoder.decodeDrawable(source)
                    } else {
                        MovieDrawable(source.absolutePath)
                    }
                }.getOrNull()
            } else if (isValidWallpaper(source)) {
                runCatching {
                    decodeBounded { source.inputStream() }
                }.getOrNull()?.let { BitmapDrawable(resources, it) }
            } else {
                null
            }

            runOnUiThread {
                if (isFinishing || isDestroyed || version != wallpaperVersion) {
                    if (drawable is Animatable) drawable.stop()
                    if (drawable is BitmapDrawable && !drawable.bitmap.isRecycled) drawable.bitmap.recycle()
                    return@runOnUiThread
                }

                if (drawable == null) {
                    FreyPrefs.setWallpaper(this, null)
                    showDefaultBackground()
                } else {
                    FreyPrefs.setWallpaper(this, source.absolutePath)
                    showWallpaper(drawable)
                }
            }
        }
    }

    private fun clearCustomWallpaper() {
        ++wallpaperVersion
        ++previewVersion

        // Stop current animation if any
        if (animatedWallpaper is Animatable) {
            (animatedWallpaper as Animatable).stop()
        }
        animatedWallpaper = null

        FreyPrefs.setWallpaper(this, null)
        showDefaultBackground()

        wallpaperExecutor.execute {
            val files = listOf(
                File(filesDir, "wallpaper.jpg"),
                File(filesDir, "wallpaper.jpg.tmp"),
                File(filesDir, "wallpaper.jpg.bak"),
                File(filesDir, "wallpaper.jpg.bak.tmp"),
                File(filesDir, "wallpaper.jpg.restore.tmp"),
                File(filesDir, "wallpaper.gif"),
                File(filesDir, "wallpaper.gif.tmp"),
                File(filesDir, "wallpaper.gif.bak"),
                File(filesDir, "wallpaper.gif.bak.tmp"),
                File(filesDir, "wallpaper.gif.restore.tmp"),
                File(filesDir, "temp_wallpaper.gif")
            )

            val deleted = files.all { file ->
                !file.exists() || file.delete()
            }

            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    if (deleted) {
                        toast("Fundo padrão restaurado")
                    } else {
                        toast("Fundo padrão ativado; alguns arquivos não puderam ser removidos")
                    }
                }
            }
        }
    }

    private fun showWallpaper(drawable: Drawable) {
        if (!::wallpaperView.isInitialized) return

        // Stop previous animation
        if (animatedWallpaper is Animatable) {
            (animatedWallpaper as Animatable).stop()
        }

        // Configure scale type for CENTER_CROP behavior
        wallpaperView.scaleType = ImageView.ScaleType.CENTER_CROP
        wallpaperView.setImageDrawable(drawable)
        animatedWallpaper = drawable

        wallpaperView.visibility = View.VISIBLE
        wallpaperScrim.visibility = View.VISIBLE

        // Start animation if it's a GIF and activity is resumed
        if (isActivityResumed && drawable is Animatable) {
            drawable.start()
        }
    }

    private fun showDefaultBackground() {
        if (!::wallpaperView.isInitialized) return
        wallpaperView.setImageDrawable(null)
        wallpaperView.visibility = View.GONE
        wallpaperScrim.visibility = View.GONE
    }

    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun space(weight: Float) = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, 0, weight)
    }

    private fun wrapWrap(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = top
            bottomMargin = bottom
        }

    private fun configureWindow() {
        window.setBackgroundDrawable(background())
        window.statusBarColor = palette.bgTop
        window.navigationBarColor =
            if (palette.light && Build.VERSION.SDK_INT < 26) 0xFF16171B.toInt() else palette.bgTop
        var flags = 0
        if (palette.light) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= 26) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.decorView.systemUiVisibility = flags
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { background = background() }
        wallpaperView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            isClickable = false
            isFocusable = false
            visibility = View.GONE
        }
        wallpaperScrim = View(this).apply {
            setBackgroundColor(if (palette.light) 0x99FFFFFF.toInt() else 0x99000000.toInt())
            isClickable = false
            isFocusable = false
            visibility = View.GONE
        }
        root.addView(wallpaperView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(wallpaperScrim, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        home = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(20), dp(24), dp(24))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val wordmark = text("FREY", 13f, palette.textDim).apply {
            typeface = serif
            letterSpacing = 0.35f
        }
        val settingsButton = text("Ajustes", 12f, palette.textDim).apply {
            setPadding(dp(10), dp(8), 0, dp(8))
            setOnClickListener { openSettings() }
        }
        topBar.addView(wordmark, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(settingsButton)
        home.addView(topBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val clock = TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = "HH:mm"
            textSize = 78f
            setTextColor(palette.text)
            typeface = serif
            includeFontPadding = false
            gravity = Gravity.CENTER
        }
        val date = TextClock(this).apply {
            format24Hour = "EEEE, d 'de' MMMM"
            format12Hour = "EEEE, d 'de' MMMM"
            textSize = 14f
            setTextColor(palette.textDim)
            typeface = serif
            gravity = Gravity.CENTER
        }

        home.addView(space(1f))
        home.addView(clock, wrapWrap(top = dp(4)))
        home.addView(date, wrapWrap(top = dp(8)))
        home.addView(space(1f))

        favBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        home.addView(favBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val allApps = text("Todos os apps", 13f, palette.accent).apply {
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(13), dp(22), dp(13))
            background = panel(palette.surface, palette.border, palette.radiusDp - 4)
            setOnClickListener { showDrawer() }
        }
        home.addView(allApps, wrapWrap(top = dp(20), bottom = dp(10)))

        pageIndicator = TextView(this).apply {
            textSize = 11f
            gravity = Gravity.CENTER
            letterSpacing = 0.05f
        }
        home.addView(pageIndicator, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(4) })
        updatePageIndicator()

        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), 0)
            background = drawerBackground()
            visibility = View.GONE
        }

        val drawerHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val drawerTitle = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        drawerTitle.addView(text("FREY", 11f, palette.textDim).apply {
            typeface = serif
            letterSpacing = 0.35f
        })
        drawerTitle.addView(text("Aplicativos", 22f, palette.text).apply {
            typeface = serif
            setPadding(0, dp(4), 0, 0)
        })
        val closeButton = text("Fechar", 12f, palette.textDim).apply {
            setPadding(dp(12), dp(10), 0, dp(10))
            setOnClickListener { showHome() }
        }
        drawerHeader.addView(drawerTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        drawerHeader.addView(closeButton)
        drawer.addView(drawerHeader, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        drawer.addView(modeRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })
        refreshModeBar()

        searchInput = EditText(this).apply {
            hint = "Pesquisar aplicativos"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            textSize = 14f
            setTextColor(palette.text)
            setHintTextColor(palette.textDim)
            background = panel(palette.surface, palette.border, maxOf(6, palette.radiusDp - 8))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        searchClear = text("Limpar", 11f, palette.accent).apply {
            setPadding(dp(10), dp(10), 0, dp(10))
            visibility = View.GONE
            setOnClickListener { searchInput.setText("") }
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchClear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                searchHandler.removeCallbacks(searchRunnable)
                searchHandler.postDelayed(searchRunnable, 120)
            }
        })
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        searchRow.addView(searchInput, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        searchRow.addView(searchClear)
        drawer.addView(searchRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        val folderScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        folderRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        folderScroll.addView(folderRow, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        drawer.addView(folderScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
        refreshFilterBar()

        grid = GridView(this).apply {
            numColumns = maxOf(3, resources.displayMetrics.widthPixels / dp(88))
            horizontalSpacing = dp(6)
            verticalSpacing = dp(6)
            setPadding(0, dp(14), 0, dp(20))
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            adapter = AppGridAdapter().also { gridAdapter = it }
            setOnItemClickListener { _, _, position, _ ->
                val pkg = apps.getOrNull(position)?.pkg ?: return@setOnItemClickListener
                openApp(pkg)
            }
            setOnItemLongClickListener { _, _, position, _ ->
                apps.getOrNull(position)?.let { showAppMenu(it) }
                true
            }
        }
        emptyView = text("Nenhum aplicativo", 14f, palette.textDim).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        val gridHost = FrameLayout(this)
        gridHost.addView(grid, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        gridHost.addView(emptyView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        drawer.addView(gridHost, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        settingsHost = FrameLayout(this).apply { visibility = View.GONE }

        buildDiagnosticsPage()

        pagesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        pagesContainer = object : HorizontalScrollView(this) {
            private var downX = 0f
            private var downY = 0f

            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                // If overlays are visible, let them handle touches
                if (settingsHost.visibility == View.VISIBLE || drawer.visibility == View.VISIBLE) {
                    return false
                }
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.x
                        downY = ev.y
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = Math.abs(ev.x - downX)
                        val dy = Math.abs(ev.y - downY)
                        // If vertical movement is stronger, don't intercept (allow vertical scroll in diagnostics)
                        if (dy > dp(10) && dy > dx * 1.2f) {
                            parent?.requestDisallowInterceptTouchEvent(true)
                            return false
                        }
                        // If horizontal movement is stronger, intercept for page swipe
                        if (dx > dp(14) && dx > dy * 1.1f) {
                            parent?.requestDisallowInterceptTouchEvent(true)
                            return super.onInterceptTouchEvent(ev)
                        }
                    }
                }
                return super.onInterceptTouchEvent(ev)
            }

            override fun onTouchEvent(ev: MotionEvent): Boolean {
                if (settingsHost.visibility == View.VISIBLE || drawer.visibility == View.VISIBLE) {
                    return false
                }
                return super.onTouchEvent(ev)
            }
        }.apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        root.addView(pagesContainer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        pagesContainer.addView(pagesRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pagesRow.addView(home, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pagesRow.addView(diagnosticsPage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        root.addView(drawer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(settingsHost, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        setContentView(root)

        pagesContainer.post {
            val pageWidthPx = pagesContainer.width
            if (pageWidthPx > 0) {
                val lpHome = home.layoutParams as? LinearLayout.LayoutParams
                if (lpHome != null) {
                    lpHome.width = pageWidthPx
                    home.layoutParams = lpHome
                }
                val lpDiag = diagnosticsPage.layoutParams as? LinearLayout.LayoutParams
                if (lpDiag != null) {
                    lpDiag.width = pageWidthPx
                    diagnosticsPage.layoutParams = lpDiag
                }
                pagesRow.requestLayout()
                val scrollX = if (currentPage == 1) pageWidthPx else 0
                pagesContainer.scrollTo(scrollX, 0)
            }
            updatePageIndicator()
        }

        pagesContainer.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val pageWidthPx = pagesContainer.width
                if (pageWidthPx > 0) {
                    val lpHome = home.layoutParams as? LinearLayout.LayoutParams
                    if (lpHome != null && lpHome.width != pageWidthPx) {
                        lpHome.width = pageWidthPx
                        home.layoutParams = lpHome
                    }
                    val lpDiag = diagnosticsPage.layoutParams as? LinearLayout.LayoutParams
                    if (lpDiag != null && lpDiag.width != pageWidthPx) {
                        lpDiag.width = pageWidthPx
                        diagnosticsPage.layoutParams = lpDiag
                    }
                    pagesRow.requestLayout()
                    pagesContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
            }
        })

        pagesContainer.setOnScrollChangeListener { _, scrollX, _, _, _ ->
            if (settingsHost.visibility == View.VISIBLE || drawer.visibility == View.VISIBLE) {
                return@setOnScrollChangeListener
            }
            val pageWidthPx = pagesContainer.width
            if (pageWidthPx <= 0) return@setOnScrollChangeListener
            val newPage = Math.round(scrollX.toFloat() / pageWidthPx.toFloat()).coerceIn(0, 1)
            if (newPage != currentPage) {
                currentPage = newPage
                updatePageIndicator()
                if (currentPage == 1) {
                    diagnosticsVisible = true
                    updateDiagnostics()
                    diagnosticsHandler.removeCallbacks(diagnosticsUpdateRunnable)
                    diagnosticsHandler.postDelayed(diagnosticsUpdateRunnable, 2000)
                } else {
                    diagnosticsVisible = false
                    diagnosticsHandler.removeCallbacks(diagnosticsUpdateRunnable)
                }
            }
        }
    }

    private inner class AppGridAdapter : BaseAdapter() {
        override fun getCount(): Int = apps.size
        override fun getItem(position: Int): Any = apps[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val cell = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(6), dp(10), dp(6), dp(10))
                addView(ImageView(this@MainActivity), LinearLayout.LayoutParams(dp(44), dp(44)))
                addView(TextView(this@MainActivity).apply {
                    textSize = 11.5f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    setPadding(0, dp(6), 0, 0)
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ))
                background = panel(palette.surface, palette.border, maxOf(4, palette.radiusDp - 8))
            }
            val app = apps.getOrNull(position) ?: return cell
            val image = cell.getChildAt(0) as ImageView
            image.tag = app.pkg
            val cached = repo.cachedIcon(app.pkg)
            image.setImageDrawable(cached ?: packageManager.defaultActivityIcon)
            if (cached == null) repo.requestIcon(app.pkg)
            (cell.getChildAt(1) as TextView).apply {
                text = app.label
                setTextColor(palette.text)
            }
            return cell
        }
    }

    private fun refreshApps() {
        repo.requestApps()
    }

    private fun applyFilter() {
        val query = if (::searchInput.isInitialized) searchInput.text.toString().trim().lowercase() else ""
        val folder = if (contentMode == MODE_FOLDERS) {
            folders.firstOrNull { it.id == activeFolderId }
        } else {
            null
        }
        if (contentMode == MODE_FOLDERS && folder == null) activeFolderId = null
        val base = when {
            contentMode == MODE_CATEGORIES -> categoryApps()
            folder == null -> fullApps
            else -> {
                val wanted = folder.packages.toHashSet()
                fullApps.filter { it.pkg in wanted }
            }
        }
        apps = if (query.isEmpty()) base else base.filter { it.label.lowercase().contains(query) }
        gridAdapter?.notifyDataSetChanged()
        if (::emptyView.isInitialized) {
            emptyView.text = when {
                query.isNotEmpty() -> "Nenhum app encontrado"
                contentMode == MODE_CATEGORIES && activeCategoryId == CATEGORY_NONE ->
                    "Nenhum app sem categoria"
                contentMode == MODE_CATEGORIES && activeCategoryId != null -> "Categoria vazia"
                folder != null -> "Pasta vazia"
                else -> "Nenhum aplicativo"
            }
            emptyView.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun categoryApps(): List<LauncherApp> {
        if (activeCategoryId == null) return fullApps

        val automaticByPackage = fullApps.associate { app ->
            app.pkg to AppCategories.classify(
                app.label,
                app.pkg,
                app.platformCategory
            )
        }
        val effectiveByPackage =
            categoryStore.effectiveCategoryIds(automaticByPackage)

        return fullApps.filter { app ->
            val effective = effectiveByPackage[app.pkg]
            if (activeCategoryId == CATEGORY_NONE) effective == null
            else effective == activeCategoryId
        }
    }

    private fun refreshFilterBar() {
        if (contentMode == MODE_FOLDERS) refreshFolderBar() else refreshCategoryBar()
    }

    private fun refreshFolderBar() {
        if (contentMode != MODE_FOLDERS || !::folderRow.isInitialized) return
        folderRow.removeAllViews()
        folderRow.addView(folderChip("Tudo", activeFolderId == null) {
            activeFolderId = null
            refreshFolderBar()
            applyFilter()
            grid.setSelection(0)
        }, chipParams())
        for (folder in folders) {
            val chip = folderChip(folder.name, activeFolderId == folder.id) {
                activeFolderId = folder.id
                refreshFolderBar()
                applyFilter()
                grid.setSelection(0)
            }
            chip.setOnLongClickListener { showFolderMenu(folder); true }
            folderRow.addView(chip, chipParams())
        }
        folderRow.addView(folderChip("+ Pasta", false) { createFolder() }, chipParams())
    }

    private fun refreshCategoryBar() {
        if (contentMode != MODE_CATEGORIES || !::folderRow.isInitialized) return

        val categories = categoryStore.allCategories()
        if (activeCategoryId != null &&
            activeCategoryId != CATEGORY_NONE &&
            categories.none { it.id == activeCategoryId }
        ) {
            activeCategoryId = null
        }

        folderRow.removeAllViews()
        folderRow.addView(folderChip("Tudo", activeCategoryId == null) {
            activeCategoryId = null
            refreshCategoryBar()
            applyFilter()
            grid.setSelection(0)
        }, chipParams())
        for (category in categories) {
            folderRow.addView(folderChip(category.name, activeCategoryId == category.id) {
                activeCategoryId = category.id
                refreshCategoryBar()
                applyFilter()
                grid.setSelection(0)
            }, chipParams())
        }
        folderRow.addView(folderChip("Sem categoria", activeCategoryId == CATEGORY_NONE) {
            activeCategoryId = CATEGORY_NONE
            refreshCategoryBar()
            applyFilter()
            grid.setSelection(0)
        }, chipParams())
    }

    private fun refreshModeBar() {
        if (!::modeRow.isInitialized) return
        modeRow.removeAllViews()
        modeRow.addView(folderChip("Pastas", contentMode == MODE_FOLDERS) {
            setContentMode(MODE_FOLDERS)
        }, chipParams())
        modeRow.addView(folderChip("Categorias", contentMode == MODE_CATEGORIES) {
            setContentMode(MODE_CATEGORIES)
        }, chipParams())
    }

    private fun setContentMode(mode: Int) {
        if (contentMode == mode) return
        contentMode = mode
        refreshModeBar()
        refreshFilterBar()
        applyFilter()
        if (::grid.isInitialized) grid.setSelection(0)
    }

    private fun chipParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { rightMargin = dp(8) }

    private fun folderChip(label: String, active: Boolean, onClick: () -> Unit): TextView =
        text(label, 12f, if (active) palette.accent else palette.textDim).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = panel(
                palette.surface,
                if (active) palette.accent else palette.border,
                maxOf(6, palette.radiusDp - 8)
            )
            setOnClickListener { onClick() }
        }

    private fun dialogStyle() = if (palette.light) {
        android.R.style.Theme_Material_Light_Dialog_Alert
    } else {
        android.R.style.Theme_Material_Dialog_Alert
    }

    private fun showFolderMenu(folder: Folder) {
        val actions = arrayOf("Renomear", "Excluir")
        AlertDialog.Builder(this, dialogStyle())
            .setTitle(folder.name)
            .setItems(actions) { _, which ->
                if (which == 0) renameFolder(folder) else deleteFolder(folder)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun promptFolderName(title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSelection(text.length)
            setSingleLine()
            textSize = 15f
            setTextColor(palette.text)
            setHintTextColor(palette.textDim)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        AlertDialog.Builder(this, dialogStyle())
            .setTitle(title)
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) toast("Informe um nome") else onOk(name)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun createFolder() {
        promptFolderName("Nova pasta", "") { name ->
            if (folders.any { it.name.equals(name, ignoreCase = true) }) {
                toast("Já existe uma pasta com esse nome")
                return@promptFolderName
            }
            folders.add(Folder(UUID.randomUUID().toString(), name, mutableListOf()))
            saveFolders()
            refreshFolderBar()
        }
    }

    private fun renameFolder(folder: Folder) {
        promptFolderName("Renomear pasta", folder.name) { name ->
            if (folders.any { it.id != folder.id && it.name.equals(name, ignoreCase = true) }) {
                toast("Já existe uma pasta com esse nome")
                return@promptFolderName
            }
            folder.name = name
            saveFolders()
            refreshFolderBar()
        }
    }

    private fun deleteFolder(folder: Folder) {
        AlertDialog.Builder(this, dialogStyle())
            .setTitle("Excluir pasta")
            .setMessage("Excluir \"${folder.name}\"? Os apps não serão desinstalados.")
            .setPositiveButton("Excluir") { _, _ ->
                folders.remove(folder)
                if (activeFolderId == folder.id) activeFolderId = null
                saveFolders()
                refreshFolderBar()
                applyFilter()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun addToFolder(pkg: String) {
        if (folders.isEmpty()) {
            toast("Crie uma pasta primeiro")
            return
        }
        AlertDialog.Builder(this, dialogStyle())
            .setTitle("Adicionar à pasta")
            .setItems(folders.map { it.name }.toTypedArray()) { _, which ->
                val folder = folders[which]
                if (pkg in folder.packages) {
                    toast("Este app já está nesta pasta")
                } else {
                    folder.packages.add(pkg)
                    saveFolders()
                    toast("Adicionado a ${folder.name}")
                    if (activeFolderId == folder.id) applyFilter()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun removeFromFolder(folder: Folder, pkg: String) {
        if (folder.packages.remove(pkg)) {
            saveFolders()
            toast("Removido da pasta")
            applyFilter()
        }
    }

    private fun loadFolders(): MutableList<Folder> {
        val raw = getSharedPreferences("folders", MODE_PRIVATE).getString("data", null)
            ?: return mutableListOf()
        return runCatching {
            val items = JSONArray(raw)
            val out = mutableListOf<Folder>()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val name = item.optString("name")
                if (name.isEmpty()) continue
                val id = item.optString("id").ifEmpty { UUID.randomUUID().toString() }
                val packages = mutableListOf<String>()
                val saved = item.optJSONArray("apps") ?: JSONArray()
                for (j in 0 until saved.length()) {
                    val pkg = saved.optString(j)
                    if (pkg.isNotEmpty() && pkg !in packages) packages.add(pkg)
                }
                out.add(Folder(id, name, packages))
            }
            out
        }.getOrDefault(mutableListOf())
    }

    private fun saveFolders() {
        val items = JSONArray()
        for (folder in folders) {
            val item = JSONObject()
            item.put("id", folder.id)
            item.put("name", folder.name)
            val packages = JSONArray()
            folder.packages.forEach { packages.put(it) }
            item.put("apps", packages)
            items.put(item)
        }
        getSharedPreferences("folders", MODE_PRIVATE).edit()
            .putString("data", items.toString()).apply()
    }

    private fun pruneFolders() {
        var changed = false
        for (folder in folders) {
            if (folder.packages.removeAll { !isInstalled(it) }) changed = true
        }
        if (changed) saveFolders()
    }

    private fun isInstalled(pkg: String): Boolean = try {
        packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    } catch (e: Exception) {
        true
    }

    private fun showDrawer() {
        home.visibility = View.GONE
        drawer.visibility = View.VISIBLE
        grid.setSelection(0)
    }

    private fun showHome() {
        if (::searchInput.isInitialized) {
            searchInput.clearFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(searchInput.windowToken, 0)
        }
        drawer.visibility = View.GONE
        home.visibility = View.VISIBLE
    }

    private fun openSettings() {
        SettingsPanel(
            activity = this,
            palette = palette,
            current = FreyPrefs.theme(this),
            versionLabel = "Versão ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            devMode = FreyPrefs.devMode(this),
            diagnostics = diagnosticsText(),
            onDevModeUnlocked = {
                FreyPrefs.setDevMode(this, true)
                toast("Modo desenvolvedor ativado")
                openSettings()
            },
            onThemeSelected = { theme ->
                FreyPrefs.setTheme(this, theme)
                closeSettings()
                recreate()
            },
            onRequestDefault = { requestDefaultLauncher() },
            onPickWallpaper = { chooseWallpaper() },
            onPickGif = { chooseGif() },
            onClearWallpaper = { clearCustomWallpaper() },
            onClose = { closeSettings() }
        ).also { panel ->
            settingsHost.removeAllViews()
            settingsHost.addView(panel.view, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            settingsHost.visibility = View.VISIBLE
        }
    }

    private fun isDefaultLauncher(): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                return roleManager.isRoleHeld(RoleManager.ROLE_HOME)
            }
        }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == packageName
    }

    private fun maybeShowFirstRun() {
        if (FreyPrefs.firstRunDone(this) || isDefaultLauncher()) return
        AlertDialog.Builder(this, dialogStyle())
            .setTitle("Bem-vindo ao FREY")
            .setMessage(
                "Para usar o FREY como tela inicial, defina-o como launcher padrão. " +
                    "O Android vai pedir a sua confirmação."
            )
            .setPositiveButton("Definir agora") { _, _ -> requestDefaultLauncher() }
            .setNegativeButton("Agora não", null)
            .setOnDismissListener { FreyPrefs.setFirstRunDone(this) }
            .show()
    }

    private fun diagnosticsText(): String {
        val metrics = resources.displayMetrics
        return buildString {
            appendLine("FREY ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Pacote: $packageName")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Aparelho: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Tela: ${metrics.widthPixels}x${metrics.heightPixels} @ ${metrics.density}")
            appendLine("Launcher padrão: ${if (isDefaultLauncher()) "sim" else "não"}")
            appendLine("Tema: ${FreyPrefs.theme(this@MainActivity).label}")
            appendLine(
                "Papel de parede: " +
                    if (FreyPrefs.wallpaper(this@MainActivity).isNullOrEmpty()) "padrão" else "personalizado"
            )
            append("Atualizações: ${Updates.REPO} (GitHub Releases)")
        }
    }

    private fun closeSettings() {
        settingsHost.visibility = View.GONE
        settingsHost.removeAllViews()
    }

    private fun requestDefaultLauncher() {
        if (Build.VERSION.SDK_INT >= 29) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                if (roleManager.isRoleHeld(RoleManager.ROLE_HOME)) {
                    toast("FREY já é o launcher padrão")
                    return
                }
                toast("Confirme o FREY na tela do sistema")
                val requested = runCatching {
                    startActivityForResult(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_ROLE)
                }.isSuccess
                if (requested) return
            }
        }
        openHomeSettings()
    }

    private fun openHomeSettings() {
        toast("Escolha o FREY na tela do sistema")
        val opened = runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }.isSuccess
        if (!opened) {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_WALLPAPER) {
            if (resultCode == RESULT_OK) data?.data?.let { previewWallpaper(it) }
            return
        }
        if (requestCode != REQ_ROLE) return
        if (resultCode == RESULT_OK) {
            toast("FREY definido como padrão")
            return
        }
        val held = if (Build.VERSION.SDK_INT >= 29) {
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true
        } else {
            false
        }
        toast(if (held) "FREY é o launcher padrão" else "Seleção não concluída. Use os Ajustes do sistema.")
    }

    private fun favs(): MutableList<String> =
        (prefs.getString("l", "") ?: "").split("|").filter { it.isNotEmpty() }.toMutableList()

    private fun saveFavs(list: List<String>) {
        prefs.edit().putString("l", list.joinToString("|")).apply()
    }

    private fun toggleFav(pkg: String) {
        val list = favs()
        when {
            list.contains(pkg) -> {
                list.remove(pkg)
                toast("Removido da tela inicial")
            }
            list.size >= 5 -> {
                toast("Máximo de 5 atalhos")
                return
            }
            else -> {
                list.add(pkg)
                toast("Adicionado à tela inicial")
            }
        }
        saveFavs(list)
        showFavs()
    }

    private fun showAppMenu(app: LauncherApp) {
        val pkg = app.pkg
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions.add("Abrir" to { openApp(pkg) })
        actions.add("Informações do app" to { openAppInfo(pkg) })
        actions.add((if (pkg in favs()) "Desfavoritar" else "Favoritar") to { toggleFav(pkg) })
        actions.add("Categoria" to { showCategoryPicker(app) })
        val activeFolder = if (contentMode == MODE_FOLDERS) {
            folders.firstOrNull { it.id == activeFolderId }
        } else {
            null
        }
        if (activeFolder != null && pkg in activeFolder.packages) {
            actions.add("Remover da pasta" to { removeFromFolder(activeFolder, pkg) })
        }
        actions.add("Adicionar à pasta" to { addToFolder(pkg) })
        if (canUninstall(pkg)) actions.add("Desinstalar" to { uninstallApp(pkg) })

        val dialog = AlertDialog.Builder(this, dialogStyle())
            .setTitle(app.label)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions[which].second()
            }
            .setNegativeButton("Cancelar", null)
            .create()

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(
                panel(0xF016171B.toInt(), 0xFF41434B.toInt(), 22)
            )
        }
        dialog.show()
    }

    private fun showCategoryPicker(app: LauncherApp) {
        val categories = categoryStore.allCategories()
        val labels = categories.map { it.name } + listOf("Sem categoria", "Automática")
        AlertDialog.Builder(this, dialogStyle())
            .setTitle("Categoria de ${app.label}")
            .setItems(labels.toTypedArray()) { _, which ->
                val applied = when {
                    which < categories.size ->
                        categoryStore.setManualCategory(app.pkg, categories[which].id)
                    which == categories.size ->
                        categoryStore.setManuallyUncategorized(app.pkg)
                    else -> {
                        categoryStore.clearManualCategory(app.pkg)
                        true
                    }
                }
                if (!applied) {
                    toast("Não foi possível atualizar a categoria")
                    return@setItems
                }
                toast("Categoria atualizada")
                applyFilter()
                if (::grid.isInitialized) grid.setSelection(0)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun openApp(pkg: String) {
        if (!repo.launch(pkg)) toast("Não foi possível abrir este app")
    }

    private fun openAppInfo(pkg: String) {
        val opened = runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
        }.isSuccess
        if (!opened) toast("Não foi possível abrir as informações")
    }

    private fun canUninstall(pkg: String): Boolean = runCatching {
        val flags = packageManager.getApplicationInfo(pkg, 0).flags
        (flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0
    }.getOrDefault(false)

    private fun uninstallApp(pkg: String) {
        val opened = runCatching {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
        }.isSuccess
        if (!opened) toast("Não foi possível desinstalar")
    }

    private fun showFavs() {
        favBox.removeAllViews()
        val stored = favs()
        val valid = mutableListOf<Pair<String, String>>()
        for (pkg in stored) {
            val label = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) {
                continue
            }
            valid.add(pkg to label)
            if (valid.size == 5) break
        }
        val validPackages = valid.map { it.first }
        if (validPackages != stored) saveFavs(validPackages)
        for ((pkg, label) in valid) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = panel(palette.surface, palette.border, maxOf(6, palette.radiusDp - 6))
                setOnClickListener { if (!repo.launch(pkg)) toast("Não foi possível abrir este app") }
                setOnLongClickListener {
                    toggleFav(pkg)
                    true
                }
            }
            val icon = ImageView(this).apply {
                tag = pkg
                val cached = repo.cachedIcon(pkg)
                setImageDrawable(cached ?: packageManager.defaultActivityIcon)
                if (cached == null) repo.requestIcon(pkg)
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(28), dp(28)))
            row.addView(text(label, 15f, palette.text).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(12), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            favBox.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
        }
        if (valid.isEmpty()) {
            favBox.addView(text("Segure um app na lista\npara fixar aqui", 13f, palette.textDim).apply {
                gravity = Gravity.CENTER
            })
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun buildDiagnosticsPage() {
        diagnosticsPage = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        diagnosticsContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(40))
        }
        diagnosticsPage.addView(diagnosticsContent, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Diagnósticos"
            textSize = 22f
            setTextColor(0xFFF1F0EC.toInt())
            typeface = Typeface.create("serif", Typeface.NORMAL)
            letterSpacing = 0.02f
        }
        titleRow.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val refreshBtn = TextView(this).apply {
            text = "Atualizar"
            textSize = 12f
            setTextColor(0xFFB9A9FF.toInt())
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(0x1A6C5CE7.toInt())
                setStroke(dp(1), 0xFF6C5CE7.toInt())
            }
            setOnClickListener {
                diagnosticsHandler.removeCallbacks(diagnosticsUpdateRunnable)
                updateDiagnostics()
                if (currentPage == 1 && isActivityResumed) {
                    diagnosticsHandler.postDelayed(diagnosticsUpdateRunnable, 2000)
                }
            }
        }
        titleRow.addView(refreshBtn)

        diagnosticsContent.addView(titleRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(6) })

        val subtitle = TextView(this).apply {
            text = "Dados reais via APIs Android"
            textSize = 12f
            setTextColor(0xFF9A9A9F.toInt())
        }
        diagnosticsContent.addView(subtitle, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(18) })

        diagnosticsContent.addView(buildDiagCard("Memória RAM"))
        diagnosticsContent.addView(buildDiagCard("Bateria"))
        diagnosticsContent.addView(buildDiagCard("Armazenamento"))
        diagnosticsContent.addView(buildDiagCard("Tempo de atividade"))
        diagnosticsContent.addView(buildDiagCard("Sistema avançado"))
        diagnosticsContent.addView(buildDiagCard("Limitações"))

        updateDiagnostics()
    }

    private fun buildDiagCard(section: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(0xE617181C.toInt())
                setStroke(dp(1), 0xFF6C5CE7.toInt())
            }
        }
        val header = TextView(this).apply {
            text = section
            textSize = 15f
            setTextColor(0xFFF1F0EC.toInt())
            typeface = Typeface.create("serif", Typeface.NORMAL)
        }
        card.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) })
        val body = TextView(this).apply {
            text = "Carregando..."
            textSize = 12f
            setTextColor(0xFFBFB9B3.toInt())
            setLineSpacing(0f, 1.4f)
        }
        body.tag = "diag_body_$section"
        card.addView(body)
        card.tag = "diag_card_$section"
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(12)
        card.layoutParams = lp
        return card
    }

    private fun getDiagBody(section: String): TextView? {
        val card = diagnosticsContent.findViewWithTag<LinearLayout>("diag_card_$section") ?: return null
        for (i in 0 until card.childCount) {
            val v = card.getChildAt(i)
            if (v.tag == "diag_body_$section" && v is TextView) return v
        }
        return null
    }

    private fun updatePageIndicator() {
        if (!::pageIndicator.isInitialized) return
        val homeColor = if (currentPage == 0) 0xFFB9A9FF.toInt() else 0xFF6B6B70.toInt()
        val diagColor = if (currentPage == 1) 0xFFB9A9FF.toInt() else 0xFF6B6B70.toInt()
        val spannable = android.text.SpannableStringBuilder()
        val homePart = android.text.SpannableString("Home")
        homePart.setSpan(android.text.style.ForegroundColorSpan(homeColor), 0, homePart.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.append(homePart)
        spannable.append("  •  ")
        val diagPart = android.text.SpannableString("Diagnósticos")
        diagPart.setSpan(android.text.style.ForegroundColorSpan(diagColor), 0, diagPart.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.append(diagPart)
        pageIndicator.text = spannable
    }

    private fun updateDiagnostics() {
        if (!::diagnosticsContent.isInitialized) return
        if (!isActivityResumed && !diagnosticsVisible) return

        // Memória RAM
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val ramBody = getDiagBody("Memória RAM")
        if (mi.totalMem > 0) {
            val totalGb = mi.totalMem / (1024.0 * 1024.0 * 1024.0)
            val availGb = mi.availMem / (1024.0 * 1024.0 * 1024.0)
            val usedGb = totalGb - availGb
            ramBody?.text = String.format(
                "Total: %.2f GB\nUsado: %.2f GB\nDisponível: %.2f GB\nBaixa memória: %s",
                totalGb,
                usedGb,
                availGb,
                if (mi.lowMemory) "Sim" else "Não"
            )
        } else {
            ramBody?.text = "Indisponível\nLimitação: ActivityManager.MemoryInfo.totalMem não disponível nesta API/versão"
        }

        // Bateria
        val battBody = getDiagBody("Bateria")
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (batteryIntent != null) {
            val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            val health = batteryIntent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
            val temp = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            if (level >= 0 && scale > 0) {
                val pct = (level * 100.0 / scale).toInt()
                val statusStr = when (status) {
                    BatteryManager.BATTERY_STATUS_CHARGING -> "Carregando"
                    BatteryManager.BATTERY_STATUS_DISCHARGING -> "Descarregando"
                    BatteryManager.BATTERY_STATUS_FULL -> "Completa"
                    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Não carregando"
                    else -> "Indefinido"
                }
                val pluggedStr = when (plugged) {
                    BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                    BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Sem fio"
                    else -> "Desconectado"
                }
                val healthStr = when (health) {
                    BatteryManager.BATTERY_HEALTH_GOOD -> "Boa"
                    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Superaquecimento"
                    BatteryManager.BATTERY_HEALTH_DEAD -> "Ruim/Descartada"
                    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Sobretensão"
                    BatteryManager.BATTERY_HEALTH_COLD -> "Frio"
                    else -> "Indefinida"
                }
                val tempStr = if (temp >= 0) String.format("%.1f °C", temp / 10.0) else "Indisponível"
                battBody?.text = String.format(
                    "Nível: %d%%\nStatus: %s\nConexão: %s\nSaúde: %s\nTemperatura: %s",
                    pct,
                    statusStr,
                    pluggedStr,
                    healthStr,
                    tempStr
                )
            } else {
                battBody?.text = "Indisponível\nLimitação: Dados de bateria não fornecidos pelo sistema neste dispositivo"
            }
        } else {
            battBody?.text = "Indisponível\nLimitação: Não foi possível obter Intent.ACTION_BATTERY_CHANGED"
        }

        // Armazenamento
        val storBody = getDiagBody("Armazenamento")
        try {
            val dataDir = Environment.getDataDirectory()
            val stat = StatFs(dataDir.path)
            val blockSize = if (Build.VERSION.SDK_INT >= 18) stat.blockSizeLong else stat.blockSize.toLong()
            val totalBlocks = if (Build.VERSION.SDK_INT >= 18) stat.blockCountLong else stat.blockCount.toLong()
            val availBlocks = if (Build.VERSION.SDK_INT >= 18) stat.availableBlocksLong else stat.availableBlocks.toLong()
            val total = totalBlocks * blockSize
            val avail = availBlocks * blockSize
            val used = total - avail
            if (total > 0) {
                val totalGb = total / (1024.0 * 1024.0 * 1024.0)
                val usedGb = used / (1024.0 * 1024.0 * 1024.0)
                val availGb = avail / (1024.0 * 1024.0 * 1024.0)
                storBody?.text = String.format(
                    "Interno (dados)\nTotal: %.2f GB\nUsado: %.2f GB\nLivre: %.2f GB",
                    totalGb,
                    usedGb,
                    availGb
                )
            } else {
                storBody?.text = "Indisponível\nLimitação: StatFs não retornou valores válidos"
            }
        } catch (e: Exception) {
            storBody?.text = "Indisponível\nLimitação: Falha ao acessar informações de armazenamento via APIs disponíveis"
        }

        // Tempo de atividade
        val upBody = getDiagBody("Tempo de atividade")
        try {
            val uptimeMs = SystemClock.elapsedRealtime()
            val seconds = (uptimeMs / 1000) % 60
            val minutes = (uptimeMs / (1000 * 60)) % 60
            val hours = (uptimeMs / (1000 * 60 * 60)) % 24
            val days = (uptimeMs / (1000 * 60 * 60 * 24))
            upBody?.text = String.format("Ligado há: %d dias, %02d:%02d:%02d", days, hours, minutes, seconds)
        } catch (e: Exception) {
            upBody?.text = "Indisponível\nLimitação: SystemClock.elapsedRealtime() indisponível"
        }

        // Sistema avançado
        val sysBody = getDiagBody("Sistema avançado")
        try {
            val abi = if (Build.VERSION.SDK_INT >= 21) {
                Build.SUPPORTED_ABIS.joinToString(", ")
            } else {
                @Suppress("DEPRECATION")
                listOf(Build.CPU_ABI, Build.CPU_ABI2).filter { it.isNotEmpty() }.joinToString(", ")
            }
            sysBody?.text = String.format(
                "Fabricante: %s\nModelo: %s\nDispositivo: %s\nProduto: %s\nAndroid: %s (API %d)\nBuild: %s\nABI: %s",
                Build.MANUFACTURER,
                Build.MODEL,
                Build.DEVICE,
                Build.PRODUCT,
                Build.VERSION.RELEASE,
                Build.VERSION.SDK_INT,
                Build.DISPLAY,
                abi
            )
        } catch (e: Exception) {
            sysBody?.text = "Indisponível\nLimitação: Informações de Build indisponíveis"
        }

        // Limitações
        val limBody = getDiagBody("Limitações")
        limBody?.text = "• Somente dados reais obtidos via APIs Android disponíveis.\n• Nenhuma permissão especial foi adicionada.\n• Valores podem variar conforme ROM/dispositivo.\n• Alguns campos podem aparecer como 'Indisponível' se não suportados."

        if (currentPage == 1 && isActivityResumed) {
            diagnosticsHandler.removeCallbacks(diagnosticsUpdateRunnable)
            diagnosticsHandler.postDelayed(diagnosticsUpdateRunnable, 2000)
        }
    }
}
