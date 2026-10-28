package io.github.strongsand.lshell

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class CardEditorSessionTest {
    @Test fun draggingAnotherCardStillRequiresAHoldSoOrdinaryScrollingCanWin() {
        val state = CardArrangement(listOf("a", "b"), emptySet(), setOf("a", "b")) { _, _ -> }
        assertFalse(state.canDragDirectly("a"))
        state.select("a")
        assertTrue(state.canDragDirectly("a")); assertFalse(state.canDragDirectly("b"))
        state.select("b")
        assertTrue(state.canDragDirectly("b")); assertFalse(state.canDragDirectly("a"))
        state.finishEditing(); assertFalse(state.canDragDirectly("b"))
    }
    private fun tap(outside: Boolean = true) = CardOutsideTap(Offset(150f, 20f), 100L, 8f, 500L, 7L, outside)

    @Test fun shortOutsideTapCompletesButInsideTapDoesNot() {
        assertTrue(tap().complete(180L, 7L, false))
        assertFalse(tap(false).complete(180L, 7L, false))
    }

    @Test fun scrollAndConsumedMovementNeverTurnIntoAReleaseTap() {
        val scroll = tap(); scroll.observe(Offset(150f, 60f), 1, false)
        scroll.observe(Offset(150f, 20f), 1, false)
        assertFalse(scroll.complete(200L, 7L, false))
        val claimed = tap(); claimed.observe(Offset(151f, 20f), 1, true)
        assertFalse(claimed.complete(200L, 7L, false))
    }

    @Test fun normalFingerJitterIsATapWithoutDependingOnDownOrUpConsumption() {
        val tap = tap(); tap.observe(Offset(153f, 21f), 1, false)
        assertTrue(tap.complete(200L, 7L, false))
    }

    @Test fun longHoldMultitouchAndBusyResizeAreNotOutsideTaps() {
        assertFalse(tap().complete(700L, 7L, false))
        assertFalse(tap().complete(200L, 7L, true))
        val two = tap(); two.observe(Offset(150f, 20f), 2, false)
        assertFalse(two.complete(200L, 7L, false))
    }

    @Test fun completedDragOrResizeCannotDismissTheNewSelectionOnTheSameUpEvent() {
        assertFalse(tap().complete(200L, 8L, false))
        assertFalse(tap().complete(200L, 9L, false))
    }

    @Test fun finishingAfterDropPersistsOnlyOnceAndDoesNotUndoOrderOrSize() {
        var writes = 0
        val state = CardArrangement(listOf("a", "b"), emptySet(), setOf("a", "b")) { _, _ -> writes++ }
        state.select("b"); state.previewMove(state.order, "b", "a"); state.previewSize("b", true)
        state.commit(); assertEquals(1, writes)
        state.finishEditing(); state.finishEditing()
        assertEquals(1, writes); assertEquals(listOf("b", "a"), state.order)
        assertEquals(setOf("b"), state.compact); assertNull(state.selected); assertFalse(state.editing)
    }

    @Test fun switchingEditorOrNavigationFinishesTheCorrectOwnerAndReleasesReferences() {
        val a = CardArrangement(listOf("a"), emptySet(), setOf("a")) { _, _ -> }
        val b = CardArrangement(listOf("b"), emptySet(), setOf("b")) { _, _ -> }
        val session = CardEditorSession(); var aFinished = 0; var bFinished = 0
        a.select("a"); session.bind(a, { Rect(0f, 0f, 100f, 100f) }, { false }) { a.finishEditing(); aFinished++ }
        b.select("b"); session.bind(b, { Rect(100f, 0f, 200f, 100f) }, { false }) { b.finishEditing(); bFinished++ }
        assertEquals(1, aFinished); assertFalse(a.editing); assertSame(b, session.active)
        session.release(a); assertSame(b, session.active)
        session.finish(); session.finish()
        assertEquals(1, bFinished); assertNull(session.active); assertNull(session.selectedBounds())
    }

    @Test fun measuredIndicatorWidthsHandleTranslatedWordsAndLargeFonts() {
        for (longest in listOf(90f, 98f, 104f)) {
            val widths = listOf(longest, 65f, 74f, 58f)
            assertEquals(2, CardLayoutPolicy.indicatorColumns(330f, widths))
            assertEquals(4, CardLayoutPolicy.indicatorColumns(450f, widths))
            assertEquals(1, CardLayoutPolicy.indicatorColumns(330f, widths.map { it * 2f }))
        }
    }
}
