package com.dewijones92.totum.data.update

import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId

public data class ApkFacts(val packageName: String?, val versionName: String?, val signers: Set<String>)

public sealed interface ApkVerdict {
    public data object Genuine : ApkVerdict

    public data class Refused(val why: String) : ApkVerdict
}

public fun judgeApk(archive: ApkFacts?, installed: ApkFacts, release: Release): ApkVerdict = when {
    archive == null -> ApkVerdict.Refused("Android could not read the downloaded file as an app")
    archive.packageName != installed.packageName ->
        ApkVerdict.Refused("the file is a different app (${archive.packageName}), not ${installed.packageName}")
    archive.signers.isEmpty() -> ApkVerdict.Refused("the file is not signed")
    archive.signers != installed.signers ->
        ApkVerdict.Refused("the file is signed with a different key from the installed Totum; Android would refuse it")
    archive.versionName != release.version.toString() ->
        ApkVerdict.Refused("the file is version ${archive.versionName}, not ${release.version}")
    else -> ApkVerdict.Genuine
}

public fun Release.apkDownload(): PlayableItem? = apk?.let {
    PlayableItem(
        item = MediaItem(
            id = MediaItemId("app-update:$tag"),
            sourceId = SourceId(UPDATE_SOURCE),
            title = "Totum $version",
            publishedAt = null,
            duration = null,
            mediaUrl = it.url,
        ),
        handle = PlayHandle.Podcast(),
    )
}

private const val UPDATE_SOURCE = "app-update"
