package chat.cypher.android.ui

import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import chat.cypher.android.core.MeshController
import chat.cypher.android.ui.calls.CallsTab
import chat.cypher.android.ui.chats.ChatThread
import chat.cypher.android.ui.chats.ChatsTab
import chat.cypher.android.ui.components.PeerAvatar
import chat.cypher.android.ui.contacts.ContactsTab
import chat.cypher.android.ui.profile.ProfileTab
import chat.cypher.android.ui.theme.CypherThemeDefaults

/** The four tabs of the main experience. */
enum class MainTab { CHATS, CONTACTS, CALLS, PROFILE }

/**
 * Root scaffold: bottom navigation (Chats · Contacts · Calls · Profile)
 * with the open conversation rendered as a full-screen layer above it.
 */
@Composable
fun AppScaffold() {
    var tab by rememberSaveable { mutableStateOf(MainTab.CHATS) }
    val colors = CypherThemeDefaults.colors
    val openConversation = MeshController.openConversation

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas),
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    MainTab.CHATS -> ChatsTab(
                        onOpenConversation = { MeshController.openConversation = it },
                    )
                    MainTab.CONTACTS -> ContactsTab(
                        onOpenConversation = { MeshController.openConversation = it },
                    )
                    MainTab.CALLS -> CallsTab(
                        onOpenConversation = { MeshController.openConversation = it },
                    )
                    MainTab.PROFILE -> ProfileTab()
                }
            }
            BottomNavBar(selected = tab, onSelect = { tab = it })
        }

        // Open conversation slides over everything.
        androidx.compose.animation.AnimatedContent(
            targetState = openConversation,
            transitionSpec = {
                if (targetState != null) {
                    (slideInVertically { it } + fadeIn()).togetherWith(
                        slideOutVertically { it } + fadeOut(),
                    )
                } else {
                    (fadeIn()).togetherWith(fadeOut())
                }
            },
            label = "conversation",
        ) { conversation ->
            if (conversation != null) {
                ChatThread(
                    conversationId = conversation,
                    onBack = { MeshController.openConversation = null },
                )
            }
        }
    }
}

/** The design-verified bottom bar: white surface, filled active icon with
 *  a leading indicator line, outline icons for inactive tabs. */
@Composable
private fun BottomNavBar(selected: MainTab, onSelect: (MainTab) -> Unit) {
    val colors = CypherThemeDefaults.colors
    Surface(color = colors.surface, shadowElevation = 8.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(68.dp),
        ) {
            NavItem(
                label = "Chats",
                activeIcon = Icons.Filled.ChatBubble,
                inactiveIcon = Icons.Outlined.ChatBubbleOutline,
                selected = selected == MainTab.CHATS,
                badge = MeshController.unreadPeers.size + meshUnreadCount(),
                modifier = Modifier.weight(1f),
            ) { onSelect(MainTab.CHATS) }
            NavItem(
                label = "Contacts",
                activeIcon = Icons.Filled.Person,
                inactiveIcon = Icons.Outlined.PersonOutline,
                selected = selected == MainTab.CONTACTS,
                modifier = Modifier.weight(1f),
            ) { onSelect(MainTab.CONTACTS) }
            NavItem(
                label = "Calls",
                activeIcon = Icons.Filled.Call,
                inactiveIcon = Icons.Outlined.Call,
                selected = selected == MainTab.CALLS,
                modifier = Modifier.weight(1f),
            ) { onSelect(MainTab.CALLS) }
            NavItem(
                label = "Profile",
                activeIcon = null,
                inactiveIcon = Icons.Outlined.PersonOutline,
                selected = selected == MainTab.PROFILE,
                isProfile = true,
                modifier = Modifier.weight(1f),
            ) { onSelect(MainTab.PROFILE) }
        }
    }
}

private fun meshUnreadCount(): Int =
    if (MeshController.unreadPeers.isEmpty()) 0 else 0

@Composable
private fun NavItem(
    label: String,
    activeIcon: ImageVector?,
    inactiveIcon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    badge: Int = 0,
    isProfile: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = CypherThemeDefaults.colors
    val tint = if (selected) colors.accentDark else colors.textTertiary
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(top = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Leading indicator line on the active tab.
        Box(
            modifier = Modifier
                .width(if (selected) 22.dp else 22.dp)
                .height(3.dp)
                .background(
                    color = if (selected) colors.accentDark else Color.Transparent,
                    shape = RoundedCornerShape(2.dp),
                ),
        )
        Box(Modifier.padding(top = 7.dp)) {
            if (isProfile) {
                PeerAvatar(
                    name = MeshController.myNickname.ifBlank { "?" },
                    peerId = MeshController.myPeerId,
                    size = if (selected) 27.dp else 25.dp,
                )
            } else {
                Icon(
                    imageVector = if (selected && activeIcon != null) activeIcon else inactiveIcon,
                    contentDescription = label,
                    tint = tint,
                    modifier = Modifier.size(26.dp),
                )
            }
            if (badge > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(9.dp)
                        .background(colors.accent, RoundedCornerShape(5.dp)),
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}
