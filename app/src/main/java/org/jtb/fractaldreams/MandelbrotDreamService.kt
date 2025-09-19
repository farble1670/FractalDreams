package org.jtb.fractaldreams

import android.content.Context
import kotlin.math.log2
import kotlin.random.Random

class MandelbrotDreamService : FractalDreamService() {

  override fun createFractalView(context: Context): FractalDreamService.FractalView {
    return MandelbrotView(context, serviceScope)
  }

  private inner class MandelbrotView(context: Context, serviceScope: kotlinx.coroutines.CoroutineScope) : FractalView(context, serviceScope) {
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

    /**
     * Iterate on a single pixel, returning the number of iterations before escape.
     */
    private fun iteratePixel(zx: Double, zy: Double): Int {
      var cx = zx;
      var cy = zy;
      var i = 0
      while (i < MAX_ITERATIONS && cx * cx + cy * cy < ESCAPE_RADIUS_SQUARED) {
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

        if (iterations > maxIterationsFound && iterations < MAX_ITERATIONS) {
          maxIterationsFound = iterations
          bestX = zx
          bestY = zy
        }
        i++
      }

      // Fallback in case no interesting point is found
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
      // "Rotate" the pixel coordinates to find the corresponding complex number
      val finalZx = transform.zx_x * x + transform.zx_y * y + transform.zx_c
      val finalZy = transform.zy_x * x + transform.zy_y * y + transform.zy_c

      var cx = finalZx;
      var cy = finalZy;
      var i = 0
      while (i < MAX_ITERATIONS && cx * cx + cy * cy < ESCAPE_RADIUS_SQUARED) {
        val temp = cx * cx - cy * cy + finalZx
        cy = 2.0 * cx * cy + finalZy;
        cx = temp;
        i++
      }


      if (SMOOTH_COLORS) {
        if (i < MAX_ITERATIONS) {
          val logZn = log2LookupTable[((cx * cx + cy * cy) * LOOKUP_SCALE_FACTOR).toInt().coerceIn(0, 65535)] / 2.0
          val nu = log2LookupTable[logZn.toInt().coerceIn(0, 65535)] / LOG2_2
          val continuousIndex = i + 1 - nu
          val index1 = continuousIndex.toInt()
          val index2 = index1 + 1
          
          val color1 = colorPalette[(index1 + colorOffset) % MAX_ITERATIONS]
          val color2 = colorPalette[(index2 + colorOffset) % MAX_ITERATIONS]

          val fraction = (continuousIndex - continuousIndex.toInt()).toFloat()
          val r = (Colors.r(color1) * (1 - fraction) + Colors.r(color2) * fraction).toInt()
          val g = (Colors.g(color1) * (1 - fraction) + Colors.g(color2) * fraction).toInt()
          val b = (Colors.b(color1) * (1 - fraction) + Colors.b(color2) * fraction).toInt()

          return Colors.rgb(r, g, b)
        }

        return colorPalette[MAX_ITERATIONS]
      } else {
        return colorPalette[(i + colorOffset) % MAX_ITERATIONS]
      }
    }

    override val scaleFactor: Int = SCALE_FACTOR
    override val precisionLimit: Double = PRECISION_LIMIT
    override val zoomFactor: Double = ZOOM_FACTOR
    override val slices: Int = SLICES
    override val useBlockOptimization: Boolean = USE_BLOCK_OPTIMIZATION
    override val blockDensityDivisor: Int = BLOCK_DENSITY_DIVISOR
    override val minBlockSize: Int = MIN_BLOCK_SIZE
    override val maxBlockSize: Int = MAX_BLOCK_SIZE
    override val samplesPerAxis: Int = SAMPLES_PER_AXIS
    override val cropToSquare: Boolean = CROP_TO_SQUARE
  }

  companion object {
    private const val SCALE_FACTOR = 2
    private const val PRECISION_LIMIT = 1.0E-9
    private const val ZOOM_FACTOR = 0.98
    private val SLICES = Runtime.getRuntime().availableProcessors()
    private const val ZOOM_SEARCH_MAX = 1024
    private val ZOOM_SEAHORSE_VALLEY = Pair(-0.7451968299999999, 0.10186988500000009)
    private const val ESCAPE_RADIUS_SQUARED = 4.0
    private val LOG2_2 = log2(2.0)
    private const val SMOOTH_COLORS = true
    private const val USE_BLOCK_OPTIMIZATION = true
    private const val BLOCK_DENSITY_DIVISOR = 4096
    private const val MIN_BLOCK_SIZE = 8
    private const val MAX_BLOCK_SIZE = 64
    private const val SAMPLES_PER_AXIS = 4
    private const val CROP_TO_SQUARE = true
  }
}
