# -*- coding: utf-8 -*-
"""18+ 首启确认门"""
import io

# 1. SourcePrefs: ageConfirmed
p = 'app/src/main/java/com/jmread/data/SourcePrefs.kt'
s = io.open(p, encoding='utf-8').read()
s = s.replace('''import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey''',
'''import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey''')
s = s.replace('''    /** /setting 下发的 jm3_version（签名头携带） */
    val JM_VERSION = stringPreferencesKey("jm_version")
}''',
'''    /** /setting 下发的 jm3_version（签名头携带） */
    val JM_VERSION = stringPreferencesKey("jm_version")
    /** 18+ 确认（首启一次性） */
    val AGE_CONFIRMED = booleanPreferencesKey("age_confirmed")
}''')
s = s.replace('''    @Volatile private var cachedVersion: String? = null
''', '''    @Volatile private var cachedVersion: String? = null
    @Volatile private var cachedAgeConfirmed: Boolean? = null
''')
s = s.replace('''                cachedVersion = prefs[Keys.JM_VERSION]?.takeIf { it.isNotEmpty() }
            }''',
'''                cachedVersion = prefs[Keys.JM_VERSION]?.takeIf { it.isNotEmpty() }
                cachedAgeConfirmed = prefs[Keys.AGE_CONFIRMED] ?: false
            }''')
s = s.replace('''    suspend fun setJmVersion(value: String) {
        cachedVersion = value
        appContext.dataStore.edit { it[Keys.JM_VERSION] = value }
    }
}''',
'''    suspend fun setJmVersion(value: String) {
        cachedVersion = value
        appContext.dataStore.edit { it[Keys.JM_VERSION] = value }
    }

    // ---------- 18+ 确认 ----------

    val ageConfirmed: Boolean
        get() = cachedAgeConfirmed
            ?: runBlocking {
                appContext.dataStore.data.first()[Keys.AGE_CONFIRMED] ?: false
            }.also { cachedAgeConfirmed = it }

    suspend fun setAgeConfirmed() {
        cachedAgeConfirmed = true
        appContext.dataStore.edit { it[Keys.AGE_CONFIRMED] = true }
    }
}''')
io.open(p, 'w', encoding='utf-8').write(s)

# 2. MainScreen: gate
p = 'app/src/main/java/com/jmread/ui/MainScreen.kt'
s = io.open(p, encoding='utf-8').read()
s = s.replace('''import com.jmread.data.ReaderPrefs
import com.jmread.ui.author.AuthorComicsScreen''',
'''import com.jmread.data.ReaderPrefs
import com.jmread.data.SourcePrefs
import com.jmread.ui.author.AuthorComicsScreen''')
s = s.replace('''@Composable
fun MainScreen() {
    val navController = rememberNavController()''',
'''@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // ── 18+ 年龄门：首次启动必须确认，之后不再打扰 ──
    var ageConfirmed by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(SourcePrefs.current().ageConfirmed)
    }
    if (!ageConfirmed) {
        AgeGate(
            onConfirm = {
                scope.launch { SourcePrefs.current().setAgeConfirmed() }
                ageConfirmed = true
            },
        )
        return
    }''')
s = s.replace('''import com.jmread.ui.settings.LogScreen
import com.jmread.ui.settings.SettingsScreen''',
'''import com.jmread.ui.settings.LogScreen
import com.jmread.ui.settings.SettingsScreen
import kotlinx.coroutines.launch''')

s = s.rstrip() + '''

/** 首启 18+ 确认页：内容性质声明，本地记住选择 */
@Composable
private fun AgeGate(onConfirm: () -> Unit) {
    androidx.compose.material3.Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            Text(
                text = "JM Reader",
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "本应用包含成人向漫画内容，仅限已满 18 周岁人士使用。\n\n" +
                    "· 所有内容来自第三方站点，版权归原作者及对应站点所有\n" +
                    "· 请遵守所在地区的法律法规",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
            androidx.compose.material3.Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
                Text("我已满 18 周岁，进入应用")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "未满 18 周岁请立即退出",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
'''
s = s.replace('import androidx.compose.foundation.layout.height\n', 'import androidx.compose.foundation.layout.fillMaxSize\nimport androidx.compose.foundation.layout.height\n')
io.open(p, 'w', encoding='utf-8').write(s)
print("done")
