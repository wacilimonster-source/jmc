package com.jmread.data

import android.content.Context
import android.content.SharedPreferences
import com.jmread.core.model.ComicCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 分类排序/隐藏设置（P1-06）。
 *
 * - order 为空 = 使用服务端顺序；
 * - 服务端新增分类追加到末尾并默认显示；
 * - 已保存但服务端已不存在的 id 忽略；
 * - 至少保留 1 个可见分类（UI 保存时校验，应用层再做一次防御）。
 */
object CategorySettings {

    private const val PREFS_NAME = "jm_category_settings"
    private const val KEY_SETTINGS = "settings"

    private val json = Json { ignoreUnknownKeys = true }
    private var prefs: SharedPreferences? = null

    @Serializable
    data class Settings(
        val order: List<String> = emptyList(),
        val hidden: Set<String> = emptySet(),
    )

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun get(): Settings {
        val p = prefs ?: return Settings()
        val raw = p.getString(KEY_SETTINGS, null) ?: return Settings()
        return runCatching { json.decodeFromString<Settings>(raw) }.getOrDefault(Settings())
    }

    fun save(settings: Settings) {
        prefs?.edit()?.putString(KEY_SETTINGS, json.encodeToString(settings))?.apply()
    }

    fun reset() {
        prefs?.edit()?.remove(KEY_SETTINGS)?.apply()
    }
}

/** 按设置排序但保留隐藏项（设置对话框用）。 */
internal fun orderedCategories(
    categories: List<ComicCategory>,
    settings: CategorySettings.Settings,
): List<ComicCategory> {
    val byId = categories.associateBy { it.id }
    val ordered = settings.order.mapNotNull { byId[it] }
    val rest = categories.filter { item -> settings.order.none { it == item.id } }
    return ordered + rest
}

/** 分类页实际展示：排序后过滤隐藏；全隐藏时防御性回退为全部。 */
internal fun applyCategorySettings(
    categories: List<ComicCategory>,
    settings: CategorySettings.Settings,
): List<ComicCategory> {
    val visible = orderedCategories(categories, settings).filter { it.id !in settings.hidden }
    return visible.ifEmpty { categories }
}
