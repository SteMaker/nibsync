package de.nibsync.ink

import android.graphics.Matrix
import de.nibsync.model.geometry.ScaleTranslate

fun ScaleTranslate.toMatrix(out: Matrix = Matrix()): Matrix = out.apply { setValues(toMatrixValues()) }
