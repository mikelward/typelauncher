package app.typelauncher

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.vector.ImageVector

internal fun AppCategory.icon(): ImageVector = when (this) {
    AppCategory.Games -> Icons.Filled.SportsEsports
    AppCategory.Music -> Icons.Filled.MusicNote
    AppCategory.Video -> Icons.Filled.Movie
    AppCategory.Photos -> Icons.Filled.PhotoLibrary
    AppCategory.Social -> Icons.Filled.People
    AppCategory.News -> Icons.Filled.Newspaper
    AppCategory.Maps -> Icons.Filled.Map
    AppCategory.Productivity -> Icons.Filled.Work
    AppCategory.Accessibility -> Icons.Filled.Accessibility
    AppCategory.Browser -> Icons.Filled.Public
    AppCategory.Email -> Icons.Filled.Email
    AppCategory.Messaging -> Icons.Filled.Chat
    AppCategory.Calendar -> Icons.Filled.CalendarMonth
    AppCategory.Contacts -> Icons.Filled.Contacts
    AppCategory.Files -> Icons.Filled.Folder
    AppCategory.Fitness -> Icons.Filled.FitnessCenter
    AppCategory.Finance -> Icons.Filled.AccountBalance
    AppCategory.Other -> Icons.Filled.Apps
}
