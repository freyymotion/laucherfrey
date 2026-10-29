package com.simples.launcher

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

/**
 * Categoria visível de um aplicativo.
 *
 * [id] é o identificador estável; [name] é apenas o texto apresentado em português.
 * Categorias embutidas ([builtIn] = true) têm IDs e nomes fixos; as demais são personalizadas.
 */
data class AppCategory(
    val id: String,
    val name: String,
    val builtIn: Boolean
)

/** Escolha manual de categoria. A ausência de escolha é representada por `null`. */
sealed class ManualCategory {
    data class Assigned(val categoryId: String) : ManualCategory()
    object Uncategorized : ManualCategory()
}

/**
 * Definições, classificação automática e heurísticas locais.
 *
 * Regras de classificação (determinísticas, sem I/O e sem rede):
 * 1. Se o sistema informar uma [ApplicationInfo.category] reconhecida, ela vence.
 * 2. Caso contrário, procura palavras-chave no rótulo e no nome do pacote.
 * 3. Sem evidência, retorna `null` (sem categoria).
 *
 * A busca normaliza o texto (minúsculas, sem acentos, separadores viram espaço) e
 * compara palavras/frases inteiras. Quando mais de uma categoria casa, vence a primeira
 * na ordem de [KEYWORDS] (comunicação, jogos, mídia, produtividade, ferramentas).
 * A lista é curta e editável; a classificação é sempre corrigível pelo usuário.
 */
object AppCategories {

    const val COMMUNICATION = "communication"
    const val GAMES = "games"
    const val MEDIA = "media"
    const val TOOLS = "tools"
    const val PRODUCTIVITY = "productivity"

    val builtIns: List<AppCategory> = listOf(
        AppCategory(COMMUNICATION, "Comunicação", true),
        AppCategory(GAMES, "Jogos", true),
        AppCategory(MEDIA, "Mídia", true),
        AppCategory(TOOLS, "Ferramentas", true),
        AppCategory(PRODUCTIVITY, "Produtividade", true)
    )

    fun classify(label: String, packageName: String, platformCategory: Int? = null): String? {
        categoryForPlatform(platformCategory)?.let { return it }
        val text = normalize("$label $packageName")
        if (text.isEmpty()) return null
        for ((categoryId, keywords) in KEYWORDS) {
            if (keywords.any { text.containsWord(it) }) return categoryId
        }
        return null
    }

    /** Lê `ApplicationInfo.category` apenas em API 26+; em API 23–25 retorna `null`. */
    fun platformCategoryOf(applicationInfo: ApplicationInfo?): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return applicationInfo?.category
    }

    /**
     * Mapeamentos da categoria informada pelo Android (API 26+):
     * - SOCIAL -> communication
     * - GAME -> games
     * - AUDIO, VIDEO, IMAGE -> media
     * - PRODUCTIVITY -> productivity
     *
     * NEWS, MAPS, ACCESSIBILITY e UNDEFINED não têm mapeamento e seguem para as
     * palavras-chave; se nada casar, o app fica sem categoria.
     */
    private fun categoryForPlatform(value: Int?): String? = when (value) {
        ApplicationInfo.CATEGORY_SOCIAL -> COMMUNICATION
        ApplicationInfo.CATEGORY_GAME -> GAMES
        ApplicationInfo.CATEGORY_AUDIO,
        ApplicationInfo.CATEGORY_VIDEO,
        ApplicationInfo.CATEGORY_IMAGE -> MEDIA
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> PRODUCTIVITY
        else -> null
    }

    private val NON_WORD = Regex("[^a-z0-9]+")
    private val DIACRITICS = Regex("\\p{M}+")

    private fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
        val withoutMarks = DIACRITICS.replace(decomposed, "")
        return NON_WORD.replace(withoutMarks.lowercase(Locale.ROOT), " ").trim()
    }

    /** Chave de comparação de nomes, sem caixa, acentos ou diferenças de separadores. */
    internal fun nameKey(value: String): String = normalize(value)

    private fun String.containsWord(keyword: String): Boolean = " $this ".contains(" $keyword ")

    private val KEYWORDS: List<Pair<String, List<String>>> = listOf(
        COMMUNICATION to listOf(
            "whatsapp", "telegram", "signal", "messenger", "instagram", "facebook",
            "twitter", "tiktok", "snapchat", "discord", "slack", "teams", "zoom",
            "skype", "viber", "wechat", "kakao", "messages", "mensagens", "sms",
            "phone", "telefone", "dialer", "contatos", "contacts"
        ),
        GAMES to listOf(
            "game", "games", "jogo", "jogos", "minecraft", "roblox", "pubg",
            "freefire", "free fire", "clash", "candy crush", "among us",
            "pokemon", "emulator", "emulador", "play games"
        ),
        MEDIA to listOf(
            "youtube", "spotify", "netflix", "prime video", "disney", "twitch",
            "soundcloud", "deezer", "vlc", "player", "music", "musica", "video",
            "filmes", "movies", "podcast", "podcasts", "gallery", "galeria",
            "photos", "fotos", "camera", "radio", "streaming"
        ),
        PRODUCTIVITY to listOf(
            "calendar", "calendario", "agenda", "notes", "notas", "notepad",
            "keep", "docs", "sheets", "slides", "drive", "gmail", "outlook",
            "proton mail", "word", "excel", "powerpoint", "notion", "trello",
            "todo", "tarefas", "tasks", "reminder", "lembrete"
        ),
        TOOLS to listOf(
            "calculator", "calculadora", "flashlight", "lanterna", "compass",
            "bussola", "clock", "relogio", "weather", "clima", "files",
            "arquivos", "file manager", "gerenciador", "settings",
            "configuracoes", "scanner", "qr code", "antivirus", "cleaner",
            "limpeza", "maps", "mapas"
        )
    )
}

/**
 * Persistência das categorias personalizadas e das escolhas manuais.
 *
 * Usa apenas `SharedPreferences("app_categories")`, separada de `"folders"` e `"fav"`.
 * A categoria inferida automaticamente nunca é gravada; apenas decisões manuais o são.
 */
class CategoryStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun builtInCategories(): List<AppCategory> = AppCategories.builtIns

    fun customCategories(): List<AppCategory> =
        parseCustomCategories(prefs.getString(KEY_CUSTOM, null))

    fun allCategories(): List<AppCategory> = AppCategories.builtIns + customCategories()

    fun category(id: String?): AppCategory? {
        if (id.isNullOrBlank()) return null
        return allCategories().firstOrNull { it.id == id }
    }

    /** Cria uma categoria personalizada, ou retorna `null` para nome vazio/duplicado/reservado. */
    fun createCustomCategory(name: String): AppCategory? {
        val clean = name.trim()
        if (clean.isEmpty()) return null
        val folded = AppCategories.nameKey(clean)
        if (folded in RESERVED_NAMES) return null
        if (allCategories().any { AppCategories.nameKey(it.name) == folded }) return null
        val category = AppCategory(UUID.randomUUID().toString(), clean, false)
        val custom = customCategories().toMutableList()
        custom.add(category)
        prefs.edit().putString(KEY_CUSTOM, serializeCustomCategories(custom)).apply()
        return category
    }

    /** Renomeia apenas categorias personalizadas; o ID é preservado. */
    fun renameCustomCategory(id: String, name: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty()) return false
        val folded = AppCategories.nameKey(clean)
        if (folded in RESERVED_NAMES) return false
        val custom = customCategories()
        val index = custom.indexOfFirst { it.id == id }
        if (index < 0) return false
        if (allCategories().any { it.id != id && AppCategories.nameKey(it.name) == folded }) {
            return false
        }
        val updated = custom.toMutableList()
        updated[index] = updated[index].copy(name = clean)
        prefs.edit().putString(KEY_CUSTOM, serializeCustomCategories(updated)).apply()
        return true
    }

    /**
     * Exclui uma categoria personalizada. Apps que a usavam passam a "sem categoria"
     * (escolha manual), em vez de voltar silenciosamente à classificação automática.
     */
    fun deleteCustomCategory(id: String): Boolean {
        val custom = customCategories()
        if (custom.none { it.id == id }) return false
        val remaining = custom.filterNot { it.id == id }
        val overrides = parseOverrides(prefs.getString(KEY_OVERRIDES, null))
        var overridesChanged = false
        for (pkg in overrides.keys.toList()) {
            val value = overrides[pkg]
            if (value is ManualCategory.Assigned && value.categoryId == id) {
                overrides[pkg] = ManualCategory.Uncategorized
                overridesChanged = true
            }
        }
        val editor = prefs.edit()
            .putString(KEY_CUSTOM, serializeCustomCategories(remaining))
        if (overridesChanged) {
            editor.putString(KEY_OVERRIDES, serializeOverrides(overrides))
        }
        editor.apply()
        return true
    }

    /** Define manualmente uma categoria existente para o pacote. */
    fun setManualCategory(pkg: String, categoryId: String): Boolean {
        val cleanPkg = pkg.trim()
        if (cleanPkg.isEmpty() || category(categoryId) == null) return false
        val overrides = parseOverrides(prefs.getString(KEY_OVERRIDES, null))
        overrides[cleanPkg] = ManualCategory.Assigned(categoryId)
        prefs.edit().putString(KEY_OVERRIDES, serializeOverrides(overrides)).apply()
        return true
    }

    /** Define manualmente o pacote como "sem categoria". */
    fun setManuallyUncategorized(pkg: String): Boolean {
        val cleanPkg = pkg.trim()
        if (cleanPkg.isEmpty()) return false
        val overrides = parseOverrides(prefs.getString(KEY_OVERRIDES, null))
        overrides[cleanPkg] = ManualCategory.Uncategorized
        prefs.edit().putString(KEY_OVERRIDES, serializeOverrides(overrides)).apply()
        return true
    }

    /** Remove a escolha manual; o pacote volta a usar a classificação automática. */
    fun clearManualCategory(pkg: String): Boolean {
        val cleanPkg = pkg.trim()
        if (cleanPkg.isEmpty()) return false
        val overrides = parseOverrides(prefs.getString(KEY_OVERRIDES, null))
        if (overrides.remove(cleanPkg) == null) return false
        prefs.edit().putString(KEY_OVERRIDES, serializeOverrides(overrides)).apply()
        return true
    }

    /** Escolha manual do pacote, ou `null` quando não há substituição persistida. */
    fun manualCategory(pkg: String): ManualCategory? {
        val cleanPkg = pkg.trim()
        if (cleanPkg.isEmpty()) return null
        return parseOverrides(prefs.getString(KEY_OVERRIDES, null))[cleanPkg]
    }

    /**
     * Categoria efetiva: manual vence; "sem categoria" manual vence a automática;
     * sem manual, usa [automaticCategoryId].
     *
     * Um override manual com ID desconhecido é ignorado sem apagar a preferência e
     * sem impedir a classificação automática válida; não há migração/limpeza aqui.
     */
    /**
     * Resolve várias categorias em lote, lendo as preferências uma única vez.
     * Mantém as mesmas regras de prioridade de effectiveCategoryId().
     */
    fun effectiveCategoryIds(
        automaticCategoryIds: Map<String, String?>
    ): Map<String, String?> {
        val validIds = allCategories().mapTo(HashSet<String>()) { it.id }
        val overrides = parseOverrides(prefs.getString(KEY_OVERRIDES, null))

        return automaticCategoryIds.mapValues { (pkg, automaticCategoryId) ->
            val automatic = automaticCategoryId?.takeIf { it in validIds }

            when (val manual = overrides[pkg.trim()]) {
                is ManualCategory.Assigned ->
                    manual.categoryId.takeIf { it in validIds } ?: automatic
                ManualCategory.Uncategorized -> null
                null -> automatic
            }
        }
    }

    fun effectiveCategoryId(pkg: String, automaticCategoryId: String?): String? {
        val automatic = automaticCategoryId?.takeIf { category(it) != null }
        return when (val manual = manualCategory(pkg)) {
            is ManualCategory.Assigned ->
                manual.categoryId.takeIf { category(it) != null } ?: automatic
            ManualCategory.Uncategorized -> null
            null -> automatic
        }
    }

    fun effectiveCategory(pkg: String, automaticCategoryId: String?): AppCategory? =
        category(effectiveCategoryId(pkg, automaticCategoryId))

    private fun serializeCustomCategories(categories: List<AppCategory>): String {
        val items = JSONArray()
        for (category in categories) {
            items.put(
                JSONObject()
                    .put("id", category.id)
                    .put("name", category.name)
            )
        }
        return items.toString()
    }

    private fun parseCustomCategories(raw: String?): List<AppCategory> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val items = JSONArray(raw)
            val out = mutableListOf<AppCategory>()
            val builtInIds = AppCategories.builtIns.mapTo(HashSet<String>()) { it.id }
            val builtInNames = AppCategories.builtIns
                .mapTo(HashSet<String>()) { AppCategories.nameKey(it.name) }
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                val name = item.optString("name").trim()
                if (id.isEmpty() || name.isEmpty()) continue
                if (id == UNCATEGORIZED || id in builtInIds) continue
                if (out.any { it.id == id }) continue
                val foldedName = AppCategories.nameKey(name)
                if (foldedName in RESERVED_NAMES) continue
                if (foldedName in builtInNames) continue
                if (out.any { AppCategories.nameKey(it.name) == foldedName }) continue
                out.add(AppCategory(id, name, builtIn = false))
            }
            out
        }.getOrDefault(emptyList())
    }

    private fun serializeOverrides(overrides: Map<String, ManualCategory>): String {
        val items = JSONObject()
        for ((pkg, value) in overrides) {
            val raw = when (value) {
                is ManualCategory.Assigned -> value.categoryId
                ManualCategory.Uncategorized -> UNCATEGORIZED
            }
            items.put(pkg, raw)
        }
        return items.toString()
    }

    private fun parseOverrides(raw: String?): MutableMap<String, ManualCategory> {
        if (raw.isNullOrBlank()) return mutableMapOf()
        return runCatching {
            val items = JSONObject(raw)
            val out = mutableMapOf<String, ManualCategory>()
            val keys = items.keys()
            while (keys.hasNext()) {
                val rawKey = keys.next()
                val pkg = rawKey.trim()
                if (pkg.isEmpty()) continue
                val value = items.optString(rawKey, "").trim()
                when {
                    value == UNCATEGORIZED -> out[pkg] = ManualCategory.Uncategorized
                    value.isNotEmpty() -> out[pkg] = ManualCategory.Assigned(value)
                }
            }
            out
        }.getOrDefault(mutableMapOf())
    }

    private companion object {
        const val PREFS_NAME = "app_categories"
        const val KEY_CUSTOM = "custom_categories"
        const val KEY_OVERRIDES = "manual_categories"
        const val UNCATEGORIZED = "__uncategorized__"

        private val RESERVED_NAMES = setOf(
            "tudo",
            "sem categoria",
            "automatica"
        )
    }
}
