package com.dewijones92.totum.data.home

import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetQueueRequest
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.data.targets.TargetsAnswer
import com.dewijones92.totum.data.targets.TargetsJson
import kotlinx.serialization.KSerializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

public interface TargetsApi {
    public suspend fun targets(): HomeAnswer<TargetsAnswer>
    public suspend fun queue(id: String, request: TargetQueueRequest): HomeAnswer<TargetState>
    public suspend fun control(id: String, control: TargetControl): HomeAnswer<TargetState>
}

public class TargetsClient(
    client: OkHttpClient,
    private val base: String,
    token: () -> String,
) : TargetsApi {
    private val pi = HomePiCall(client, token, "targets")

    override suspend fun targets(): HomeAnswer<TargetsAnswer> =
        pi.call("GET /targets", Request.Builder().url("$base/targets").get()) {
            TargetsJson.decodeFromString(TargetsAnswer.serializer(), it)
        }

    override suspend fun queue(id: String, request: TargetQueueRequest): HomeAnswer<TargetState> = post(
        "POST /targets/queue?id=$id (${request.mode} ${request.items.size})",
        "targets/queue?id=${encoded(id)}",
        TargetQueueRequest.serializer(),
        request,
    )

    override suspend fun control(id: String, control: TargetControl): HomeAnswer<TargetState> = post(
        "POST /targets/control?id=$id (${control.action})",
        "targets/control?id=${encoded(id)}",
        TargetControl.serializer(),
        control,
    )

    private suspend fun <T> post(
        what: String,
        path: String,
        serializer: KSerializer<T>,
        body: T,
    ): HomeAnswer<TargetState> {
        val json = TargetsJson.encodeToString(serializer, body).toRequestBody(JSON)
        return pi.call(what, Request.Builder().url("$base/$path").post(json)) {
            TargetsJson.decodeFromString(TargetState.serializer(), it)
        }
    }

    private fun encoded(id: String) = URLEncoder.encode(id, Charsets.UTF_8).replace("+", "%20")

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
