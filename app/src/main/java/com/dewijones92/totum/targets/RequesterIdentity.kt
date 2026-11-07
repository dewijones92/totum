package com.dewijones92.totum.targets

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import com.dewijones92.totum.data.targets.Requester
import java.util.UUID

class RequesterIdentity(context: Context, private val ownsTheHomeAccount: suspend () -> Boolean) {
    private val prefs = context.getSharedPreferences("totum_phone_identity", Context.MODE_PRIVATE)

    suspend fun requester(): Requester {
        val id = prefs.getString(KEY, null) ?: UUID.randomUUID().toString().also { prefs.edit { putString(KEY, it) } }
        return Requester(id, Build.MODEL, owner = ownsTheHomeAccount())
    }

    private companion object {
        const val KEY = "id"
    }
}
