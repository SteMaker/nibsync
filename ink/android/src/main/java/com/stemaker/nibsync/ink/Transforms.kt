package com.stemaker.nibsync.ink

import android.graphics.Matrix
import com.stemaker.nibsync.model.geometry.ScaleTranslate

fun ScaleTranslate.toMatrix(out: Matrix = Matrix()): Matrix = out.apply { setValues(toMatrixValues()) }
