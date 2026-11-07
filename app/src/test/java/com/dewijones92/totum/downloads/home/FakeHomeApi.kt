package com.dewijones92.totum.downloads.home

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.backup.BackupItem
import com.dewijones92.totum.data.home.AccountHandover
import com.dewijones92.totum.data.home.HomeAnswer
import com.dewijones92.totum.data.home.HomeJob
import com.dewijones92.totum.data.home.HomeJobRequest
import com.dewijones92.totum.data.home.HomeJobs
import com.dewijones92.totum.data.home.HomeJobsApi
import com.dewijones92.totum.data.home.HomeProblem
import java.io.File

internal class FakeHomeApi : HomeJobsApi {
    val started = mutableListOf<HomeJobRequest>()
    val handedOver = mutableListOf<AccountHandover>()
    var listed: List<HomeJob> = emptyList()
    var refuse: String? = null
    var freeBytes = 1L
    var problems: List<HomeProblem> = emptyList()
    override suspend fun start(job: HomeJobRequest): HomeAnswer<String> {
        started += job
        return refuse?.let { HomeAnswer.No(it) } ?: HomeAnswer.Ok(job.item.itemId)
    }
    override suspend fun jobs() = HomeAnswer.Ok(HomeJobs(listed, freeBytes, true, problems))
    val cancelled = mutableListOf<String>()
    override suspend fun cancel(id: String): HomeAnswer<Unit> {
        cancelled += id
        return HomeAnswer.Ok(Unit)
    }
    override suspend fun retry(id: String) = HomeAnswer.Ok(Unit)
    override suspend fun handOver(account: AccountHandover): HomeAnswer<Unit> {
        handedOver += account
        return HomeAnswer.Ok(Unit)
    }
    val forgotBy = mutableListOf<String?>()
    override suspend fun forgetAccount(by: String?): HomeAnswer<Unit> {
        forgotBy += by
        return HomeAnswer.Ok(Unit)
    }
    override fun fileUrl(id: String) = HttpUrl.of("https://home.test/fetch/files/$id")
    val uploaded = mutableListOf<String>()
    var refuseUpload: String? = null
    override suspend fun upload(
        item: BackupItem,
        audioOnly: Boolean,
        file: File,
        onSent: (sent: Long, total: Long) -> Unit,
    ): HomeAnswer<Unit> {
        onSent(file.length(), file.length())
        uploaded += item.itemId
        return refuseUpload?.let { HomeAnswer.No(it) } ?: HomeAnswer.Ok(Unit)
    }
}
