package org.jtb.fractaldreams

import android.content.Context
import kotlin.random.Random

class JuliaDreamService : FractalDreamService() {

  override fun createFractalView(context: Context): FractalView {
    return JuliaView(context, serviceScope)
  }

  private inner class JuliaView(context: Context, serviceScope: kotlinx.coroutines.CoroutineScope) : FractalView(context, serviceScope) {
    override val maxIterations = 256
    override val precisionLimit = 1.0E-4

    private lateinit var juliaC: Pair<Double, Double>

    private fun findInterestingConstant(): Pair<Double, Double> {
      val localMaxIterations = maxIterations
      var bestX = 0.0
      var bestY = 0.0
      var maxIterationsFound = 0

      (0 until ZOOM_SEARCH_MAX).forEach { i ->
        val zx = -2.0 + 3.0 * Random.nextDouble()
        val zy = -1.5 + 3.0 * Random.nextDouble()

        val iterations = iterateMandelbrotPixel(zx, zy)

        if (iterations > maxIterationsFound && iterations < localMaxIterations) {
          maxIterationsFound = iterations
          bestX = zx
          bestY = zy
        }
      }

      if (maxIterationsFound == 0) {
        return FALLBACK_C
      }

      return Pair(bestX, bestY)
    }

    private fun iterateMandelbrotPixel(zx: Double, zy: Double): Int {
      val localMaxIterations = maxIterations
      var cx = 0.0
      var cy = 0.0
      var i = 0
      while (i < localMaxIterations && cx * cx + cy * cy < ESCAPE_RADIUS_SQUARED) {
        val temp = cx * cx - cy * cy + zx
        cy = 2.0 * cx * cy + zy
        cx = temp
        i++
      }
      return i
    }

    override fun onReset() {
      juliaC = findInterestingConstant()
    }

    override fun getInitialCoordinates(isPortrait: Boolean, width: Int, height: Int): DoubleArray {
      juliaC = findInterestingConstant()
      val cXminInitial = -1.5
      val cWidthInitial = 3.0
      val cHeightInitial = 3.0
      val cYminInitial = -1.5
      return doubleArrayOf(cXminInitial, cYminInitial, cWidthInitial, cHeightInitial)
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
      val jc_real = juliaC.first
      val jc_imag = juliaC.second

      for (i in 0 until ZOOM_SEARCH_MAX) {
        val randX = Random.nextDouble()
        val randY = Random.nextDouble()

        val zx = cXmin + cWidth * randX
        val zy = cYmin + cHeight * randY

        var tempZx = zx
        var tempZy = zy
        var iterations = 0
        while (iterations < localMaxIterations && tempZx * tempZx + tempZy * tempZy < ESCAPE_RADIUS_SQUARED) {
          val temp = tempZx * tempZx - tempZy * tempZy + jc_real
          tempZy = 2.0 * tempZx * tempZy + jc_imag
          tempZx = temp
          iterations++
        }

        if (iterations > maxIterationsFound && iterations < localMaxIterations) {
          maxIterationsFound = iterations
          bestX = zx
          bestY = zy
        }
      }

      if (maxIterationsFound == 0) {
        return Pair(cXmin + cWidth / 2, cYmin + cHeight / 2)
      }

      return Pair(bestX, bestY)
    }

    override fun pixelColor(
      x: Int,
      y: Int,
      transform: AffineTransform
    ): Int {
      val localMaxIterations = maxIterations
      var zx = transform.zx_x * x + transform.zx_y * y + transform.zx_c
      var zy = transform.zy_x * x + transform.zy_y * y + transform.zy_c
      val jc_real = juliaC.first
      val jc_imag = juliaC.second

      var i = 0
      while (i < localMaxIterations && zx * zx + zy * zy < ESCAPE_RADIUS_SQUARED) {
        val temp = zx * zx - zy * zy + jc_real
        zy = 2.0 * zx * zy + jc_imag
        zx = temp
        i++
      }

      return calculateColor(i, zx, zy)
    }
  }

  companion object {
    private val FALLBACK_C = Pair(-0.7, 0.27015)
  }
}