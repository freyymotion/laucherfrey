package com.simples.launcher

// Base do futuro atualizador: sem rede até existirem releases publicadas.
object Updates {
    const val REPO = "freyymotion/laucherfrey"
    const val RELEASES_API = "https://api.github.com/repos/$REPO/releases/latest"

    private fun parts(version: String?): List<Int>? =
        version?.trim()
            ?.removePrefix("v")
            ?.removePrefix("V")
            ?.substringBefore('-')
            ?.split('.')
            ?.mapNotNull { it.toIntOrNull() }
            ?.takeIf { it.isNotEmpty() }

    fun isNewer(latestTag: String?, current: String): Boolean {
        val latest = parts(latestTag) ?: return false
        val installed = parts(current) ?: return false
        for (i in 0 until maxOf(latest.size, installed.size)) {
            val a = latest.getOrElse(i) { 0 }
            val b = installed.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    init {
        check(isNewer("v1.2.0", "1.1.9"))
        check(!isNewer("v1.0.0", "1.0.0"))
        check(!isNewer(null, "1.0"))
    }
}
