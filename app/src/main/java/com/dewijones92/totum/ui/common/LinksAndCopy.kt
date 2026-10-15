package com.dewijones92.totum.ui.common

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.launch

@Composable
fun LinkText(url: String, label: String, style: TextStyle, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val text = buildAnnotatedString {
        withLink(
            LinkAnnotation.Url(
                url,
                TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline)),
            ) { Diag.log("ui", "opened the link $url") },
        ) { append(label) }
    }
    Text(text, style = style, modifier = modifier.testTag(LINK_TEXT_TAG))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CopyableText(
    text: String,
    what: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    shown: AnnotatedString = AnnotatedString(text),
) {
    val copy = rememberCopy(text, what)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        val pressToCopy = if (shown.getLinkAnnotations(0, shown.length).isEmpty()) {
            Modifier.combinedClickable(onClick = {}, onLongClick = copy)
        } else {
            Modifier
        }
        Text(shown, style = style, modifier = Modifier.weight(1f, fill = false).then(pressToCopy))
        IconButton(onClick = copy, modifier = Modifier.testTag(COPY_BUTTON_TAG)) {
            Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.copy_what, what))
        }
    }
}

@Composable
fun rememberCopy(text: String, what: String): () -> Unit {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.copied, what)
    return {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(what, text)))
            Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
            Diag.log("ui", "copied the $what")
        }
    }
}

const val LINK_TEXT_TAG = "link-text"
const val COPY_BUTTON_TAG = "copy-button"
