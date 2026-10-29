package com.simples.launcher

import android.app.Activity
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
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
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
    }

    private var palette: Palette = Themes.of(ThemeId.DARK)
    private lateinit var repo: AppRepository
    private var apps: List<LauncherApp> = emptyList()
    private var fullApps: List<LauncherApp> = emptyList()
    private var folders: MutableList<Folder> = mutableListOf()
    private var activeFolderId: String? = null

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
    private lateinit var folderRow: LinearLayout
    private var gridAdapter: AppGridAdapter? = null

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
        folders = loadFolders()
        repo.setIconListener(iconListener)
        repo.setAppsListener(appsListener)
        configureWindow()
        buildUi()
        loadSavedWallpaper()
        refreshApps()
        showFavs()
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
    }

    override fun onStop() {
        runCatching { unregisterReceiver(packageReceiver) }
        super.onStop()
    }

    override fun onDestroy() {
        wallpaperExecutor.shutdown()
        searchHandler.removeCallbacks(searchRunnable)
        repo.clearIconListener()
        repo.clearAppsListener()
        repo.close()
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

    private fun previewWallpaper(uri: Uri) {
        val version = ++previewVersion
        wallpaperExecutor.execute {
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
                if (bitmap == null) toast("Não foi possível carregar a imagem")
                else showWallpaperPreview(bitmap)
            }
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

    private fun showWallpaperPreview(bitmap: Bitmap) {
        var applied = false
        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(bitmap)
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
                applyWallpaper(bitmap)
            }
            .setNegativeButton("Cancelar", null)
            .create()
        dialog.setOnDismissListener {
            if (!applied) {
                preview.setImageDrawable(null)
                if (!bitmap.isRecycled) bitmap.recycle()
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

    private fun applyWallpaper(bitmap: Bitmap) {
        val version = ++wallpaperVersion

        wallpaperExecutor.execute {
            val target = File(filesDir, WALLPAPER_FILE)
            val temp = File(filesDir, "$WALLPAPER_FILE.tmp")
            val backup = File(filesDir, "$WALLPAPER_FILE.bak")
            val backupTemp = File(filesDir, "$WALLPAPER_FILE.bak.tmp")

            var saved = runCatching {
                FileOutputStream(temp).use { out ->
                    val compressed = bitmap.compress(
                        Bitmap.CompressFormat.JPEG, 92, out
                    )
                    out.flush()
                    compressed && temp.length() > 0L
                }
            }.getOrDefault(false)

            if (saved && isValidWallpaper(target)) {
                val copied = runCatching {
                    target.inputStream().use { input ->
                        backupTemp.outputStream().use { output ->
                            input.copyTo(output)
                            output.flush()
                        }
                    }
                    isValidWallpaper(backupTemp)
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
                // Não substitui um backup válido por uma imagem inválida.
                if (!target.delete()) saved = false
            }

            if (saved && !temp.renameTo(target)) {
                saved = false
            }

            // Se a instalação falhar, tenta recuperar a imagem anterior.
            if (!saved && isValidWallpaper(backup) &&
                !isValidWallpaper(target)
            ) {
                runCatching {
                    backup.copyTo(target, overwrite = true)
                }
            }

            if (temp.exists()) temp.delete()
            if (backupTemp.exists()) backupTemp.delete()

            runOnUiThread {
                if (isFinishing || isDestroyed || version != wallpaperVersion) {
                    if (!bitmap.isRecycled) bitmap.recycle()
                    return@runOnUiThread
                }

                if (!saved) {
                    if (!bitmap.isRecycled) bitmap.recycle()
                    toast("Não foi possível salvar a imagem")
                    return@runOnUiThread
                }

                FreyPrefs.setWallpaper(this, target.absolutePath)
                showWallpaper(bitmap)
                toast("Papel de parede aplicado")
            }
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

        wallpaperExecutor.execute {
            var source = file

            if (!isValidWallpaper(source)) {
                val backup = File(filesDir, "$WALLPAPER_FILE.bak")
                val restoreTemp = File(filesDir, "$WALLPAPER_FILE.restore.tmp")

                if (isValidWallpaper(backup)) {
                    val restored = runCatching {
                        if (restoreTemp.exists()) restoreTemp.delete()

                        backup.copyTo(restoreTemp, overwrite = true)

                        if (!isValidWallpaper(restoreTemp)) {
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

                    if (restored && isValidWallpaper(file)) {
                        source = file
                    } else {
                        source = backup
                    }
                }
            }

            val bitmap = if (isValidWallpaper(source)) {
                runCatching {
                    decodeBounded { source.inputStream() }
                }.getOrNull()
            } else {
                null
            }

            runOnUiThread {
                if (isFinishing || isDestroyed || version != wallpaperVersion) {
                    bitmap?.recycle()
                    return@runOnUiThread
                }

                if (bitmap == null) {
                    FreyPrefs.setWallpaper(this, null)
                    showDefaultBackground()
                } else {
                    FreyPrefs.setWallpaper(this, source.absolutePath)
                    showWallpaper(bitmap)
                }
            }
        }
    }

    private fun clearCustomWallpaper() {
        ++wallpaperVersion
        ++previewVersion

        FreyPrefs.setWallpaper(this, null)
        showDefaultBackground()

        wallpaperExecutor.execute {
            val files = listOf(
                File(filesDir, WALLPAPER_FILE),
                File(filesDir, "$WALLPAPER_FILE.tmp"),
                File(filesDir, "$WALLPAPER_FILE.bak"),
                File(filesDir, "$WALLPAPER_FILE.bak.tmp"),
                File(filesDir, "$WALLPAPER_FILE.restore.tmp")
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

    private fun showWallpaper(bitmap: Bitmap) {
        if (!::wallpaperView.isInitialized) return
        wallpaperView.setImageBitmap(bitmap)
        wallpaperView.visibility = View.VISIBLE
        wallpaperScrim.visibility = View.VISIBLE
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
        home.addView(allApps, wrapWrap(top = dp(20), bottom = dp(4)))

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
        refreshFolderBar()

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

        root.addView(home, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(drawer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(settingsHost, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        setContentView(root)
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
        val folder = folders.firstOrNull { it.id == activeFolderId }
        if (folder == null) activeFolderId = null
        val base = if (folder == null) fullApps else {
            val wanted = folder.packages.toHashSet()
            fullApps.filter { it.pkg in wanted }
        }
        apps = if (query.isEmpty()) base else base.filter { it.label.lowercase().contains(query) }
        gridAdapter?.notifyDataSetChanged()
        if (::emptyView.isInitialized) {
            emptyView.text = when {
                query.isNotEmpty() -> "Nenhum app encontrado"
                folder != null -> "Pasta vazia"
                else -> "Nenhum aplicativo"
            }
            emptyView.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun refreshFolderBar() {
        if (!::folderRow.isInitialized) return
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

    private fun pruneFolders(installed: Set<String>) {
        var changed = false
        for (folder in folders) {
            if (folder.packages.removeAll { it !in installed }) changed = true
        }
        if (changed) saveFolders()
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
        val activeFolder = folders.firstOrNull { it.id == activeFolderId }
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
        var count = 0
        for (pkg in favs()) {
            val label = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) {
                continue
            }
            count++
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
        if (count == 0) {
            favBox.addView(text("Segure um app na lista\npara fixar aqui", 13f, palette.textDim).apply {
                gravity = Gravity.CENTER
            })
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
