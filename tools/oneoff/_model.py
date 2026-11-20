# -*- coding: utf-8 -*-
"""Comic.kt：ComicSummary 加收藏更新字段；RankTab 加总排行；删旧 DailyCheckIn"""
import io

p = 'app/src/main/java/com/jmread/core/model/Comic.kt'
s = io.open(p, encoding='utf-8').read()

# 1. ComicSummary: latest ep fields
old = '''    /** 大分类名（category.title，列表卡片角标用） */
    val categoryName: String = "",
) {
    /** 作品标识：列表点击、路由参数、本地记账都用它（单源 == id） */
    val ref: String get() = id
}'''
new = '''    /** 大分类名（category.title，列表卡片角标用） */
    val categoryName: String = "",
    /** 云端收藏项专属：最近更新章节的展示文本（/favorite.latest_ep，其他端点恒空） */
    val latestEpText: String = "",
    /** 云端收藏项专属：最近更新章节的 photo id（/favorite.latest_ep_aid） */
    val latestEpAid: String = "",
) {
    /** 作品标识：列表点击、路由参数、本地记账都用它（单源 == id） */
    val ref: String get() = id
}'''
assert old in s, "summary"
s = s.replace(old, new)

# 2. RankTab: add MV 总排行
old = '''enum class RankTab(
    val value: String,
    val label: String,
    val hint: String,
    /** 对应的服务端 `o=` 参数；null = 本档位由月榜数据本地重排得到，没有服务端参数 */
    val order: String?,
) {
    H24("H24", "新晋热榜", "热门里最近更新的", null),
    D7("D7", "周榜", "本周最多观看", "mv_w"),
    D30("D30", "月榜", "本月最多观看", "mv_m"),
    ;'''
new = '''enum class RankTab(
    val value: String,
    val label: String,
    val hint: String,
    /** 对应的服务端 `o=` 参数；null = 本档位由月榜数据本地重排得到，没有服务端参数 */
    val order: String?,
) {
    H24("H24", "新晋热榜", "热门里最近更新的", null),
    MV("MV", "总排行", "全部时间最多观看", "mv"),
    D30("D30", "月榜", "本月最多观看", "mv_m"),
    D7("D7", "周榜", "本周最多观看", "mv_w"),
    ;'''
assert old in s, "ranktab"
s = s.replace(old, new)

# 3. remove old DailyCheckIn model
old = '''/** 每日签到结果 */
data class DailyCheckIn(
    val checkedIn: Boolean = false,
    val consecutiveDays: Int = 0,
    val message: String = "",
)

'''
assert old in s, "daily model"
s = s.replace(old, '')

io.open(p, 'w', encoding='utf-8').write(s)

# 4. Repository: drop DailyCheckIn import
p2 = 'app/src/main/java/com/jmread/core/JmRepository.kt'
s2 = io.open(p2, encoding='utf-8').read()
s2 = s2.replace('import com.jmread.core.model.DailyCheckIn\n', '')
io.open(p2, 'w', encoding='utf-8').write(s2)
print('done')
