package com.dewijones92.totum.ui.common

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import com.dewijones92.totum.common.Diag
import kotlin.math.roundToInt

internal fun hiddenAfter(hidden: Float, dy: Float, travelPx: Float): Float =
    if (travelPx <= 0f) hidden else (hidden - dy / travelPx).coerceIn(0f, 1f)

internal fun headersFixed(touchExploration: Boolean, animatorScale: Float): Boolean =
    touchExploration || animatorScale == 0f

@Stable
internal class HeaderScroll(private val name: String) {
    var hidden by mutableFloatStateOf(0f)
        private set

    var fixed: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) reveal("headers are fixed")
        }

    private val registered = LinkedHashMap<Any, Int>()
    private var edge = Edge.SHOWN

    val travelPx: Int get() = registered.values.sum()

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            Offset(0f, scrolled(available.y))
    }

    fun scrolled(dy: Float): Float {
        val travel = travelPx.toFloat()
        if (fixed || dy == 0f || travel <= 0f) return 0f
        val before = hidden
        val after = hiddenAfter(before, dy, travel)
        if (after == before) return 0f
        hidden = after
        noteEdge(after, travel)
        return -(after - before) * travel
    }

    fun reveal(why: String) {
        if (hidden == 0f) return
        Diag.log("header", "dewidebug $name header revealed: $why (was ${(hidden * PERCENT).roundToInt()}% hidden)")
        hidden = 0f
        edge = Edge.SHOWN
    }

    fun register(owner: Any, heightPx: Int) {
        if (registered.put(owner, heightPx) != heightPx) {
            Diag.log("header", "dewidebug $name header pieces=${registered.size} travel=${travelPx}px")
        }
    }

    fun unregister(owner: Any) {
        if (registered.remove(owner) != null) {
            Diag.log("header", "dewidebug $name header pieces=${registered.size} travel=${travelPx}px (one left)")
            if (registered.isEmpty() && hidden != 0f) hidden = 0f
        }
    }

    private fun noteEdge(after: Float, travel: Float) {
        val now = when {
            after <= 0f -> Edge.SHOWN
            after >= 1f -> Edge.HIDDEN
            else -> Edge.SLIDING
        }
        if (now == edge) return
        edge = now
        when (now) {
            Edge.HIDDEN -> Diag.log(
                "header",
                "dewidebug $name header slid away on scrolling down (travel=${travel.roundToInt()}px)"
            )
            Edge.SHOWN -> Diag.log("header", "dewidebug $name header back in full on scrolling up")
            Edge.SLIDING -> Unit
        }
    }

    private enum class Edge { SHOWN, SLIDING, HIDDEN }

    private companion object {
        const val PERCENT = 100
    }
}

internal val LocalHeaderScroll = staticCompositionLocalOf<HeaderScroll?> { null }

internal val LocalHeadersFixed = staticCompositionLocalOf<Boolean?> { null }

@Composable
internal fun HeaderScrollHost(name: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val system = rememberHeadersFixed()
    val fixed = LocalHeadersFixed.current ?: system
    val scroll = remember(name) { HeaderScroll(name) }
    SideEffect { scroll.fixed = fixed }
    LaunchedEffect(name, fixed) {
        val now = if (fixed) "stay fixed (TalkBack or animations off)" else "slide away on scrolling down"
        Diag.log("header", "dewidebug $name headers $now")
    }
    CompositionLocalProvider(LocalHeaderScroll provides scroll) {
        Box(modifier.fillMaxSize().nestedScroll(scroll.connection)) { content() }
    }
}

@Composable
internal fun RevealHeaderWhen(key: Any?, why: String) {
    val scroll = LocalHeaderScroll.current ?: return
    DisposableEffect(scroll, key) {
        scroll.reveal(why)
        onDispose {}
    }
}

@Composable
internal fun Modifier.scrollsAway(): Modifier {
    val scroll = LocalHeaderScroll.current ?: return this
    return clipToBounds()
        .then(ScrollsAwayElement(scroll))
        .pointerInput(scroll) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                scroll.reveal("the header was touched")
            }
        }
}

private data class ScrollsAwayElement(val scroll: HeaderScroll) : ModifierNodeElement<ScrollsAwayNode>() {
    override fun create() = ScrollsAwayNode(scroll)

    override fun update(node: ScrollsAwayNode) {
        node.scroll = scroll
    }
}

private class ScrollsAwayNode(var scroll: HeaderScroll) : Modifier.Node(), LayoutModifierNode {
    override fun onDetach() {
        scroll.unregister(this)
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        scroll.register(this@ScrollsAwayNode, placeable.height)
        val shown = ((1f - scroll.hidden) * placeable.height).roundToInt()
        return layout(placeable.width, shown) { placeable.place(0, shown - placeable.height) }
    }
}

@Composable
internal fun rememberHeadersFixed(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var touchExploration by remember { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { touchExploration = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    var animatorScale by remember { mutableFloatStateOf(animatorScaleOf(context)) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                animatorScale = animatorScaleOf(context)
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return headersFixed(touchExploration, animatorScale)
}

private fun animatorScaleOf(context: Context): Float =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
