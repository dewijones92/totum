package com.dewijones92.totum.cast

import com.google.android.gms.cast.CastStatusCodes

internal object CastCodes {

    private val named = mapOf(
        CastStatusCodes.SUCCESS to "SUCCESS (a normal end, nothing went wrong)",
        CastStatusCodes.NETWORK_ERROR to "NETWORK_ERROR",
        CastStatusCodes.TIMEOUT to "TIMEOUT",
        CastStatusCodes.INTERRUPTED to "INTERRUPTED",
        CastStatusCodes.INTERNAL_ERROR to "INTERNAL_ERROR",
        CastStatusCodes.UNKNOWN_ERROR to "UNKNOWN_ERROR",
        CastStatusCodes.AUTHENTICATION_FAILED to "AUTHENTICATION_FAILED",
        CastStatusCodes.INVALID_REQUEST to "INVALID_REQUEST",
        CastStatusCodes.CANCELED to "CANCELED",
        CastStatusCodes.NOT_ALLOWED to "NOT_ALLOWED",
        CastStatusCodes.APPLICATION_NOT_FOUND to "APPLICATION_NOT_FOUND",
        CastStatusCodes.APPLICATION_NOT_RUNNING to "APPLICATION_NOT_RUNNING (the receiver app is gone)",
        CastStatusCodes.MESSAGE_TOO_LARGE to "MESSAGE_TOO_LARGE",
        CastStatusCodes.MESSAGE_SEND_BUFFER_TOO_FULL to "MESSAGE_SEND_BUFFER_TOO_FULL",
        CastStatusCodes.DEVICE_CONNECTION_SUSPENDED to "DEVICE_CONNECTION_SUSPENDED",
        CastStatusCodes.FAILED to "FAILED",
        CastStatusCodes.REPLACED to "REPLACED (another sender took the TV)",
        CastStatusCodes.MEDIA_ERROR to "MEDIA_ERROR",
        CastStatusCodes.ERROR_CAST_PLATFORM_INCOMPATIBLE to "ERROR_CAST_PLATFORM_INCOMPATIBLE",
        CastStatusCodes.ERROR_URL_INSEURE to "ERROR_URL_INSEURE (the receiver refused a plain http address)",
        CastStatusCodes.ERROR_HOST_NOT_ALLOWED to "ERROR_HOST_NOT_ALLOWED",
        CastStatusCodes.ERROR_CAST_PLATFORM_NOT_CONNECTED to "ERROR_CAST_PLATFORM_NOT_CONNECTED",
        CastStatusCodes.ERROR_NO_CAST_CONFIGURATION to "ERROR_NO_CAST_CONFIGURATION",
        CastStatusCodes.ERROR_DEVICE_ID_FLAGS_NOT_SET to "ERROR_DEVICE_ID_FLAGS_NOT_SET",
        CastStatusCodes.ERROR_SERVICE_CREATION_FAILED to "ERROR_SERVICE_CREATION_FAILED",
        CastStatusCodes.ERROR_SERVICE_DISCONNECTED to "ERROR_SERVICE_DISCONNECTED",
        CastStatusCodes.ERROR_STOPPING_SERVICE_FAILED to "ERROR_STOPPING_SERVICE_FAILED",
    )

    private const val CAUSE_SERVICE_DISCONNECTED = 1
    private const val CAUSE_NETWORK_LOST = 2

    fun name(code: Int): String {
        val known = named[code]
        if (known != null) return "$code $known"
        val sdk = runCatching { CastStatusCodes.getStatusCodeString(code) }.getOrNull()
        return "$code unknown code (the SDK calls it \"${sdk ?: "nothing"}\")"
    }

    fun suspendedReason(reason: Int): String = when (reason) {
        CAUSE_SERVICE_DISCONNECTED -> "$reason the Cast service disconnected"
        CAUSE_NETWORK_LOST -> "$reason the network was lost"
        else -> name(reason)
    }
}
