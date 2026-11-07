package com.dewijones92.totum.targets

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import com.dewijones92.totum.data.targets.Requester
import java.util.UUID

class RequesterIdentity(context: Context, private val model: String = Build.MODEL) {
    private val prefs = context.getSharedPreferences("totum_phone_identity", Context.MODE_PRIVATE)

    fun requester(): Requester {
        val id = prefs.getString(KEY, null) ?: UUID.randomUUID().toString().also { prefs.edit { putString(KEY, it) } }
        return Requester(id, labelFor(model, id))
    }

    companion object {
        private const val KEY = "id"
        private const val SUFFIX = 4

        fun labelFor(model: String, id: String): String =
            "${model.ifBlank { "Phone" }} (${id.filter { it.isLetterOrDigit() }.take(SUFFIX)})"
    }
}
