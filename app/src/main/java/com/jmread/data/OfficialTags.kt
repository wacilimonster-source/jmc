package com.jmread.data

/**
 * 官方标签墙（固定词表）。
 *
 * 线上 `/categories.blocks` 的 4 组标签属于站点稳定的内容位，不会随作品存量变化。
 * 2026-11-01 抓取后固化在这里：分类页和搜索页直接读本地词表，
 * 不再每次进入页面都打一次 `/categories`（弱网下表现为标签区长时间空白）。
 *
 * 站点调整标签墙时改这里即可；核对方式见 tools/jm-api-check.mjs 的 /categories 断言。
 */
object OfficialTags {

    data class Group(val title: String, val tags: List<String>)

    val groups: List<Group> = listOf(
        Group(
            title = "主题A漫",
            tags = listOf(
                "剧情向", "校园", "纯爱", "人妻", "师生", "近亲", "百合",
                "YAOI", "性转", "NTR", "伪娘", "痴女", "全彩", "女性向",
            ),
        ),
        Group(
            title = "角色 / 扮演",
            tags = listOf(
                "萝莉", "御姐", "熟女", "正太", "巨乳", "贫乳", "女王",
                "教师", "女僕", "护士", "泳装", "眼镜", "连裤袜", "其他制服", "兔女郎",
            ),
        ),
        Group(
            title = "特殊PLAY",
            tags = listOf(
                "群交", "足交", "SM", "肛交", "阿黑颜", "药物", "扶他",
                "调教", "野外露出", "催眠", "自慰", "触手", "兽交",
            ),
        ),
        Group(
            title = "其他",
            tags = listOf("CG集", "重口", "猎奇", "非H", "血腥暴力"),
        ),
    )

    /** 全部标签（去重保序）：搜索页标签入口用 */
    val all: List<String> = groups.flatMap { it.tags }.distinct()
}
