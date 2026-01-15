package org.jtb.fractaldreams

import android.content.Context
import kotlinx.coroutines.CoroutineScope

class GLMandelbrotRenderer(
  context: Context,
  fpsDisplay: FpsDisplay,
  scope: CoroutineScope
) : GLFractalRenderer(context, fpsDisplay, scope) {

  // Mandelbrot-specific constants
  private companion object {
    // Target point (seahorse valley coordinates - fallback)
    const val SEAHORSE_VALLEY_X = -0.7451968299999999
    const val SEAHORSE_VALLEY_Y = 0.10186988500000009
  }

  // Initial view bounds for Mandelbrot set
  override val initialXMin = -2.0
  override val initialYMin = -1.5
  override val initialWidth = 3.0
  override val initialHeight = 3.0
  override val initialTargetX = SEAHORSE_VALLEY_X
  override val initialTargetY = SEAHORSE_VALLEY_Y
  override val resetDuration = 30.0
  override val targetZoomIterations = 250

  override fun getFragmentShaderResource(): Int = R.raw.fragment_shader

  // For Mandelbrot, current c doesn't matter (c = pixel always), but we need to return something
  override fun getCurrentCx(): Double = 0.0
  override fun getCurrentCy(): Double = 0.0

  // Mandelbrot iteration for a single point (CPU-based)
  // For Mandelbrot: z starts at 0, iterates z = z² + c where c is the pixel position
  override fun iteratePixel(pixelX: Double, pixelY: Double, cX: Double, cY: Double): Int {
    var zx = 0.0
    var zy = 0.0
    var i = 0
    while (i < MAX_ITERATIONS && zx * zx + zy * zy < ESCAPE_RADIUS_SQUARED) {
      val temp = zx * zx - zy * zy + pixelX
      zy = 2.0 * zx * zy + pixelY
      zx = temp
      i++
    }
    return i
  }
}
