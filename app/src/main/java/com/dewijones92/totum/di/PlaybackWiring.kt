package com.dewijones92.totum.di

import android.content.Context
import com.dewijones92.totum.playback.Media3PlaybackController
import com.dewijones92.totum.playback.PlaybackController
import com.dewijones92.totum.playback.SharedPrefsPlaybackSpeedStore
import com.dewijones92.totum.playback.SharedPrefsVolumeBoostStore
import com.dewijones92.totum.settings.setting
import kotlinx.coroutines.launch

internal fun playbackControllerOf(c: AppContainer, context: Context): PlaybackController = Media3PlaybackController(
    context,
    c.applicationScope,
    c.randomStarts,
    SharedPrefsPlaybackSpeedStore(context),
    SharedPrefsVolumeBoostStore(context),
    onPlay = { item, kind ->
        c.applicationScope.launch { recordPlay(c.playHistoryStore, c.downloadManager, item, kind) }
    },
    silenceMode = c.appPreferences.setting(c.applicationScope) { it.silenceMode },
    rewindOnResume = c.appPreferences.setting(c.applicationScope) { it.rewindOnResume },
    sourceSkips = c.appPreferences.setting(c.applicationScope) { it.sourceSkips },
    silentTailEnds = c.appPreferences.setting(c.applicationScope) { it.silentTailEnds },
)
