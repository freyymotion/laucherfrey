package com.simples.launcher

import android.content.Context

enum class ThemeId(val id: String, val label: String) {
    DARK("dark", "Escuro"),
    LIGHT("light", "Claro"),
    GLASS("glass", "Glass"),
    DANIE("danie", "DANIE_limpo");

    companion object {
        fun from(value: String?): ThemeId = ThemeId.values().firstOrNull { it.id == value } ?: DARK
    }
}

data class Palette(
    val bgTop: Int,
    val bgBottom: Int,
    val surface: Int,
    val surfaceStrong: Int,
    val text: Int,
    val textDim: Int,
    val border: Int,
    val accent: Int,
    val radiusDp: Int,
    val light: Boolean
)

object Themes {
    private val dark = Palette(
        bgTop = 0xFF0A0A0C.toInt(),
        bgBottom = 0xFF121317.toInt(),
        surface = 0xE617181C.toInt(),
        surfaceStrong = 0xFF1D1E23.toInt(),
        text = 0xFFF1F0EC.toInt(),
        textDim = 0xFF9A9A9F.toInt(),
        border = 0xFF2C2D32.toInt(),
        accent = 0xFFCFC8BC.toInt(),
        radiusDp = 16,
        light = false
    )

    private val light = Palette(
        bgTop = 0xFFF6F5F1.toInt(),
        bgBottom = 0xFFE9E7E0.toInt(),
        surface = 0xFFFFFFFF.toInt(),
        surfaceStrong = 0xFFFBFAF7.toInt(),
        text = 0xFF16171B.toInt(),
        textDim = 0xFF6C6D73.toInt(),
        border = 0xFFD8D5CC.toInt(),
        accent = 0xFF3B3C42.toInt(),
        radiusDp = 16,
        light = true
    )

    private val glass = Palette(
        bgTop = 0xFF090B0F.toInt(),
        bgBottom = 0xFF171C24.toInt(),
        surface = 0x26FFFFFF.toInt(),
        surfaceStrong = 0x40FFFFFF.toInt(),
        text = 0xFFFFFFFF.toInt(),
        textDim = 0xFFB9BEC7.toInt(),
        border = 0x4DFFFFFF.toInt(),
        accent = 0xFFE2E6ED.toInt(),
        radiusDp = 22,
        light = false
    )

    private val danie = Palette(
        bgTop = 0xFF09110C.toInt(),
        bgBottom = 0xFF12201A.toInt(),
        surface = 0x2A9BD3B0.toInt(),
        surfaceStrong = 0x3D8CC3A3.toInt(),
        text = 0xFFEAF3EC.toInt(),
        textDim = 0xFF9CB3A4.toInt(),
        border = 0x4D9BD3B0.toInt(),
        accent = 0xFF8CC3A3.toInt(),
        radiusDp = 20,
        light = false
    )

    fun of(id: ThemeId): Palette = when (id) {
        ThemeId.DARK -> dark
        ThemeId.LIGHT -> light
        ThemeId.GLASS -> glass
        ThemeId.DANIE -> danie
    }
}

object FreyPrefs {
    private const val NAME = "frey"
    private const val KEY_THEME = "theme"
    private const val KEY_WALLPAPER = "wallpaper"

    fun theme(context: Context): ThemeId =
        ThemeId.from(context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(KEY_THEME, null))

    fun setTheme(context: Context, id: ThemeId) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putString(KEY_THEME, id.id).apply()
    }

    fun wallpaper(context: Context): String? =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(KEY_WALLPAPER, null)

    fun setWallpaper(context: Context, path: String?) {
        val edit = context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
        if (path.isNullOrEmpty()) edit.remove(KEY_WALLPAPER) else edit.putString(KEY_WALLPAPER, path)
        edit.apply()
    }
}
