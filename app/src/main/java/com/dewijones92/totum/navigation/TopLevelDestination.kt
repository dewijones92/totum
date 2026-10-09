package com.dewijones92.totum.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.ui.graphics.vector.ImageVector
import com.dewijones92.totum.R

/**
 * The app's top-level destinations, shown in the bottom navigation bar.
 *
 * Five entries, per Material 3's 3–5 guidance. The queue earns a place because it
 * is the spine of playback — everything you tap lands in it. Account does not: it
 * is visited once to sign in, so it lives inside Library instead.
 */
enum class TopLevelDestination(
    @StringRes val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    Videos(
        labelRes = R.string.destination_videos,
        selectedIcon = Icons.Filled.SmartDisplay,
        unselectedIcon = Icons.Outlined.SmartDisplay,
    ),
    Podcasts(
        labelRes = R.string.destination_podcasts,
        selectedIcon = Icons.Filled.Podcasts,
        unselectedIcon = Icons.Outlined.Podcasts,
    ),
    Music(
        labelRes = R.string.destination_music,
        selectedIcon = Icons.Filled.LibraryMusic,
        unselectedIcon = Icons.Outlined.LibraryMusic,
    ),
    Queue(
        labelRes = R.string.destination_queue,
        selectedIcon = Icons.AutoMirrored.Filled.QueueMusic,
        unselectedIcon = Icons.AutoMirrored.Outlined.QueueMusic,
    ),
    Library(
        labelRes = R.string.destination_library,
        selectedIcon = Icons.Filled.CollectionsBookmark,
        unselectedIcon = Icons.Outlined.CollectionsBookmark,
    ),
}
