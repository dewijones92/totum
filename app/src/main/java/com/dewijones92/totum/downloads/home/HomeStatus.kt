package com.dewijones92.totum.downloads.home

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.home.HomeJobState
import com.dewijones92.totum.data.home.HomeJobs
import com.dewijones92.totum.data.home.HomeProblem
import com.dewijones92.totum.domain.StorageUsage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class HomeStatus(private val notices: HomeNotices) {
    private val trouble = MutableStateFlow<List<HomeProblem>>(emptyList())
    val problems: StateFlow<List<HomeProblem>> = trouble.asStateFlow()
    private val space = MutableStateFlow<StorageUsage?>(null)
    val storage: StateFlow<StorageUsage?> = space.asStateFlow()
    private var read = false

    fun read(answer: HomeJobs) {
        val done = answer.jobs.filter { it.state == HomeJobState.DONE }
        space.value = StorageUsage(done.size, done.sumOf { it.bytes }, answer.freeBytes)
        val now = answer.problems
        val before = trouble.value.map { it.key }.toSet()
        trouble.value = now
        if (now.map { it.key }.toSet() != before) {
            Diag.log("home", "home problems: ${now.joinToString { it.text }.ifEmpty { "none" }}")
        }
        if (read) now.filter { it.key !in before }.forEach { notices.problem(it.key, it.text) }
        read = true
    }
}
