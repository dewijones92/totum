package com.dewijones92.totum.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.innertube.comments.Comment
import com.dewijones92.totum.ui.player.WatchViewModel.CommentsState
import com.dewijones92.totum.ui.player.WatchViewModel.PostState
import com.dewijones92.totum.ui.player.WatchViewModel.RepliesState

@Composable
internal fun CommentsSection(
    comments: CommentsState,
    watchActions: WatchActions,
    replies: CommentReplies,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.comments_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        if (watchActions.canAct) {
            CommentComposer(watchActions)
        }
        when (comments) {
            CommentsState.Loading -> PlayerNote(stringResource(R.string.comments_loading))
            CommentsState.Disabled -> PlayerNote(stringResource(R.string.comments_disabled))
            CommentsState.Error -> PlayerNote(stringResource(R.string.comments_error))
            is CommentsState.Loaded ->
                if (comments.comments.isEmpty()) {
                    PlayerNote(stringResource(R.string.comments_empty))
                } else {
                    comments.comments.forEach { comment -> CommentRow(comment, replies) }
                }
        }
    }
}

@Composable
private fun CommentComposer(watchActions: WatchActions) {
    var text by remember { mutableStateOf("") }
    // Clear the box once a post lands, then reset the state (as an effect, not in composition).
    LaunchedEffect(watchActions.postState) {
        if (watchActions.postState == PostState.Posted) {
            text = ""
            watchActions.onPostHandled()
        }
    }
    Column(modifier = Modifier.padding(bottom = 20.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(stringResource(R.string.comment_hint)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = watchActions.postState != PostState.Posting,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (watchActions.postState == PostState.Failed) {
                Text(
                    text = stringResource(R.string.comment_failed),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
            TextButton(
                onClick = { watchActions.onPostComment(text) },
                enabled = text.isNotBlank() && watchActions.postState != PostState.Posting,
            ) { Text(stringResource(R.string.comment_post)) }
        }
    }
}

@Composable
private fun CommentRow(comment: Comment, replies: CommentReplies) {
    Column(modifier = Modifier.padding(bottom = 16.dp)) {
        CommentBody(comment)
        // Tappable reply count expands the thread (only when it actually has replies).
        if (comment.replyCount > 0 && comment.replyToken != null) {
            val expanded = replies.threads.containsKey(comment.id)
            Text(
                text = if (expanded) {
                    stringResource(R.string.replies_hide)
                } else {
                    stringResource(R.string.comments_replies, comment.replyCount)
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clickable { replies.onToggle(comment) },
            )
            if (expanded) {
                RepliesBlock(comment.id, replies.threads[comment.id], replies.onLoadMore)
            }
        }
    }
}

/** A comment's author, text and like count — shared by top-level comments and replies. */
@Composable
private fun CommentBody(comment: Comment) {
    val author = buildString {
        append(comment.author)
        comment.publishedTime?.let { append("  ·  ").append(it) }
    }
    Text(
        text = author,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(text = comment.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
    comment.likeCount?.let {
        Text(
            text = "👍 $it",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun RepliesBlock(commentId: String, state: RepliesState?, onLoadMore: (String) -> Unit) {
    Column(
        modifier = Modifier.padding(start = 20.dp, top = 8.dp),
    ) {
        when (state) {
            null, RepliesState.Loading -> PlayerNote(stringResource(R.string.replies_loading))
            RepliesState.Error -> PlayerNote(stringResource(R.string.replies_error))
            is RepliesState.Loaded -> {
                state.replies.forEach { reply ->
                    Column(modifier = Modifier.padding(bottom = 12.dp)) { CommentBody(reply) }
                }
                if (state.moreToken != null) {
                    Text(
                        text = stringResource(R.string.replies_show_more),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onLoadMore(commentId) },
                    )
                }
            }
        }
    }
}
