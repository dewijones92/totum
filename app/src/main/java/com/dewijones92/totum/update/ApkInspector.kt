package com.dewijones92.totum.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.update.ApkFacts
import com.dewijones92.totum.data.update.ApkVerdict
import com.dewijones92.totum.data.update.Release
import com.dewijones92.totum.data.update.judgeApk
import java.io.File
import java.security.MessageDigest

internal class ApkInspector(private val context: Context) {

    fun inspect(file: File, release: Release): ApkVerdict {
        val archive = infoOf { it.getPackageArchiveInfo(file.path, FLAGS) }?.let(::factsOf)
        val installed = infoOf { it.getPackageInfo(context.packageName, FLAGS) }?.let(::factsOf)
            ?: ApkFacts(context.packageName, null, emptySet())
        Diag.log(TAG, "check: file=$archive installed=$installed expected=v${release.version}")
        return judgeApk(archive, installed, release)
    }

    private fun infoOf(read: (PackageManager) -> PackageInfo?): PackageInfo? =
        runCatching { read(context.packageManager) }
            .onFailure { Diag.warn(TAG, "check: package info could not be read", it) }
            .getOrNull()

    private fun factsOf(info: PackageInfo) = ApkFacts(
        packageName = info.packageName,
        versionName = info.versionName,
        signers = info.signingInfo?.apkContentsSigners.orEmpty().map { sha256(it.toByteArray()) }.toSet(),
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "update"
        val FLAGS = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
    }
}
