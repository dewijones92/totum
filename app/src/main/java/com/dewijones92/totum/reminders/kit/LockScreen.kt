package com.dewijones92.totum.reminders.kit

import android.app.Activity

fun Activity.showOverLockScreen() {
    setShowWhenLocked(true)
    setTurnScreenOn(true)
}
