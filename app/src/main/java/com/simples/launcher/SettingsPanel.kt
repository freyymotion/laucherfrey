package com.simples.launcher

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

class SettingsPanel(
    private val activity: Activity,
    private val palette: Palette,
    private val current: ThemeId,
    private val onThemeSelected: (ThemeId) -> Unit,
    private val onRequestDefault: () -> Unit,
    private val onClose: () -> Unit
) {

    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val serif: Typeface = Typeface.create("serif", Typeface.NORMAL)

    val view: View = build()

    private fun panel(fill: Int, stroke: Int, radius: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radius).toFloat()
        setColor(fill)
        setStroke(maxOf(1, dp(1)), stroke)
    }

    private fun label(value: String, size: Float, color: Int, serif: Boolean = false) =
        TextView(activity).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (serif) typeface = this@SettingsPanel.serif
            letterSpacing = if (serif) 0.3f else 0f
        }

    private fun build(): View {
        val root = FrameLayout(activity).apply {
            setBackgroundColor(0x8C000000.toInt())
            setOnClickListener { onClose() }
        }

        val scroll = ScrollView(activity).apply { isFillViewport = false }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = panel(palette.surfaceStrong, palette.border, palette.radiusDp)
            setPadding(dp(22), dp(20), dp(22), dp(16))
            setOnClickListener { }
        }

        card.addView(label("FREY", 12f, palette.textDim, serif = true))
        card.addView(label("Ajustes", 24f, palette.text, serif = true).apply {
            setPadding(0, dp(6), 0, 0)
        })

        val divider = View(activity).apply { setBackgroundColor(palette.border) }

        card.addView(label("Tema", 12f, palette.textDim).apply {
            setPadding(0, dp(18), 0, dp(6))
        })

        val group = RadioGroup(activity).apply { orientation = LinearLayout.VERTICAL }
        ThemeId.values().forEach { theme ->
            val radio = RadioButton(activity).apply {
                id = View.generateViewId()
                text = theme.label
                textSize = 15f
                setTextColor(palette.text)
                buttonTintList = ColorStateList.valueOf(palette.accent)
                isChecked = theme == current
                setPadding(0, dp(6), 0, dp(6))
            }
            radio.setOnCheckedChangeListener { _, checked ->
                if (checked && theme != current) onThemeSelected(theme)
            }
            group.addView(radio)
        }
        card.addView(group)

        card.addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, dp(1))).apply {
            topMargin = dp(18)
            bottomMargin = dp(16)
        })

        card.addView(label("Definir como launcher padrão", 15f, palette.text).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = panel(palette.surface, palette.accent, palette.radiusDp - 6)
            setOnClickListener { onRequestDefault() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        card.addView(label("O Android pode pedir que você confirme a escolha nas configurações do sistema.", 11f, palette.textDim).apply {
            setPadding(0, dp(8), 0, 0)
        })

        card.addView(label("Fechar", 13f, palette.textDim).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
            setOnClickListener { onClose() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        scroll.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            leftMargin = dp(20)
            rightMargin = dp(20)
        })

        return root
    }
}
