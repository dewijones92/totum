package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.innertube.auth.AccessToken
import com.dewijones92.totum.innertube.auth.OAuthTokens
import com.dewijones92.totum.innertube.auth.RefreshToken
import com.dewijones92.totum.innertube.auth.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

internal class FileTokenStore(private val file: File) : TokenStore {

    @Serializable
    private data class Stored(val access: String, val refresh: String, val expiresAt: Long)

    override suspend fun load(): OAuthTokens? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        runCatching { Json.decodeFromString(Stored.serializer(), file.readText()) }.getOrNull()?.let {
            OAuthTokens(AccessToken(it.access), RefreshToken(it.refresh), it.expiresAt)
        }
    }

    override suspend fun save(tokens: OAuthTokens): Unit = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        if (!file.exists()) {
            Files.createFile(
                file.toPath(),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
            )
        }
        val stored = Stored(tokens.accessToken.value, tokens.refreshToken.value, tokens.expiresAtEpochSeconds)
        file.writeText(Json.encodeToString(Stored.serializer(), stored))
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        file.delete()
        Unit
    }
}
