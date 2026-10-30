package com.simples.launcher

import android.app.Activity
import android.content.Intent
import android.net.Uri
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
import android.widget.Toast

class SettingsPanel(
    private val activity: Activity,
    private val palette: Palette,
    private val current: ThemeId,
    private val versionLabel: String,
    private val devMode: Boolean,
    private val diagnostics: String,
    private val onDevModeUnlocked: () -> Unit,
    private val onThemeSelected: (ThemeId) -> Unit,
    private val onRequestDefault: () -> Unit,
    private val onPickWallpaper: () -> Unit,
    private val onPickGif: () -> Unit,
    private val onClearWallpaper: () -> Unit,
    private val onClose: () -> Unit
) {

    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val serif: Typeface = Typeface.create("serif", Typeface.NORMAL)
    private var versionTaps = 0

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

    private fun tip(title: String, body: String): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(12), 0, 0)
        addView(label(title, 14f, palette.text))
        addView(label(body, 12f, palette.textDim).apply {
            setPadding(0, dp(3), 0, 0)
            setLineSpacing(0f, 1.15f)
        })
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
        card.addView(label(versionLabel, 12f, palette.textDim).apply {
            setPadding(0, dp(4), 0, 0)
            setOnClickListener {
                if (devMode) return@setOnClickListener
                versionTaps++
                when {
                    versionTaps >= 7 -> {
                        versionTaps = 0
                        onDevModeUnlocked()
                    }
                    versionTaps >= 4 -> Toast.makeText(
                        activity, "Faltam ${7 - versionTaps} toques", Toast.LENGTH_SHORT
                    ).show()
                }
            }
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

        card.addView(label("Papel de parede", 12f, palette.textDim).apply {
            setPadding(0, dp(18), 0, dp(6))
        })

        card.addView(label("Adicionar imagem", 15f, palette.text).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = panel(palette.surface, palette.accent, palette.radiusDp - 6)
            setOnClickListener { onPickWallpaper() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        card.addView(label("Adicionar GIF", 15f, palette.text).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = panel(palette.surface, palette.accent, palette.radiusDp - 6)
            setOnClickListener { onPickGif() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)).apply {
            setPadding(0, dp(8), 0, 0)
        }

        card.addView(label("Usar fundo padrão", 13f, palette.textDim).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
            setOnClickListener { onClearWallpaper() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        card.addView(label("Desenvolvedor", 12f, palette.textDim).apply {
            setPadding(0, dp(20), 0, dp(6))
        })

        card.addView(label("FREYY", 18f, palette.text, serif = true).apply {
            setPadding(0, dp(2), 0, dp(4))
        })

        card.addView(label(
            "Launcher Android independente, criado por FREYY.",
            12f, palette.textDim
        ).apply {
            setLineSpacing(0f, 1.15f)
        })

        card.addView(label(
            "Problemas? Envie uma mensagem no Instagram.",
            12f, palette.textDim
        ).apply {
            setPadding(0, dp(8), 0, 0)
        })

        card.addView(label("Instagram · @jaofrey_", 12f, palette.accent).apply {
            setPadding(0, dp(6), 0, 0)
            setOnClickListener {
                runCatching {
                    activity.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://instagram.com/jaofrey_"))
                    )
                }.onFailure {
                    Toast.makeText(
                        activity, "Não foi possível abrir o Instagram", Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })

        card.addView(label("Dicas de bateria", 12f, palette.textDim).apply {
            setPadding(0, dp(20), 0, dp(6))
        })

        card.addView(label(
            "Não há economia garantida: o consumo depende do aparelho, da tela e do uso. " +
                "Estas dicas são apenas orientações; o launcher não altera configurações do sistema.",
            11f, palette.textDim
        ).apply {
            setLineSpacing(0f, 1.15f)
        })

        listOf(
            "Brilho da tela" to
                "Reduzir o brilho e usar brilho automático costuma diminuir o consumo, " +
                "especialmente com a tela ligada por muito tempo. O ganho varia conforme o aparelho e o ambiente.",
            "Fundos escuros" to
                "Em telas OLED/AMOLED, pixels pretos podem ficar desligados, então fundos escuros podem ajudar. " +
                "Em telas LCD, a iluminação de fundo continua acesa e a diferença tende a ser pequena ou nenhuma.",
            "Aplicativos em segundo plano" to
                "Revise apps com permissão para rodar em segundo plano, usar localização ou sincronizar dados. " +
                "Restrinja apenas os que não precisam funcionar o tempo todo; encerrar tudo à força pode aumentar " +
                "o consumo quando os apps forem reabertos.",
            "Configurações do sistema" to
                "Modo economia de bateria, bateria adaptativa e redução de atualizações em segundo plano podem ajudar. " +
                "Os nomes e caminhos variam conforme o fabricante e a versão do Android."
        ).forEach { (title, body) ->
            card.addView(tip(title, body), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        if (devMode) {
            card.addView(label("Diagnósticos", 12f, palette.textDim).apply {
                setPadding(0, dp(20), 0, dp(6))
            })
            card.addView(label(diagnostics, 11f, palette.textDim).apply {
                typeface = Typeface.MONOSPACE
                setLineSpacing(0f, 1.2f)
            })
        }

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
