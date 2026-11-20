# -*- coding: utf-8 -*-
"""MainScreen：移除 rank 路由；新增 follow-feed/weekly/promote/channel/cloud 路由"""
import io

p = 'app/src/main/java/com/jmread/ui/MainScreen.kt'
s = io.open(p, encoding='utf-8').read()

# 1. home route: new callbacks
old = '''            composable("home") {
                HomeScreen(
                    onComicClick = { id ->
                        navController.navigate(comicRoute(id))
                    },
                )
            }
            composable("rank") {
                com.jmread.ui.rank.RankScreen(
                    onBack = { navController.popBackStack() },
                    onComicClick = { id ->
                        navController.navigate(comicRoute(id))
                    },
                )
            }'''
new = '''            composable("home") {
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
            }'''
assert old in s, "home route"
s = s.replace(old, new)

# 2. mine route: cloud callbacks
old = '''                    onOpenLogin = { navController.navigate("login") { launchSingleTop = true } },
                    onOpenRecentReads = { navController.navigate("recent-reads") },
                )'''
new = '''                    onOpenLogin = { navController.navigate("login") { launchSingleTop = true } },
                    onOpenRecentReads = { navController.navigate("recent-reads") },
                    onOpenCloudFavourites = { navController.navigate("cloud-favourites") },
                    onOpenCloudHistory = { navController.navigate("cloud-history") },
                )'''
assert old in s, "mine route"
s = s.replace(old, new)

# 3. cloud routes after bookmarks
old = '''            composable("bookmarks") {
                com.jmread.ui.favourite.BookmarkScreen(
                    onBack = { navController.popBackStack() },
                    onComicClick = { id -> navController.navigate(comicRoute(id)) },
                )
            }'''
new = '''            composable("bookmarks") {
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
            }'''
assert old in s, "cloud routes"
s = s.replace(old, new)

io.open(p, 'w', encoding='utf-8').write(s)
print('done')
