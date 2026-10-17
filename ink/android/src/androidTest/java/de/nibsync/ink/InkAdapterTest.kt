package de.nibsync.ink

import androidx.ink.brush.InputToolType
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import de.nibsync.model.BrushFamily
import de.nibsync.model.BrushSpec
import de.nibsync.model.DeviceId
import de.nibsync.model.InputTool
import de.nibsync.model.Layer
import de.nibsync.model.Stroke
import de.nibsync.model.StrokeId
import de.nibsync.model.StrokePoint
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a device because Ink's geometry engine is a native library. */
@RunWith(AndroidJUnit4::class)
class InkAdapterTest {

    private fun stroke(family: BrushFamily, tool: InputTool = InputTool.STYLUS, withPressure: Boolean = true) = Stroke(
        id = StrokeId("s"),
        layer = family.defaultLayer,
        brush = BrushSpec(family, 0xff1a3fa8.toInt(), 1f),
        tool = tool,
        points = List(20) { i ->
            StrokePoint(
                x = 10f + i,
                y = 20f + (i % 3) * 0.5f,
                timeMs = i * 8L,
                pressure = if (withPressure) 0.2f + i * 0.03f else StrokePoint.NO_VALUE,
                tiltRad = if (withPressure) 0.4f else StrokePoint.NO_VALUE,
                orientationRad = if (withPressure) 1.2f else StrokePoint.NO_VALUE,
            )
        },
        createdAtEpochMs = 0,
        author = DeviceId("d"),
    )

    @Test
    fun everyFamilyMapsToABrush() {
        for (family in BrushFamily.entries) {
            val brush = BrushMapper.toInk(BrushSpec(family, 0x80ff0000.toInt(), 2.5f))
            assertThat(brush.size).isEqualTo(2.5f)
            assertThat(brush.colorIntArgb).isEqualTo(0x80ff0000.toInt())
            assertThat(brush.epsilon).isEqualTo(BrushMapper.EPSILON_MM)
        }
    }

    @Test
    fun familiesAreDistinct() {
        val families = BrushFamily.entries.map { BrushMapper.family(it) }
        assertThat(families.toSet()).hasSize(BrushFamily.entries.size)
    }

    @Test
    fun domainToInkKeepsAllInput() {
        val domain = stroke(BrushFamily.FOUNTAIN_PEN)
        val ink = StrokeConverter.toInk(domain)
        assertThat(ink.inputs.size).isEqualTo(domain.points.size)
        assertThat(ink.inputs.getToolType()).isEqualTo(InputToolType.STYLUS)
        assertThat(ink.inputs.hasPressure()).isTrue()
        assertThat(ink.inputs.getStrokeUnitLengthCm()).isWithin(1e-6f).of(0.1f)
    }

    @Test
    fun roundTripPreservesPoints() {
        for (family in BrushFamily.entries) {
            for (withPressure in listOf(true, false)) {
                val domain = stroke(family, withPressure = withPressure)
                val draft = StrokeConverter.toDraft(StrokeConverter.toInk(domain), domain.brush)
                assertThat(draft.tool).isEqualTo(InputTool.STYLUS)
                assertThat(draft.brush).isEqualTo(domain.brush)
                assertThat(draft.layer).isEqualTo(domain.layer)
                assertThat(draft.points).hasSize(domain.points.size)
                draft.points.zip(domain.points).forEach { (a, b) ->
                    assertThat(a.x).isWithin(1e-4f).of(b.x)
                    assertThat(a.y).isWithin(1e-4f).of(b.y)
                    assertThat(a.timeMs).isEqualTo(b.timeMs)
                    assertThat(a.pressure).isWithin(1e-4f).of(b.pressure)
                    assertThat(a.tiltRad).isWithin(1e-4f).of(b.tiltRad)
                    assertThat(a.orientationRad).isWithin(1e-4f).of(b.orientationRad)
                }
            }
        }
    }

    @Test
    fun touchToolIsPreserved() {
        val domain = stroke(BrushFamily.BALLPOINT, tool = InputTool.TOUCH, withPressure = false)
        assertThat(StrokeConverter.toDraft(StrokeConverter.toInk(domain), domain.brush).tool).isEqualTo(InputTool.TOUCH)
    }

    @Test
    fun meshCoversTheInputPoints() {
        val domain = stroke(BrushFamily.BALLPOINT)
        val box = checkNotNull(StrokeConverter.toInk(domain).shape.computeBoundingBox())
        val bounds = domain.bounds
        // The mesh lies within the stroke bounds (plus a small tolerance for the round caps).
        assertThat(box.xMin).isWithin(0.6f).of(bounds.left)
        assertThat(box.xMax).isWithin(0.6f).of(bounds.right)
        assertThat(box.yMin).isAtLeast(bounds.top - 0.6f)
        assertThat(box.yMax).isAtMost(bounds.bottom + 0.6f)
    }

    @Test
    fun invalidInputsAreDroppedNotFatal() {
        val domain = stroke(BrushFamily.BALLPOINT)
        // Time running backwards is rejected by Ink.
        val broken = domain.copy(points = domain.points + StrokePoint(99f, 99f, 0))
        val ink = StrokeConverter.toInk(broken)
        assertThat(ink.inputs.size).isEqualTo(domain.points.size)
    }

    @Test
    fun highlighterUsesMarkerLayer() {
        assertThat(stroke(BrushFamily.HIGHLIGHTER).layer).isEqualTo(Layer.MARKER)
    }

    @Test
    fun cacheBuildsEachStrokeOnce() {
        var conversions = 0
        val cache = InkStrokeCache { conversions++; StrokeConverter.toInk(it) }
        val a = stroke(BrushFamily.BALLPOINT)
        val b = a.copy(id = StrokeId("b"))
        cache[a]; cache[a]; cache[b]
        assertThat(conversions).isEqualTo(2)
        cache.retainOnly(setOf(b.id))
        assertThat(cache.size).isEqualTo(1)
        cache[a]
        assertThat(conversions).isEqualTo(3)
    }

    /** Height of the mesh of a horizontal 1 mm stroke written with constant [pressure]. */
    private fun widthAt(family: BrushFamily, pressure: Float): Float {
        val domain = Stroke(
            id = StrokeId("w"),
            layer = family.defaultLayer,
            brush = BrushSpec(family, 0xff000000.toInt(), 1f),
            tool = InputTool.STYLUS,
            points = List(40) { i -> StrokePoint(10f + i, 20f, i * 8L, pressure = pressure) },
            createdAtEpochMs = 0,
            author = DeviceId("d"),
        )
        val box = checkNotNull(StrokeConverter.toInk(domain).shape.computeBoundingBox())
        return box.yMax - box.yMin
    }

    @Test
    fun fountainPenWidthFollowsRealisticPressure() {
        // Values measured with the HONOR Magic-Pencil 3: light ~0.05, firm ~0.3.
        val light = widthAt(BrushFamily.FOUNTAIN_PEN, 0.05f)
        val normal = widthAt(BrushFamily.FOUNTAIN_PEN, 0.25f)
        val firm = widthAt(BrushFamily.FOUNTAIN_PEN, 0.45f)
        assertThat(normal).isGreaterThan(light * 1.5f)
        assertThat(firm).isGreaterThan(normal * 1.3f)
        assertThat(normal).isWithin(0.15f).of(1f) // nominal width at typical pressure
    }

    @Test
    fun ballpointIgnoresPressure() {
        assertThat(widthAt(BrushFamily.BALLPOINT, 0.45f)).isWithin(0.02f).of(widthAt(BrushFamily.BALLPOINT, 0.05f))
    }
}
