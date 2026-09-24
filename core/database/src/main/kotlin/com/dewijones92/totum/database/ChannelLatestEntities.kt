package com.dewijones92.totum.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.channel.ChannelLatestStore
import com.dewijones92.totum.data.channel.CheckedChannel
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

@Entity(tableName = "channel_latest_uploads")
public data class ChannelLatestEntity(
    @PrimaryKey public val channelId: String,
    public val checkedAtEpochMs: Long,
    public val itemId: String?,
    public val title: String?,
    public val author: String?,
    public val thumbnailUrl: String?,
    public val mediaUrl: String?,
    public val publishedAtEpochMs: Long?,
)

@Dao
public interface ChannelLatestDao {
    @Query("SELECT * FROM channel_latest_uploads")
    public fun observeAll(): Flow<List<ChannelLatestEntity>>

    @Query("SELECT * FROM channel_latest_uploads")
    public suspend fun all(): List<ChannelLatestEntity>

    @Upsert
    public suspend fun upsertAll(rows: List<ChannelLatestEntity>)
}

public class RoomChannelLatestStore(private val dao: ChannelLatestDao) : ChannelLatestStore {

    override fun observe(): Flow<List<CheckedChannel>> = dao.observeAll().map { rows -> rows.map { it.toChecked() } }

    override suspend fun checkedAt(): Map<String, Instant> =
        dao.all().associate { it.channelId to Instant.ofEpochMilli(it.checkedAtEpochMs) }

    override suspend fun put(checked: List<CheckedChannel>) {
        dao.upsertAll(checked.map { it.toEntity() })
    }

    private fun CheckedChannel.toEntity() = ChannelLatestEntity(
        channelId = channelId,
        checkedAtEpochMs = checkedAt.toEpochMilli(),
        itemId = latest?.id?.value,
        title = latest?.title,
        author = latest?.author,
        thumbnailUrl = latest?.thumbnailUrl?.value,
        mediaUrl = latest?.mediaUrl?.value,
        publishedAtEpochMs = latest?.publishedAt?.toEpochMilli(),
    )

    private fun ChannelLatestEntity.toChecked(): CheckedChannel {
        val channelUrl = HttpUrl.parse("https://www.youtube.com/channel/$channelId")
        val latest = if (itemId != null && channelUrl != null) {
            MediaItem(
                id = MediaItemId(itemId),
                sourceId = SourceId(channelUrl.value),
                title = title ?: itemId,
                publishedAt = publishedAtEpochMs?.let(Instant::ofEpochMilli),
                duration = null,
                author = author,
                thumbnailUrl = thumbnailUrl?.let(HttpUrl::parse),
                mediaUrl = mediaUrl?.let(HttpUrl::parse),
                sourceUrl = channelUrl,
            )
        } else {
            null
        }
        return CheckedChannel(channelId, latest, Instant.ofEpochMilli(checkedAtEpochMs))
    }
}
