package com.jmread.core.model

/**
 * 作者署名解析（对标 PiKA v1.10.0「作者署名拆分与部分作者名搜索」）。
 *
 * 痛点：`张三 / 李四（翻译：王五）` 这类复合署名被整串当作作者身份搜索，
 * 只能找到署名恰好相同的作品；真实作者、翻译各自的作品全部漏掉。
 *
 * 设计（与 PiKA 同语义）：
 *  - 把原始署名拆成多个「署名片段」，片段才是搜索/收藏的身份；
 *  - 括号内容带角色前缀（翻译：/汉化：/原作：…）→ 角色标签 + 名字；
 *    无角色前缀 → 当作同作品的其他署名（role=OTHER）；
 *  - 单一片段 → hasMultiple=false，详情页保持一次点击直达，不弹层；
 *  - 解析不确定时永远保留「完整署名」出口（作者页片段切换条含完整署名）。
 *
 * 与 PiKA 的差异（源能力差异）：PiKA 关键词搜索会混入标题/标签命中，
 * 需要客户端 author 字段包含校验；JM 的作者维度搜索（main_tag=2）服务端
 * 已按作者字段过滤，不再二次过滤，避免署名书写差异造成漏召回。
 */
enum class AuthorRole(val label: String) {
    UNKNOWN("作者"),
    TRANSLATION("翻译"),
    GROUP("汉化"),
    ORIGINAL("原作"),
    ART("作画"),
    STORY("脚本"),
    OTHER("其他"),
}

data class AuthorSegment(
    /** 实际用于搜索/收藏的文本 */
    val text: String,
    val role: AuthorRole,
)

data class ParsedAuthor(
    val origin: String,
    val segments: List<AuthorSegment>,
) {
    /** 详情页判断是否弹选择层：单一署名一次点击直达 */
    val hasMultiple: Boolean get() = segments.size > 1
}

object AuthorName {

    /** 片段数上限（防病态长署名撑爆 UI） */
    internal const val MAX_SEGMENTS = 6

    /** 角色前缀 → 角色（顺序敏感：长前缀优先，如「汉化组」先于「汉化」） */
    private val ROLE_PREFIXES = linkedMapOf(
        "翻译" to AuthorRole.TRANSLATION,
        "翻譯" to AuthorRole.TRANSLATION,
        "trans" to AuthorRole.TRANSLATION,
        "汉化组" to AuthorRole.GROUP,
        "漢化組" to AuthorRole.GROUP,
        "汉化" to AuthorRole.GROUP,
        "漢化" to AuthorRole.GROUP,
        "原作" to AuthorRole.ORIGINAL,
        "作画" to AuthorRole.ART,
        "作畫" to AuthorRole.ART,
        "画师" to AuthorRole.ART,
        "art" to AuthorRole.ART,
        "脚本" to AuthorRole.STORY,
        "劇本" to AuthorRole.STORY,
        "剧本" to AuthorRole.STORY,
    )

    /** 顶层分隔符（空格斜杠优先于裸斜杠，避免 URL/日期误切） */
    private val SEPARATORS = listOf(" / ", "／", "/", "、", "＆", "&", "×", "✕")

    /** 顶层切分：先按长分隔符，避免 " / " 被裸 "/" 切成空段 */
    internal fun splitTopLevel(origin: String): List<String> {
        var parts = listOf(origin)
        for (sep in SEPARATORS) {
            if (origin.length <= 1) break
            parts = parts.flatMap { p ->
                if (p.contains(sep)) {
                    p.split(sep).map { it.trim() }.filter { it.isNotEmpty() }
                } else {
                    listOf(p)
                }
            }
        }
        return parts
    }

    /** 识别「角色前缀：名字」形态（全角/半角冒号、=） */
    internal fun splitRolePrefix(text: String): Pair<AuthorRole, String>? {
        val idx = text.indexOfFirst { it == '：' || it == ':' || it == '=' }
        if (idx <= 0) return null
        val prefix = text.take(idx).trim()
        val name = text.substring(idx + 1).trim()
        if (name.isEmpty()) return null
        val role = ROLE_PREFIXES.entries.firstOrNull { prefix.equals(it.key, ignoreCase = true) }?.value
            ?: return null
        return role to name
    }

    /** 单个顶层片段 → 0..2 个署名片段（本体 + 括号内内容） */
    private fun parsePart(part: String): List<AuthorSegment> {
        val trimmed = part.trim().replace(Regex("[（(]\\s*[）)]"), "").trim()
        if (trimmed.isEmpty()) return emptyList()
        val out = mutableListOf<AuthorSegment>()

        val paren = Regex("[（(]([^）)]+)[）)]")
        val inner = paren.find(trimmed)?.groupValues?.get(1)?.trim()
        val body = if (inner != null) trimmed.replace(paren, "").trim() else trimmed

        // 本体：可能自带角色前缀（如「翻译：王五」直接作为一段）
        if (body.isNotEmpty()) {
            val bodyLen = body.length
            val rolePair = splitRolePrefix(body)
            if (rolePair != null && bodyLen > 1) {
                out += AuthorSegment(rolePair.second, rolePair.first)
            } else if (bodyLen <= 40) {
                out += AuthorSegment(body, AuthorRole.UNKNOWN)
            }
        }
        // 括号内：角色前缀 → 角色片段；否则视为同作品的其他署名
        if (!inner.isNullOrEmpty() && inner.length <= 40) {
            val rolePair = splitRolePrefix(inner)
            if (rolePair != null) {
                out += AuthorSegment(rolePair.second, rolePair.first)
            } else {
                out += AuthorSegment(inner, AuthorRole.OTHER)
            }
        }
        return out
    }

    fun parse(origin: String): ParsedAuthor {
        val trimmedOrigin = origin.trim()
        if (trimmedOrigin.isEmpty()) return ParsedAuthor(origin, emptyList())
        val segments = splitTopLevel(trimmedOrigin).flatMap { parsePart(it) }
            .distinctBy { it.text.lowercase() }
            .take(MAX_SEGMENTS)
        return ParsedAuthor(trimmedOrigin, segments)
    }

    /** 完整署名片段（作者页切换条与详情选择层的兜底出口） */
    fun fullSegment(origin: String): AuthorSegment = AuthorSegment(origin.trim(), AuthorRole.UNKNOWN)
}
