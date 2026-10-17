package de.nibsync.shapes

/** A clean geometric shape, in document millimetres. */
sealed interface Shape {
    data class Line(val start: Pt, val end: Pt) : Shape

    /** A line from [tail] to [tip] with a symmetric head of [headLength]. */
    data class Arrow(val tail: Pt, val tip: Pt, val headLength: Double) : Shape

    data class Triangle(val a: Pt, val b: Pt, val c: Pt) : Shape

    /** Rectangle (or square) centred at [center], rotated by [rotationRad]. */
    data class Rectangle(val center: Pt, val width: Double, val height: Double, val rotationRad: Double) : Shape {
        val corners: List<Pt>
            get() = listOf(
                Pt(-width / 2, -height / 2), Pt(width / 2, -height / 2),
                Pt(width / 2, height / 2), Pt(-width / 2, height / 2),
            ).map { center + Geometry.rotate(it, rotationRad) }
    }

    /** Ellipse (or circle) with radii [rx] along its rotated x axis and [ry]. */
    data class Ellipse(val center: Pt, val rx: Double, val ry: Double, val rotationRad: Double) : Shape
}
