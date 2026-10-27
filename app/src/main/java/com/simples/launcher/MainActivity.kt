package com.simples.launcher

import android.app.Activity
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {

    private lateinit var home: LinearLayout
    private lateinit var favBox: LinearLayout
    private lateinit var drawer: ListView
    private var apps: List<ResolveInfo> = emptyList()
    private val prefs by lazy { getSharedPreferences("fav", MODE_PRIVATE) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        // ---------- TELA INICIAL ----------
        home = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        val clock = TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = "HH:mm"
            textSize = 84f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            includeFontPadding = false
            gravity = Gravity.CENTER
        }
        val date = TextClock(this).apply {
            format24Hour = "EEEE, d 'de' MMMM"
            format12Hour = "EEEE, d 'de' MMMM"
            textSize = 16f
            setTextColor(Color.parseColor("#8A8A8A"))
            gravity = Gravity.CENTER
        }
        favBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val allApps = TextView(this).apply {
            text = "Todos os apps"
            textSize = 14f
            setTextColor(Color.parseColor("#8A8A8A"))
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(20), dp(16), dp(8))
            setOnClickListener { showDrawer() }
        }

        fun spacer() = Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        }
        home.addView(spacer())
        home.addView(clock)
        home.addView(date)
        home.addView(spacer())
        home.addView(favBox)
        home.addView(allApps)

        // ---------- GAVETA DE APPS ----------
        drawer = ListView(this).apply {
            setBackgroundColor(Color.BLACK)
            divider = null
            setPadding(dp(16), dp(32), dp(16), dp(16))
            clipToPadding = false
            visibility = View.GONE
            setOnItemClickListener { _, _, pos, _ -> open(apps[pos].activityInfo.packageName) }
            setOnItemLongClickListener { _, _, pos, _ ->
                toggleFav(apps[pos].activityInfo.packageName)
                true
            }
        }

        root.addView(home, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(drawer, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        loadApps()
        showFavs()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        showHome()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawer.visibility == View.VISIBLE) showHome()
        // na tela inicial, não faz nada (é um launcher)
    }

    private fun showDrawer() {
        home.visibility = View.GONE
        drawer.visibility = View.VISIBLE
        drawer.setSelection(0)
    }

    private fun showHome() {
        drawer.visibility = View.GONE
        home.visibility = View.VISIBLE
    }

    private fun loadApps() {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        apps = packageManager.queryIntentActivities(i, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        val labels = apps.map { it.loadLabel(packageManager).toString() }
        drawer.adapter = object : ArrayAdapter<String>(
            this, android.R.layout.simple_list_item_1, labels
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val t = super.getView(position, convertView, parent) as TextView
                t.setTextColor(Color.WHITE)
                t.textSize = 18f
                return t
            }
        }
    }

    private fun favs(): MutableList<String> =
        (prefs.getString("l", "") ?: "").split("|").filter { it.isNotEmpty() }.toMutableList()

    private fun saveFavs(l: List<String>) {
        prefs.edit().putString("l", l.joinToString("|")).apply()
    }

    private fun toggleFav(pkg: String) {
        val l = favs()
        when {
            l.contains(pkg) -> { l.remove(pkg); toast("Removido da tela inicial") }
            l.size >= 5 -> { toast("Máximo de 5 atalhos"); return }
            else -> { l.add(pkg); toast("Adicionado à tela inicial") }
        }
        saveFavs(l)
        showFavs()
    }

    private fun showFavs() {
        favBox.removeAllViews()
        var count = 0
        for (pkg in favs()) {
            val label = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) { continue }
            count++
            favBox.addView(TextView(this).apply {
                text = label
                textSize = 20f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setOnClickListener { open(pkg) }
                setOnLongClickListener { toggleFav(pkg); true }
            })
        }
        if (count == 0) {
            favBox.addView(TextView(this).apply {
                text = "Segure um app na lista\npara fixar aqui"
                textSize = 14f
                setTextColor(Color.parseColor("#555555"))
                gravity = Gravity.CENTER
            })
        }
    }

    private fun open(pkg: String) {
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
