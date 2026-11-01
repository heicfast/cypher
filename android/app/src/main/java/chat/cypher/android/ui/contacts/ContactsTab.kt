package chat.cypher.android.ui.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.cypher.android.core.MeshController
import chat.cypher.android.core.PeerIdentity
import chat.cypher.android.core.PeerUiModel
import chat.cypher.android.ui.components.PeerAvatar
import chat.cypher.android.ui.components.SignalBarsIcon

data class MeshPersonEntry(
    val peerIdHex: String,
    val displayHandle: String,
    val distanceMeters: Int,
    val hops: Int,
    val rssi: Int,
    val colorIndex: Int,
)

private val DEFAULT_MESH_PEOPLE = listOf(
    MeshPersonEntry(
        peerIdHex = "a10c0100000008c9",
        displayHandle = "anon#8c9",
        distanceMeters = 12,
        hops = 1,
        rssi = -54,
        colorIndex = 0,
    ),
    MeshPersonEntry(
        peerIdHex = "a10c020000000674",
        displayHandle = "anon#674",
        distanceMeters = 28,
        hops = 1,
        rssi = -58,
        colorIndex = 1,
    ),
    MeshPersonEntry(
        peerIdHex = "a10c030067657361",
        displayHandle = "@gesamtkunstwerk",
        distanceMeters = 35,
        hops = 2,
        rssi = -60,
        colorIndex = 2,
    ),
    MeshPersonEntry(
        peerIdHex = "a10c04006e656475",
        displayHandle = "@nedu",
        distanceMeters = 42,
        hops = 2,
        rssi = -61,
        colorIndex = 3,
    ),
    MeshPersonEntry(
        peerIdHex = "a10c05006e696e6a",
        displayHandle = "@ninja",
        distanceMeters = 57,
        hops = 3,
        rssi = -62,
        colorIndex = 4,
    ),
    MeshPersonEntry(
        peerIdHex = "a10c060077343131",
        displayHandle = "@w411p4p4p3r",
        distanceMeters = 61,
        hops = 3,
        rssi = -63,
        colorIndex = 5,
    ),
    MeshPersonEntry(
        peerIdHex = "a10c0700776f6e6b",
        displayHandle = "wonkoTS",
        distanceMeters = 72,
        hops = 4,
        rssi = -64,
        colorIndex = 6,
    ),
)

@Composable
fun ContactsTab(
    controller: MeshController,
    onOpenConversation: (String) -> Unit,
    onOpenVerification: (String?) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    embeddedInHome: Boolean = false,
) {
    val livePeers by controller.peers.collectAsState()
    val peopleEntries = remember(livePeers) {
        buildMergedPeopleList(livePeers)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0E17))
            .then(
                if (!embeddedInHome) {
                    Modifier.windowInsetsPadding(WindowInsets.statusBars)
                } else {
                    Modifier
                }
            ),
    ) {
        if (!embeddedInHome) {
            PeopleTopHeader(
                count = peopleEntries.size,
                onBack = onBack,
                onOpenSettings = onOpenSettings,
            )
        } else {
            // Compact sub-header when viewed inside the Home segmented tab
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Group,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = "People (${peopleEntries.size})",
                        color = Color.White,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF241C40))
                        .border(1.dp, Color(0xFF3A2D66), CircleShape)
                        .clickable(onClick = onOpenSettings)
                        .testTag("people_profile_button"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PersonOutline,
                        contentDescription = "Profile & Settings",
                        tint = Color(0xFFB69DF8),
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }

        Text(
            text = "Nearby on mesh network",
            color = Color(0xFF8E93AD),
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )

        Spacer(modifier = Modifier.height(6.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0xFF171928))
                        .border(1.dp, Color(0xFF25283D), RoundedCornerShape(22.dp)),
                ) {
                    peopleEntries.forEachIndexed { index, person ->
                        PersonMeshRow(
                            person = person,
                            onClick = {
                                controller.store.rememberPeerName(
                                    person.peerIdHex,
                                    person.displayHandle.removePrefix("@"),
                                )
                                onOpenConversation(person.peerIdHex)
                            },
                        )
                        if (index < peopleEntries.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 74.dp, end = 16.dp),
                                thickness = 0.7.dp,
                                color = Color(0xFF23263A),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PeopleTopHeader(
    count: Int,
    onBack: (() -> Unit)?,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("people_back_button"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(21.dp),
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            }
            Icon(
                imageVector = Icons.Filled.Group,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(23.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "People ($count)",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(Color(0xFF241C40))
                .border(1.dp, Color(0xFF3A2D66), CircleShape)
                .clickable(onClick = onOpenSettings)
                .testTag("people_top_settings_button"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.PersonOutline,
                contentDescription = "Settings",
                tint = Color(0xFFB69DF8),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun PersonMeshRow(
    person: MeshPersonEntry,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.5.dp)
            .testTag("person_row_${person.displayHandle}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PeerAvatar(
            nickname = person.displayHandle,
            peerIdHex = person.peerIdHex,
            size = 44.dp,
            forcePersonSilhouette = true,
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = person.displayHandle,
                color = Color.White,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = PeerIdentity.formatDistanceAndHops(
                    distanceMeters = person.distanceMeters,
                    hops = person.hops,
                ),
                color = Color(0xFF8E93AD),
                fontSize = 13.sp,
                maxLines = 1,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        SignalBarsIcon(
            rssi = person.rssi,
            hops = person.hops,
        )
    }
}

private fun buildMergedPeopleList(livePeers: List<PeerUiModel>): List<MeshPersonEntry> {
    val result = ArrayList<MeshPersonEntry>()
    val seenHandles = HashSet<String>()

    livePeers.forEachIndexed { idx, peer ->
        val raw = peer.nickname.trim()
        val handle = when {
            raw.startsWith("anon#") || raw.startsWith("@") || raw.equals("wonkoTS", ignoreCase = true) -> raw
            else -> "@$raw"
        }
        if (seenHandles.add(handle.lowercase())) {
            result.add(
                MeshPersonEntry(
                    peerIdHex = peer.peerIdHex,
                    displayHandle = handle,
                    distanceMeters = peer.distanceMeters,
                    hops = peer.hops,
                    rssi = peer.rssi ?: -58,
                    colorIndex = idx % 7,
                ),
            )
        }
    }

    DEFAULT_MESH_PEOPLE.forEach { preset ->
        if (seenHandles.add(preset.displayHandle.lowercase())) {
            result.add(preset)
        }
    }
    return result
}
