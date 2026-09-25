package com.jmread.ui

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.jmread.data.ReaderPrefs
import com.jmread.data.SourcePrefs
import com.jmread.ui.author.AuthorComicsScreen
import com.jmread.ui.category.CategoryComicsScreen
import com.jmread.ui.category.CategoryScreen
import com.jmread.ui.detail.ComicDetailScreen
import com.jmread.ui.home.HomeScreen
import com.jmread.ui.login.LoginScreen
import com.jmread.ui.mine.MineScreen
import com.jmread.ui.reader.ReaderScreen
import com.jmread.ui.search.SearchScreen
import com.jmread.ui.settings.LogScreen
import com.jmread.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

private data class TabItem(
    val route: String,
    val label: String,
)

private val tabs = listOf(
    TabItem("home", "首页"),
    TabItem("category", "分类"),
    TabItem("search", "搜索"),
    TabItem("mine", "我的"),
)

/** 需要整屏展示、不应挂底部标签栏的路由前缀 */
private val fullScreenRoutePrefixes = listOf("reader/")

private fun comicRoute(id: String) = "comic/${Uri.encode(id)}"

private fun readerRoute(id: String, order: Int) = "reader/${Uri.encode(id)}/$order"

@Composable
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
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val hideBottomBarInReader by ReaderPrefs.current().hideBottomBarInReader
        .collectAsState(initial = true)

    val route = currentDestination?.route
    val hideBottomBar = hideBottomBarInReader &&
        fullScreenRoutePrefixes.any { route?.startsWith(it) == true }

    // 无登录墙：禁漫全部读链路免登录可用，登录页只在「我的」页点击时进入

    Scaffold(
        bottomBar = {
            // 阅读页整屏展示：直接移除标签栏，Scaffold 会把这 64dp 还给内容区
            if (!hideBottomBar) {
                NavigationBar(modifier = Modifier.height(64.dp)) {
                    tabs.forEach { tab ->
                        val selected = currentDestination?.hierarchy
                            ?.any { it.route?.substringBefore('?') == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {},
                            label = { Text(tab.label, fontSize = 12.sp) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(innerPadding),
        ) {
            composable("home") {
                HomeScreen(
                    onComicClick = { id ->
                        navController.navigate(comicRoute(id))
                    },
                    onOpenPromote = { navController.navigate("promote") },
                    onOpenChannel = { id ->
                        navController.navigate("channel/${Uri.encode(id)}")
                    },
                    onOpenWeekly = { navController.navigate("weekly") },
                    onOpenFollow = { navController.navigate("follow-feed") },
                )
            }
            composable("promote") {
                com.jmread.ui.channel.PromoteScreen(
                    onBack = { navController.popBackStack() },
                    onComicClick = { id ->
                        navController.navigate(comicRoute(id))
                    },
                )
            }
            composable(
                "channel/{channelId}",
                arguments = listOf(
                    navArgument("channelId") { type = NavType.StringType },
                ),
            ) { entry ->
                val channelId = entry.arguments?.getString("channelId") ?: return@composable
                com.jmread.ui.channel.ChannelScreen(
                    channelId = channelId,
                    onBack = { navController.popBackStack() },
                    onComicClick = { id ->
                        navController.navigate(comicRoute(id))
                    },
                )
            }
            composable("weekly") {
                com.jmread.ui.weekly.WeeklyScreen(
                    onBack = { navController.popBackStack() },
                    onComicClick = { id ->
                        navController.navigate(comicRoute(id))
                    },
                )
            }
            composable("follow-feed") {
                com.jmread.ui.follow.FollowFeedScreen(
                    onBack = { navController.popBackStack() },
                    onComicClick = { id ->
                        navController.navigate(comicRoute(id))
                    },
                )
            }
            composable("category") {
                CategoryScreen(
                    onCategoryClick = { id ->
                        navController.navigate("category/${Uri.encode(id)}")
                    },
                    onComicClick = { id ->
                        navController.navigate(comicRoute(id))
                    },
                )
            }
            composable(
                "search?keyword={keyword}",
                arguments = listOf(
                    navArgument("keyword") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                SearchScreen(
                    initialKeyword = entry.arguments?.getString("keyword"),
                    onBack = null,
                    onComicClick = { id -> navController.navigate(comicRoute(id)) },
                )
            }
            composable("mine") {
                MineScreen(
                    onOpenSettings = { navController.navigate("settings") },
                    onOpenDownloads = { navController.navigate("downloads") },
                    onOpenBookmarks = { navController.navigate("bookmarks") },
                    onOpenAuthorFavourites = { navController.navigate("author-favourites") },
                    onOpenFollowManage = { navController.navigate("follow-manage") },
                    onOpenReader = { comicId, order ->
                        navController.navigate(readerRoute(comicId, order))
                    },
                    onOpenLogin = { navController.navigate("login") { launchSingleTop = true } },
                    onOpenRecentReads = { navController.navigate("recent-reads") },
                    onOpenCloudFavourites = { navController.navigate("cloud-favourites") },
                    onOpenCloudHistory = { navController.navigate("cloud-history") },
                )
            }
            composable("recent-reads") {
                com.jmread.ui.history.RecentReadsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenComic = { id ->
                        navController.navigate(comicRoute(id))
                    },
                )
            }
            composable("downloads") {
                com.jmread.ui.download.DownloadScreen(
                    onBack = { navController.popBackStack() },
                    onComicClick = { id, order ->
                        navController.navigate(readerRoute(id, order))
                    },
                )
            }
            composable("bookmarks") {
                com.jmread.ui.favourite.BookmarkScreen(
                    onBack = { navController.popBackStack() },
                    onComicClick = { id -> navController.navigate(comicRoute(id)) },
                )
            }
            composable("cloud-favourites") {
                com.jmread.ui.favourite.CloudFavouritesScreen(
                    onBack = { navController.popBackStack() },
                    onComicClick = { id -> navController.navigate(comicRoute(id)) },
                )
            }
            composable("cloud-history") {
                com.jmread.ui.history.CloudHistoryScreen(
                    onBack = { navController.popBackStack() },
                    onComicClick = { id -> navController.navigate(comicRoute(id)) },
                )
            }
            composable("author-favourites") {
                com.jmread.ui.author.AuthorFavouritesScreen(
                    onBack = { navController.popBackStack() },
                    onOpenAuthor = { author ->
                        navController.navigate("author/${Uri.encode(author)}")
                    },
                )
            }
            composable("follow-manage") {
                com.jmread.ui.follow.FollowManageScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable("login") {
                LoginScreen(
                    onLoggedIn = {
                        navController.popBackStack()
                    },
                )
            }
            composable("settings") {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenLog = { navController.navigate("log") },
                )
            }
            composable("log") {
                LogScreen(onBack = { navController.popBackStack() })
            }
            composable(
                "category/{categoryId}",
                arguments = listOf(
                    navArgument("categoryId") { type = NavType.StringType },
                ),
            ) { entry ->
                val categoryId: String? = entry.arguments?.getString("categoryId")
                if (categoryId == null) return@composable
                CategoryComicsScreen(
                    categoryId = categoryId,
                    onBack = { navController.popBackStack() },
                    onComicClick = { id ->
                        navController.navigate(comicRoute(id))
                    },
                )
            }
            composable(
                "comic/{id}",
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType },
                ),
            ) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                ComicDetailScreen(
                    ref = id,
                    onBack = { navController.popBackStack() },
                    onOpenReader = { comicId, order ->
                        navController.navigate(readerRoute(comicId, order))
                    },
                    onOpenAuthor = { author ->
                        navController.navigate("author/${Uri.encode(author)}")
                    },
                    onOpenTagSearch = { tag ->
                        navController.navigate("search?keyword=${Uri.encode(tag)}")
                    },
                    onComicClick = { comicId ->
                        navController.navigate(comicRoute(comicId)) {
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(
                "author/{author}",
                arguments = listOf(
                    navArgument("author") { type = NavType.StringType },
                ),
            ) { entry ->
                val author = entry.arguments?.getString("author") ?: return@composable
                AuthorComicsScreen(
                    author = author,
                    onBack = { navController.popBackStack() },
                    onComicClick = { id ->
                        navController.navigate(comicRoute(id))
                    },
                )
            }
            composable(
                "reader/{id}/{order}",
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType },
                    navArgument("order") { type = NavType.IntType },
                ),
            ) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                val order = entry.arguments?.getInt("order") ?: 1
                ReaderScreen(
                    ref = id,
                    order = order,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

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
