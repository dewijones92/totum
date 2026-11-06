package com.dewijones92.totum.workapps

import com.dewijones92.totum.dailyalarms.QuietGroup
import java.util.UUID

fun WorkAppsQuiet.addGroup(name: String, emoji: String, source: String): QuietGroup {
    val group = QuietGroup.blank(UUID.randomUUID().toString(), name, emoji)
    update(source) { it.withGroup(group) }
    return group
}

fun WorkAppsQuiet.removeGroup(groupId: String, source: String): RemovedGroup? {
    val before = settings.value
    val group = before.group(groupId) ?: return null
    update(source) { it.withoutGroup(groupId) }
    return RemovedGroup(group, before.groups.indexOf(group))
}

fun WorkAppsQuiet.restoreGroup(removed: RemovedGroup, source: String) =
    update(source) { it.withGroup(removed.group, removed.index) }

fun WorkAppsQuiet.removeApp(groupId: String, packageName: String, source: String): RemovedApp? {
    val index = settings.value.group(groupId)?.packages?.indexOf(packageName) ?: return null
    if (index < 0) return null
    update(source) { it.withoutApp(groupId, packageName) }
    return RemovedApp(groupId, packageName, index)
}

fun WorkAppsQuiet.restoreApp(removed: RemovedApp, source: String) =
    update(source) { it.withApp(removed.groupId, removed.packageName, removed.index) }
