package com.dewijones92.totum.video

sealed interface LengthCheck {
    data class Known(val seconds: Long, val wasLive: Boolean, val via: String) : LengthCheck

    data class LiveNow(val via: String) : LengthCheck

    data class Upcoming(val via: String) : LengthCheck

    data class SaysNone(val why: String) : LengthCheck

    data class Unknown(val why: String) : LengthCheck
}
