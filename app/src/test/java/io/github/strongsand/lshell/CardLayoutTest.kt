package io.github.strongsand.lshell

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class CardLayoutTest {
    @Test fun resizeUsesSemanticBreakpointsAndHysteresis() {
        assertTrue(CardLayoutPolicy.resizeCompact(160f, 340f, true))
        assertFalse(CardLayoutPolicy.resizeCompact(300f, 340f, true))
        assertTrue(CardLayoutPolicy.resizeCompact(220f, 340f, false))
        assertFalse(CardLayoutPolicy.resizeCompact(255f, 340f, false))
        assertTrue(CardLayoutPolicy.resizeCompact(255f, 340f, true))
        assertFalse(CardLayoutPolicy.resizeCompact(Float.NaN, 340f, false))
    }

    @Test fun handlePreviewWritesOnlyOnDropAndCancellationRestoresSizes() {
        var writes = 0
        val state = CardArrangement(listOf("a", "b"), emptySet(), setOf("a", "b")) { _, _ -> writes++ }
        state.select("a")
        assertEquals("a", state.selected); assertTrue(state.editing)
        state.previewSize("a", true); state.previewSize("a", false); state.previewSize("a", true)
        assertEquals(0, writes)
        state.restoreSizes(emptySet()); assertTrue(state.compact.isEmpty()); assertEquals(0, writes)
        state.previewSize("a", true); state.commit(); assertEquals(1, writes)
        state.finishEditing(); assertNull(state.selected); assertFalse(state.editing)
    }
    private val ids = listOf("download", "upload", "latency", "signal", "power")

    @Test fun twoCompactCardsShareARowAndFullWidthStartsItsOwnRow() {
        val slots = CardLayoutPolicy.slots(ids, ids.take(4).toSet(), 2)
        assertEquals(listOf(0, 0, 1, 1, 2), slots.map { it.row })
        assertEquals(listOf(0, 1, 0, 1, 0), slots.map { it.column })
        assertEquals(listOf(1, 1, 1, 1, 2), slots.map { it.span })
        val mixed = CardLayoutPolicy.slots(ids.take(3), setOf("download", "latency"), 2)
        assertEquals(listOf(0, 1, 2), mixed.map { it.row })
    }

    @Test fun narrowAndEnlargedFontFallbackDoesNotDestroyCompactPreference() {
        assertEquals(2, CardLayoutPolicy.columns(340f, 1f))
        assertEquals(1, CardLayoutPolicy.columns(280f, 1f))
        assertEquals(1, CardLayoutPolicy.columns(340f, 1.5f))
        val compact = ids.toSet()
        assertTrue(CardLayoutPolicy.slots(ids, compact, 1).all { it.span == 1 && it.column == 0 })
        assertEquals(compact, CardLayoutPolicy.compactIds(compact.joinToString(",").split(','), compact))
    }

    @Test fun statusStripFitsMeasuredContentInsteadOfSplittingLabels() {
        assertEquals(4, CardLayoutPolicy.indicatorColumns(348f, List(4) { 80f }))
        assertEquals(2, CardLayoutPolicy.indicatorColumns(348f, listOf(98f, 64f, 74f, 62f)))
        assertEquals(3, CardLayoutPolicy.indicatorColumns(348f, List(3) { 80f }))
        assertEquals(2, CardLayoutPolicy.indicatorColumns(288f, List(4) { 112f }))
        assertEquals(1, CardLayoutPolicy.indicatorColumns(100f, List(4) { 180f }))
    }

    @Test fun lastCardCanReachFirstAndBackWithoutDroppingOrDuplicatingIds() {
        val first = CardLayoutPolicy.moveTo(ids, ids, "power", "download")
        assertEquals(listOf("power", "download", "upload", "latency", "signal"), first)
        assertEquals(ids, CardLayoutPolicy.moveTo(first, first, "power", "signal"))
        assertEquals(ids, CardLayoutPolicy.moveTo(ids, ids, "unknown", "download"))
    }

    @Test fun filteredCardsStayInTheirSlotsDuringLongMoves() {
        assertEquals(listOf("power", "upload", "download", "signal", "latency"),
            CardLayoutPolicy.moveTo(ids, listOf("download", "latency", "power"), "power", "download"))
    }

    @Test fun repeatedMixedReordersAndSizesKeepTheSameIdentitySet() {
        val random = Random(4096)
        var order = ids
        repeat(500) {
            order = CardLayoutPolicy.moveTo(order, order, ids.random(random), ids.random(random))
            val sizes = ids.filter { random.nextBoolean() }.toSet()
            val slots = CardLayoutPolicy.slots(order, sizes, 2)
            assertEquals(ids.toSet(), slots.map { it.id }.toSet())
            assertEquals(ids.size, slots.size)
            slots.groupBy { it.row }.values.forEach { row -> assertTrue(row.sumOf { it.span } <= 2) }
        }
    }

    @Test fun onlyEligibleCompactIdsSurviveSavedRoundTrip() {
        val decoded = CardLayoutPolicy.compactIds("power,unknown,power,download".split(','), setOf("download", "upload"))
        assertEquals(setOf("download"), decoded)
        assertTrue(CardLayoutPolicy.compactIds(listOf(""), ids.toSet()).isEmpty())
    }

    @Test fun edgeScrollingWorksBothWaysIsBoundedAndDoesNotNeedNewPointerEvents() {
        assertEquals(0f, CardLayoutPolicy.edgeSpeed(400f, 100f, 800f, 1f), .001f)
        assertTrue(CardLayoutPolicy.edgeSpeed(110f, 100f, 800f, 1f) < 0)
        assertTrue(CardLayoutPolicy.edgeSpeed(790f, 100f, 800f, 1f) > 0)
        assertEquals(-220f, CardLayoutPolicy.edgeSpeed(-500f, 100f, 800f, 1f), .001f)
        assertEquals(220f, CardLayoutPolicy.edgeSpeed(1500f, 100f, 800f, 1f), .001f)
        val speed = CardLayoutPolicy.edgeSpeed(790f, 100f, 800f, 1f)
        assertEquals(speed, (1..60).sumOf { (speed / 60).toDouble() }.toFloat(), .01f)
        assertEquals(0f, CardLayoutPolicy.edgeSpeed(Float.NaN, 100f, 800f, 1f), 0f)
        assertEquals(0f, CardLayoutPolicy.edgeSpeed(110f, 100f, 100f, 1f), 0f)
    }

    @Test fun previewDoesNotWritePreferencesAndDropCommitsOnlyFinalOrder() {
        val writes = mutableListOf<Pair<List<String>, Set<String>>>()
        val state = CardArrangement(ids, emptySet(), ids.toSet()) { order, compact -> writes += order to compact }
        state.previewMove(ids, "power", "latency")
        state.previewMove(ids, "power", "download")
        assertTrue(writes.isEmpty())
        state.commit()
        assertEquals(1, writes.size)
        assertEquals("power", writes.single().first.first())
    }

    @Test fun autoScrollStopsAtEditableSectionEdgesRatherThanRecyclingTheDraggedSection() {
        assertEquals(-4f, CardLayoutPolicy.boundedScroll(-8f, 96f, 1200f, 100f, 800f), 0f)
        assertEquals(0f, CardLayoutPolicy.boundedScroll(-8f, 110f, 1200f, 100f, 800f), 0f)
        assertEquals(3f, CardLayoutPolicy.boundedScroll(8f, -400f, 803f, 100f, 800f), 0f)
        assertEquals(0f, CardLayoutPolicy.boundedScroll(8f, 100f, 790f, 100f, 800f), 0f)
    }

    @Test fun cancelRestoresOrderAndResizePersistsSemanticSizeWithoutChangingIdentity() {
        val writes = mutableListOf<Pair<List<String>, Set<String>>>()
        val state = CardArrangement(ids, emptySet(), setOf("download")) { order, compact -> writes += order to compact }
        state.previewMove(ids, "power", "download")
        state.restore(ids)
        assertEquals(ids, state.order)
        assertTrue(writes.isEmpty())
        state.toggleSize("unknown")
        assertTrue(writes.isEmpty())
        state.toggleSize("download")
        assertEquals(setOf("download"), writes.last().second)
        state.toggleSize("download")
        assertTrue(writes.last().second.isEmpty())
        assertEquals(ids, state.order)
    }

    @Test fun accessibleMovesUseTheSameVisibleOrderAndPersistWithoutDragging() {
        var saved = ids
        val state = CardArrangement(ids, emptySet(), ids.toSet()) { order, _ -> saved = order }
        state.accessibleMove(ids, "upload", -1)
        assertEquals(listOf("upload", "download", "latency", "signal", "power"), saved)
    }

    @Test fun dragFeedbackRespectsDisabledAndSubtlePreferences() {
        for (event in listOf(DashFeedback.DRAG_STARTED, DashFeedback.DRAG_SNAP, DashFeedback.DRAG_COMPLETED)) {
            assertNull(hapticGesture(event, HapticLevel.OFF))
            assertEquals(HapticGesture.SOFT_TICK, hapticGesture(event, HapticLevel.SUBTLE))
        }
        assertEquals(HapticGesture.START, hapticGesture(DashFeedback.DRAG_STARTED, HapticLevel.STANDARD))
        assertEquals(HapticGesture.END, hapticGesture(DashFeedback.DRAG_COMPLETED, HapticLevel.STANDARD))
    }
}
