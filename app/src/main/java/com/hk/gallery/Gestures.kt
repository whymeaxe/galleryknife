package com.hk.gallery

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Tap opens (or toggles while selecting).
 *  - Long-press starts selecting.
 *  - While selecting, a new sideways swipe (even after lifting your finger) keeps selecting; a vertical swipe still scrolls.
 *  - Sliding along a row selects photo by photo. Moving the finger into another row selects that whole row,
 *    and every row you pass on the way.
 *  - Near the top or bottom edge the grid scrolls.
 */
fun Modifier.mediaGestures(
    state: LazyGridState, sel: Sel, byId: Map<Long, Media>, onOpen: (Media) -> Unit,
): Modifier = composed {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    pointerInput(state, sel, byId) {
        fun itemAt(p: Offset): Media? {
            val info = state.layoutInfo.visibleItemsInfo.firstOrNull { i ->
                p.x >= i.offset.x && p.x < i.offset.x + i.size.width && p.y >= i.offset.y && p.y < i.offset.y + i.size.height
            } ?: return null
            return (info.key as? Long)?.let { byId[it] }
        }

        /** Visible photos grouped into rows, top to bottom. */
        fun rows(): List<List<Media>> =
            state.layoutInfo.visibleItemsInfo.mapNotNull { i -> (i.key as? Long)?.let { byId[it] }?.let { m -> i.offset.y to m } }
                .groupBy({ it.first }, { it.second }).toList().sortedBy { it.first }.map { it.second }

        suspend fun AwaitPointerEventScope.dragSelect(id: PointerId, first: Media) {
            val adding = !sel.has(first.id)
            fun apply(m: Media) { if (adding != sel.has(m.id)) sel.toggle(m) }
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            apply(first)
            var lastId = first.id
            var lastRowFirst: Long = rows().firstOrNull { r -> r.any { it.id == first.id } }?.first()?.id ?: first.id
            var pressed = true
            while (pressed) {
                val ev = awaitPointerEvent()
                val c = ev.changes.firstOrNull { it.id == id }
                if (c == null || !c.pressed) { pressed = false; continue }
                val under = itemAt(c.position)
                if (under != null && under.id != lastId) {
                    val all = rows()
                    val curIdx = all.indexOfFirst { r -> r.any { it.id == under.id } }
                    val lastIdx = all.indexOfFirst { r -> r.first().id == lastRowFirst }
                    if (curIdx >= 0 && lastIdx >= 0 && curIdx != lastIdx) {
                        // Moved into another row: take the whole row, and any rows skipped on the way.
                        val range = if (curIdx > lastIdx) (lastIdx + 1)..curIdx else curIdx until lastIdx
                        for (ri in range) all[ri].forEach { apply(it) }
                        lastRowFirst = all[curIdx].first().id
                    } else {
                        apply(under)
                        if (curIdx >= 0) lastRowFirst = all[curIdx].first().id
                    }
                    lastId = under.id
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                val h = state.layoutInfo.viewportSize.height
                val dy = when { c.position.y < 160f -> -34f; c.position.y > h - 260f -> 34f; else -> 0f }
                if (dy != 0f) scope.launch { state.scrollBy(dy) }
                c.consume()
            }
        }

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (sel.active) {
                // Already selecting: a sideways swipe selects, a vertical swipe scrolls, a tap toggles.
                var sideways = false
                val moved = awaitTouchSlopOrCancellation(down.id) { change, over ->
                    if (abs(over.x) > abs(over.y)) { change.consume(); sideways = true }
                }
                if (moved != null && sideways) {
                    itemAt(down.position)?.let { dragSelect(down.id, it) }
                } else if (moved == null) {
                    val up = currentEvent.changes.firstOrNull { it.id == down.id }
                    if (up != null && !up.pressed && !up.isConsumed) itemAt(down.position)?.let { sel.toggle(it) }
                }
            } else {
                val lp = awaitLongPressOrCancellation(down.id)
                if (lp != null) {
                    itemAt(down.position)?.let { dragSelect(down.id, it) }
                } else {
                    val up = currentEvent.changes.firstOrNull { it.id == down.id }
                    if (up != null && !up.pressed && !up.isConsumed) itemAt(down.position)?.let { onOpen(it) }
                }
            }
        }
    }
}
