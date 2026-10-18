package com.stemaker.nibsync.model

val TEST_DEVICE = DeviceId("device-a")
val PEN = BrushSpec(BrushFamily.BALLPOINT, 0xff000000.toInt(), 0.5f)

fun testStroke(
    id: String = "s1",
    vararg xy: Pair<Float, Float> = arrayOf(0f to 0f, 10f to 10f),
    brush: BrushSpec = PEN,
): Stroke = Stroke(
    id = StrokeId(id),
    layer = brush.family.defaultLayer,
    brush = brush,
    tool = InputTool.STYLUS,
    points = xy.mapIndexed { i, (x, y) -> StrokePoint(x, y, i * 10L, pressure = 0.5f) },
    createdAtEpochMs = 1_000L,
    author = TEST_DEVICE,
)
