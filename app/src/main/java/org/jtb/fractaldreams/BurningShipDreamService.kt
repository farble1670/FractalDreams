package org.jtb.fractaldreams

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlin.math.abs
import kotlin.random.Random

class BurningShipDreamService : FractalDreamService() {

  override fun createFractalView(context: Context): FractalView {
    return BurningShipView(context, serviceScope)
  }

  private inner class BurningShipView(context: Context, serviceScope: CoroutineScope) : FractalView(context, serviceScope) {
    override val maxIterations = 64
    override val precisionLimit = 1.0E-8

    override fun getInitialCoordinates(isPortrait: Boolean, width: Int, height: Int): DoubleArray {
      val centerX = -1.77
      val cWidthInitial = 0.2
      val cHeightInitial = if (isPortrait) cWidthInitial * height / width else cWidthInitial * width / height
      val cXminInitial = centerX - cWidthInitial / 2.0
      val centerY = -0.05
      val cYminInitial = centerY - cHeightInitial / 2.0
      return doubleArrayOf(cXminInitial, cYminInitial, cWidthInitial, cHeightInitial)
    }

    private fun iteratePixel(zx: Double, zy: Double): Int {
      val localMaxIterations = maxIterations
      var z_real = 0.0
      var z_imag = 0.0
      var i = 0
      while (i < localMaxIterations && z_real * z_real + z_imag * z_imag < ESCAPE_RADIUS_SQUARED) {
        val z_real_abs = abs(z_real)
        val z_imag_abs = abs(z_imag)
        val z_real_next = z_real_abs * z_real_abs - z_imag_abs * z_imag_abs + zx
        val z_imag_next = 2.0 * z_real_abs * z_imag_abs + zy
        z_real = z_real_next
        z_imag = z_imag_next
        i++
      }
      return i
    }

    override fun searchZoomPoint(
      width: Int,
      height: Int,
      isPortrait: Boolean,
      cXmin: Double,
      cYmin: Double,
      cWidth: Double,
      cHeight: Double
    ): Pair<Double, Double> {
      // Now that we start in a good spot, the random search is more effective.
      // But we keep a known good point as a fallback.
      val localMaxIterations = maxIterations
      var bestX = 0.0
      var bestY = 0.0
      var maxIterationsFound = 0

      var i = 0
      while (i < ZOOM_SEARCH_MAX) {
        val randX = Random.nextInt(width)
        val randY = Random.nextInt(height)

        val zx = if (isPortrait)
          cXmin + cWidth * randY / height
        else
          cXmin + cWidth * randX / width
        val zy = if (isPortrait)
          cYmin + cHeight * (width - randX) / width
        else
          cYmin + cHeight * randY / height

        val iterations = iteratePixel(zx, zy)

        if (iterations > maxIterationsFound && iterations < localMaxIterations) {
          maxIterationsFound = iterations
          bestX = zx
          bestY = zy
        }
        i++
      }

      if (maxIterationsFound == 0) {
        return INTERESTING_POINT
      }

      return Pair(bestX, bestY)
    }

    override fun pixelColor(
      x: Int,
      y: Int,
      transform: AffineTransform
    ): Int {
      val localMaxIterations = maxIterations
      val c_real = transform.zx_x * x + transform.zx_y * y + transform.zx_c
      val c_imag = transform.zy_x * x + transform.zy_y * y + transform.zy_c

      var z_real = 0.0
      var z_imag = 0.0

      var i = 0
      while (i < localMaxIterations && z_real * z_real + z_imag * z_imag < ESCAPE_RADIUS_SQUARED) {
        val z_real_abs = abs(z_real)
        val z_imag_abs = abs(z_imag)
        val z_real_next = z_real_abs * z_real_abs - z_imag_abs * z_imag_abs + c_real
        val z_imag_next = 2.0 * z_real_abs * z_imag_abs + c_imag
        z_real = z_real_next
        z_imag = z_imag_next
        i++
      }

      return calculateColor(i, z_real, z_imag)
    }
  }

  companion object {
    private val INTERESTING_POINT = Pair(-1.77, -0.05)
  }
}
