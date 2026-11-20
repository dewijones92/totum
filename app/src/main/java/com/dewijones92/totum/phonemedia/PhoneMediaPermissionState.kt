package com.dewijones92.totum.phonemedia

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.phone.PhoneMediaAccess
import com.dewijones92.totum.data.phone.PhoneMediaGate
import com.dewijones92.totum.data.phone.PhoneMediaPermissions

class PhoneMediaPermissionState(
    private val isGranted: (String) -> Boolean,
    private val asked: () -> Boolean,
    private val setAsked: () -> Unit,
) {
    fun access(): PhoneMediaAccess = PhoneMediaPermissions.access(isGranted)

    fun askedBefore(): Boolean = asked()

    fun noteAsked() {
        setAsked()
        Diag.log(
            "phone-media",
            "the permission dialog was shown and answered; recorded so a refusal points at Settings"
        )
    }

    fun gate(): PhoneMediaGate {
        val access = access()
        val gate = PhoneMediaPermissions.gate(access, askedBefore())
        Diag.log("phone-media", "gate: ${PhoneMediaPermissions.reason(gate, access)}")
        return gate
    }

    companion object {
        private const val PREFS = "phone_media"
        private const val ASKED = "asked_for_media_permissions"

        fun of(context: Context): PhoneMediaPermissionState {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return PhoneMediaPermissionState(
                isGranted = { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED },
                asked = { prefs.getBoolean(ASKED, false) },
                setAsked = { prefs.edit { putBoolean(ASKED, true) } },
            )
        }

        fun everythingGranted(): PhoneMediaPermissionState =
            PhoneMediaPermissionState(isGranted = { true }, asked = { true }, setAsked = {})
    }
}
