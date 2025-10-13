package org.jtb.fractaldreams

import android.content.Context
import kotlin.random.Random

class MandelbrotDreamService : FractalDreamService() {

  override fun createFractalView(context: Context): FractalDreamService.FractalView {
    return MandelbrotView(context, serviceScope)
  }

  private inner class MandelbrotView(context: Context, serviceScope: kotlinx.coroutines.CoroutineScope) : FractalView(context, serviceScope) {
    override val maxIterations = 256
    override val precisionLimit = 1.0E-4

    override fun getInitialCoordinates(isPortrait: Boolean, width: Int, height: Int): DoubleArray {
      val cXminInitial = -2.0;
      val cXmaxInitial = 1.0
      val cWidthInitial = cXmaxInitial - cXminInitial
      val baseCHeight =
          if (isPortrait) cWidthInitial * width / height else cWidthInitial * height / width
      val cHeightInitial = baseCHeight * 1.2;
      val cYminInitial = -cHeightInitial / 2.0
      return doubleArrayOf(cXminInitial, cYminInitial, cWidthInitial, cHeightInitial)
    }

    private fun iteratePixel(zx: Double, zy: Double): Int {
      val localMaxIterations = maxIterations
      var cx = 0.0
      var cy = 0.0
      var i = 0
      while (i < localMaxIterations && cx * cx + cy * cy < ESCAPE_RADIUS_SQUARED) {
        val temp = cx * cx - cy * cy + zx;
        cy = 2.0 * cx * cy + zy;
        cx = temp;
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
        return ZOOM_SEAHORSE_VALLEY
      }

      return Pair(bestX, bestY)
    }

    override fun pixelColor(
      x: Int,
      y: Int,
      transform: AffineTransform
    ): Int {
      val localMaxIterations = maxIterations
      val finalZx = transform.zx_x * x + transform.zx_y * y + transform.zx_c
      val finalZy = transform.zy_x * x + transform.zy_y * y + transform.zy_c

      var cx = 0.0
      var cy = 0.0
      var i = 0
      while (i < localMaxIterations && cx * cx + cy * cy < ESCAPE_RADIUS_SQUARED) {
        val temp = cx * cx - cy * cy + finalZx
        cy = 2.0 * cx * cy + finalZy;
        cx = temp;
        i++
      }

      return calculateColor(i, cx, cy)
    }
  }

  companion object {
    private val ZOOM_SEAHORSE_VALLEY = Pair(-0.7451968299999999, 0.10186988500000009)
  }
}
