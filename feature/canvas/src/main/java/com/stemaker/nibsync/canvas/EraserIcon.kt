package com.stemaker.nibsync.canvas

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.stemaker.nibsync.document.erase.EraserMode

/**
 * Eraser icons in the style of the Material icons: a tilted eraser block, over a whole wavy
 * stroke for the object eraser and over loose crumbs for the pixel eraser.
 */
internal fun eraserIcon(mode: EraserMode): ImageVector = when (mode) {
    EraserMode.OBJECT -> objectEraserIcon
    EraserMode.PIXEL -> pixelEraserIcon
}

private fun ImageVector.Builder.eraserBlock(): ImageVector.Builder = this
    .path(fill = SolidColor(Color.Black)) {
        // Sleeve.
        moveTo(14.4f, 2.3f)
        lineTo(21.7f, 9.6f)
        lineTo(16.8f, 14.5f)
        lineTo(9.5f, 7.2f)
        close()
    }
    .path(fill = SolidColor(Color.Black), fillAlpha = 0.45f) {
        // Rubber tip.
        moveTo(8.8f, 7.9f)
        lineTo(16.1f, 15.2f)
        lineTo(13.4f, 17.9f)
        lineTo(6.1f, 10.6f)
        close()
    }

private val objectEraserIcon: ImageVector by lazy {
    ImageVector.Builder(name = "EraserObject", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .eraserBlock()
        .path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round) {
            // One complete hand-drawn stroke.
            moveTo(2.5f, 20.5f)
            curveTo(5f, 16.5f, 7f, 23.5f, 10f, 20.5f)
            curveTo(12.5f, 18f, 14f, 22.5f, 16.5f, 20.5f)
        }
        .build()
}

private val pixelEraserIcon: ImageVector by lazy {
    ImageVector.Builder(name = "EraserPixel", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .eraserBlock()
        .path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
            // Loose crumbs: only parts are removed.
            moveTo(3f, 20.5f); lineTo(4f, 20.5f)
            moveTo(7f, 20.5f); lineTo(8f, 20.5f)
            moveTo(11f, 20.5f); lineTo(12f, 20.5f)
            moveTo(15f, 20.5f); lineTo(16f, 20.5f)
        }
        .build()
}
