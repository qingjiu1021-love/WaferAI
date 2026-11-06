package com.example.waferai.ui.theme

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private val pageSpring = spring(dampingRatio = 1f, stiffness = 305f, visibilityThreshold = 0.002f)

@Stable
internal class TabNavigation<T : Any>(
    private val selection: MutableState<T>,
    val tabs: List<T>,
    private val scope: CoroutineScope
) {
    val page: State<T> get() = selection
    private val animation = Animatable(0f)
    val position get() = animation.value
    var track by mutableStateOf<Map<T, Float>>(
        if (selection.value in tabs) mapOf(selection.value to 0f) else emptyMap()
    )
        private set
    private val sliding = mutableStateOf(false)
    val moving: State<Boolean> get() = sliding
    private var motionJob: Job? = null

    fun navigate(target: T, animate: Boolean = ValueAnimator.areAnimatorsEnabled()) {
        val previous = selection.value
        if (previous == target) return
        val from = tabs.indexOf(previous)
        val to = tabs.indexOf(target)
        val current = position
        if (!animate || from == -1 || to == -1 || track.isEmpty()) {
            motionJob?.cancel()
            track = if (to == -1) emptyMap() else mapOf(target to current)
            sliding.value = false
            selection.value = target
            return
        }

        // Keep the pages that are still in view. A reversal can reuse their
        // existing positions and the running spring's velocity.
        val visible = track.filterValues { abs(it - current) < 1f }.ifEmpty { track }
        val destination = visible[target] ?: if (to > from) visible.values.max() + 1f else visible.values.min() - 1f
        track = visible + (target to destination)
        selection.value = target
        sliding.value = true
        motionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            animation.animateTo(destination, pageSpring)
            if (selection.value == target) {
                track = mapOf(target to destination)
                sliding.value = false
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun <T : Any> PrimaryTabHost(
    navigation: TabNavigation<T>,
    motion: TabMotion,
    prepareTabs: Boolean,
    modifier: Modifier,
    content: @Composable (T) -> Unit
) {
    val page by navigation.page
    val tabs = navigation.tabs
    var prepared by remember(navigation) { mutableStateOf(navigation.track.keys.toSet()) }
    var measuring by remember { mutableStateOf<T?>(null) }
    val warmLayer = if (measuring != null) rememberGraphicsLayer() else null
    val moving by navigation.moving
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val currentContent = rememberUpdatedState(content)
    val tabBodies = remember(navigation) {
        tabs.associateWith { tab -> movableContentOf { currentContent.value(tab) } }
    }

    LaunchedEffect(page) {
        if (page in tabs && page !in prepared) prepared = prepared + page
    }

    // Warm one tab at a time after the first draw or navigation settles. Only a visible
    // page is placed, so hidden tabs retain state without drawing each frame.
    LaunchedEffect(prepareTabs, page, moving, tabs) {
        if (!prepareTabs || moving || page !in tabs) return@LaunchedEffect
        try {
            for (tab in tabs) {
                if (tab in prepared) continue
                delay(180)
                measuring = tab
                prepared = prepared + tab
                delay(40)
            }
        } finally {
            measuring = null
        }
    }

    val roots = tabs.filter { it in prepared || it == page || it in navigation.track }
    val slots = if (page in tabs) roots else roots + page
    Layout(
        modifier = modifier,
        content = {
            slots.forEach { slot ->
                key(slot) {
                    val interaction = Modifier.semantics {
                        if (navigation.page.value != slot) invisibleToUser()
                    }.pointerInput(navigation, slot) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (navigation.page.value != slot) event.changes.forEach { it.consume() }
                            }
                        }
                    }
                    val prepaint = if (warmLayer != null && slot == measuring && slot !in navigation.track) Modifier.drawWithContent {
                        // Prepare text and drawing caches before the first visit, without
                        // drawing this page to the window or allocating a full-page texture.
                        if (drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
                            warmLayer.record { this@drawWithContent.drawContent() }
                        }
                    } else Modifier
                    Box(Modifier.fillMaxSize().then(interaction).then(prepaint)) {
                        val body = tabBodies[slot]
                        if (body != null) body() else currentContent.value(slot)
                    }
                }
            }
        }
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val placing = navigation.track
        val first = placing.values.minOrNull() ?: 0f
        val last = placing.values.maxOrNull() ?: 0f
        val detail = if (page in tabs) null else page
        val measured = slots.zip(measurables).mapNotNull { (slot, measurable) ->
            if (slot in placing || slot == detail || slot == measuring) {
                slot to measurable.measure(constraints)
            } else null
        }
        layout(width, height) {
            measured.forEach { (slot, item) ->
                if (slot == detail) {
                    item.place(0, 0)
                } else {
                    val anchor = placing[slot]
                    if (anchor != null && placing.size == 1) {
                        item.place(0, 0)
                    } else if (anchor != null) {
                        item.placeWithLayer(0, 0, zIndex = if (slot == page) 1f else 0f) {
                            val offset = anchor - navigation.position.coerceIn(first, last)
                            if (motion == TabMotion.FADE) {
                                alpha = (1f - abs(offset)).coerceIn(0f, 1f)
                            } else {
                                alpha = if (abs(offset) >= 1f) 0f else 1f
                                translationX = offset * width * (if (rtl) -1f else 1f)
                            }
                        }
                    } else if (slot == measuring) {
                        item.place(0, 0, zIndex = -1f)
                    }
                }
            }
        }
    }
}
