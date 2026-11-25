package com.mao.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mao.app.ui.chat.ChatScreen
import com.mao.app.ui.group.CreateGroupScreen
import com.mao.app.ui.group.DiscoverGroupsScreen
import com.mao.app.ui.group.GroupInfoScreen
import com.mao.app.ui.group.JoinGroupScreen
import com.mao.app.ui.home.HomeScreen
import com.mao.app.ui.people.NewChatScreen
import com.mao.app.ui.profile.ProfileScreen

object Routes {
    const val HOME = "home"
    const val NEW_CHAT = "new-chat"
    const val NEW_GROUP = "new-group"
    const val DISCOVER = "discover"
    const val PROFILE = "profile"
    fun chat(id: String) = "chat/$id"
    fun groupInfo(id: String) = "group/$id"
    fun join(code: String) = "join/$code"
}

@Composable
fun MaoNavHost(pendingInvite: String?, onInviteConsumed: () -> Unit) {
    val nav = rememberNavController()

    LaunchedEffect(pendingInvite) {
        if (pendingInvite != null) {
            nav.navigate(Routes.join(pendingInvite))
            onInviteConsumed()
        }
    }

    val openChat: (String) -> Unit = { id ->
        nav.navigate(Routes.chat(id)) { popUpTo(Routes.HOME) }
    }

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenChat = { nav.navigate(Routes.chat(it)) },
                onNewChat = { nav.navigate(Routes.NEW_CHAT) },
                onNewGroup = { nav.navigate(Routes.NEW_GROUP) },
                onDiscover = { nav.navigate(Routes.DISCOVER) },
                onJoinCode = { nav.navigate(Routes.join(it)) },
                onProfile = { nav.navigate(Routes.PROFILE) },
            )
        }
        composable(Routes.chat("{id}"), arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("id")!!
            ChatScreen(
                conversationId = id,
                onBack = { nav.popBackStack() },
                onOpenInfo = { nav.navigate(Routes.groupInfo(id)) },
                onOpenChat = openChat,
            )
        }
        composable(Routes.groupInfo("{id}"), arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("id")!!
            GroupInfoScreen(
                conversationId = id,
                onBack = { nav.popBackStack() },
                onOpenChat = openChat,
                onLeft = { nav.popBackStack(Routes.HOME, inclusive = false) },
            )
        }
        composable(Routes.NEW_CHAT) { NewChatScreen(onBack = { nav.popBackStack() }, onOpenChat = openChat) }
        composable(Routes.NEW_GROUP) { CreateGroupScreen(onBack = { nav.popBackStack() }, onCreated = openChat) }
        composable(Routes.DISCOVER) { DiscoverGroupsScreen(onBack = { nav.popBackStack() }, onOpenChat = openChat) }
        composable(Routes.PROFILE) { ProfileScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.join("{code}"), arguments = listOf(navArgument("code") { type = NavType.StringType })) { entry ->
            JoinGroupScreen(code = entry.arguments?.getString("code")!!, onBack = { nav.popBackStack() }, onOpenChat = openChat)
        }
    }
}
