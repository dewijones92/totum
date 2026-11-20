package com.dewijones92.totum.ui.library

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.PhoneMediaEntry
import com.dewijones92.totum.domain.PhoneMediaKind
import kotlinx.coroutines.delay

private const val MAX_ZOOM = 6f
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val MS_PER_SECOND = 1000L
private val SLIDESHOW_SECONDS = listOf(3, 5, 10)

@Composable
internal fun PhotoViewer(
    viewing: PhotoViewing,
    screens: PhotoScreens,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewing.browser.state.collectAsStateWithLifecycle()
    val photos = remember(state.entries) { state.entries.filter { it.kind == PhoneMediaKind.PHOTO } }
    if (photos.isEmpty()) {
        LaunchedEffect(Unit) {
            Diag.warn("phone-media", "the photo viewer has nothing to show; closing")
            onClose()
        }
        return
    }
    val start = remember { photos.indexOfFirst { it.key == viewing.startKey }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = start) { photos.size }
    var slideshow by rememberSaveable { mutableStateOf<Int?>(null) }
    val view = LocalView.current
    val mirror = rememberScreenMirror(screens, photos, pager)
    DisposableEffect(slideshow != null) {
        val before = view.keepScreenOn
        view.keepScreenOn = slideshow != null
        onDispose { view.keepScreenOn = before }
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect {
            val shown = photos.getOrNull(it)
            Diag.log("phone-media", "viewing photo ${it + 1} of ${photos.size}: ${shown?.id?.value}")
            if (it >= photos.size - PREFETCH_PHOTOS && state.hasMore && !state.loading) viewing.browser.more()
        }
    }
    LaunchedEffect(slideshow, photos.size) {
        val seconds = slideshow ?: return@LaunchedEffect
        Diag.log("phone-media", "slideshow started: every $seconds s over ${photos.size} loaded photos")
        while (true) {
            delay(seconds * MS_PER_SECOND)
            val next = pager.currentPage + 1
            when {
                next < photos.size -> pager.animateScrollToPage(next, animationSpec = tween(SLIDE_MS))
                viewing.browser.state.value.hasMore -> {
                    Diag.log("phone-media", "slideshow is at the last loaded photo; loading more before going on")
                    viewing.browser.more()
                }
                else -> {
                    Diag.log("phone-media", "slideshow reached the last photo and goes back to the first")
                    pager.scrollToPage(0)
                }
            }
        }
    }
    Box(modifier.fillMaxSize().background(Color.Black).testTag(PHOTO_VIEWER_TAG)) {
        HorizontalPager(
            pager,
            Modifier.fillMaxSize(),
            key = { photos[it].id.value },
            beyondViewportPageCount = 1
        ) { page ->
            ZoomableImage(photos[page])
        }
        ViewerChrome(
            position = pager.currentPage + 1,
            total = photos.size,
            entry = photos[pager.currentPage.coerceIn(photos.indices)],
            slideshow = slideshow,
            mirror = mirror,
            onSlideshow = {
                slideshow = it
                Diag.log("phone-media", "slideshow ${if (it == null) "paused" else "set to every $it s"}")
            },
            onClose = onClose,
        )
    }
}

private const val PREFETCH_PHOTOS = 8
private const val SLIDE_MS = 450

@Composable
private fun ViewerChrome(
    position: Int,
    total: Int,
    entry: PhoneMediaEntry,
    slideshow: Int?,
    mirror: ScreenMirror,
    onSlideshow: (Int?) -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Row(
            Modifier.fillMaxWidth().background(
                Color.Black.copy(alpha = 0.55f)
            ).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose, modifier = Modifier.testTag(PHOTO_CLOSE_TAG)) {
                Text(stringResource(R.string.photo_viewer_close), color = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.photo_viewer_position, position, total), color = Color.White)
                Text(entry.title, color = Color.White, style = MaterialTheme.typography.bodySmall)
            }
        }
        Column(
            Modifier.fillMaxWidth().background(
                Color.Black.copy(alpha = 0.55f)
            ).padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                stringResource(R.string.photo_zoom_hint),
                color = Color.White,
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { onSlideshow(if (slideshow == null) SLIDESHOW_SECONDS[1] else null) },
                    modifier = Modifier.testTag(PHOTO_SLIDESHOW_TAG),
                ) {
                    Text(
                        stringResource(
                            if (slideshow == null) R.string.photo_slideshow_start else R.string.photo_slideshow_stop
                        ),
                        color = Color.White,
                    )
                }
                SLIDESHOW_SECONDS.forEach { seconds ->
                    FilterChip(
                        selected = slideshow == seconds,
                        onClick = { onSlideshow(seconds) },
                        label = { Text(stringResource(R.string.photo_slideshow_seconds, seconds)) },
                    )
                }
            }
            ScreenRow(mirror)
        }
    }
}

@Composable
private fun ZoomableImage(entry: PhoneMediaEntry) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    fun clamped(raw: Offset, atScale: Float): Offset {
        val maxX = size.width * (atScale - 1f) / 2f
        val maxY = size.height * (atScale - 1f) / 2f
        return Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
    }
    AsyncImage(
        model = entry.uri,
        contentDescription = entry.title,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    scale = if (scale > 1f) 1f else DOUBLE_TAP_ZOOM
                    offset = Offset.Zero
                })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (event.changes.size > 1 || scale > 1f) {
                            scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                            offset = clamped(offset + pan, scale)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (scale <= 1f) offset = Offset.Zero
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}

const val PHOTO_VIEWER_TAG = "phone-media-photo-viewer"
const val PHOTO_CLOSE_TAG = "phone-media-photo-close"
const val PHOTO_SLIDESHOW_TAG = "phone-media-photo-slideshow"
