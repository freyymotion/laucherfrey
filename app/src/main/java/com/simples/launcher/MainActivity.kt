package com.simples.launcher

import android.app.Activity
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val REQ_ROLE = 501
    }

    private var palette: Palette = Themes.of(ThemeId.DARK)
    private lateinit var repo: AppRepository
    private var apps: List<LauncherApp> = emptyList()

    private lateinit var home: LinearLayout
    private lateinit var favBox: LinearLayout
    private lateinit var drawer: LinearLayout
    private lateinit var grid: GridView
    private lateinit var settingsHost: FrameLayout
    private var gridAdapter: AppGridAdapter? = null

    private val prefs by lazy { getSharedPreferences("fav", MODE_PRIVATE) }
    private val serif: Typeface by lazy { Typeface.create("serif", Typeface.NORMAL) }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            repo.clearIcons()
            refreshApps()
            showFavs()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        palette = Themes.of(FreyPrefs.theme(this))
        repo = AppRepository(this)
        configureWindow()
        buildUi()
        refreshApps()
        showFavs()
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

    private fun background() = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(palette.bgTop, palette.bgBottom)
    )

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
        val root = FrameLayout(this).apply { background = background() }

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
            background = background()
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
                if (!repo.launch(pkg)) toast("Não foi possível abrir este app")
            }
            setOnItemLongClickListener { _, _, position, _ ->
                apps.getOrNull(position)?.let { toggleFav(it.pkg) }
                true
            }
        }
        drawer.addView(grid, LinearLayout.LayoutParams(
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
            }
            val app = apps.getOrNull(position) ?: return cell
            cell.background = panel(palette.surface, palette.border, maxOf(4, palette.radiusDp - 8))
            (cell.getChildAt(0) as ImageView).setImageDrawable(
                repo.icon(app.pkg) ?: packageManager.defaultActivityIcon
            )
            (cell.getChildAt(1) as TextView).apply {
                text = app.label
                setTextColor(palette.text)
            }
            return cell
        }
    }

    private fun refreshApps() {
        apps = repo.load()
        gridAdapter?.notifyDataSetChanged()
    }

    private fun showDrawer() {
        home.visibility = View.GONE
        drawer.visibility = View.VISIBLE
        grid.setSelection(0)
    }

    private fun showHome() {
        drawer.visibility = View.GONE
        home.visibility = View.VISIBLE
    }

    private fun openSettings() {
        SettingsPanel(
            activity = this,
            palette = palette,
            current = FreyPrefs.theme(this),
            onThemeSelected = { theme ->
                FreyPrefs.setTheme(this, theme)
                closeSettings()
                recreate()
            },
            onRequestDefault = { requestDefaultLauncher() },
            onClose = { closeSettings() }
        ).also { panel ->
            settingsHost.removeAllViews()
            settingsHost.addView(panel.view, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            settingsHost.visibility = View.VISIBLE
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
                    toast("SimpleLauncher já é o launcher padrão")
                    return
                }
                toast("Confirme o SimpleLauncher na tela do sistema")
                val requested = runCatching {
                    startActivityForResult(roleManager.createIntentForRoleRequest(RoleManager.ROLE_HOME), REQ_ROLE)
                }.isSuccess
                if (requested) return
            }
        }
        openHomeSettings()
    }

    private fun openHomeSettings() {
        toast("Escolha o SimpleLauncher na tela do sistema")
        val opened = runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }.isSuccess
        if (!opened) {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_ROLE) return
        if (resultCode == RESULT_OK) {
            toast("SimpleLauncher definido como padrão")
            return
        }
        val held = if (Build.VERSION.SDK_INT >= 29) {
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true
        } else {
            false
        }
        toast(if (held) "SimpleLauncher é o launcher padrão" else "Seleção não concluída. Use os Ajustes do sistema.")
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
                setImageDrawable(repo.icon(pkg) ?: packageManager.defaultActivityIcon)
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
