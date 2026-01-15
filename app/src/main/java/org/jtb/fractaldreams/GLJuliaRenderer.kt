package org.jtb.fractaldreams

import android.content.Context
import android.opengl.GLES20
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.random.Random

class GLJuliaRenderer(
  private val context: Context,
  fpsDisplay: FpsDisplay,
  private val scope: CoroutineScope
) : GLFractalRenderer(context, fpsDisplay, scope) {

  // Julia-specific constants
  private companion object {
    // Fallback c value if search fails (Douady rabbit - famous Julia set)
    const val FALLBACK_CX = -0.4
    const val FALLBACK_CY = 0.6

    // Known good starting zoom point for Douady rabbit (near boundary feature)
    const val FALLBACK_ZOOM_X = -0.5
    const val FALLBACK_ZOOM_Y = 0.5

    // Mandelbrot set bounds for searching interesting c values
    const val SEARCH_X_MIN = -2.0
    const val SEARCH_X_MAX = 1.0
    const val SEARCH_Y_MIN = -1.5
    const val SEARCH_Y_MAX = 1.5

    // Iteration range that indicates c is near Mandelbrot boundary (interesting Julia sets)
    const val MIN_BOUNDARY_ITERATIONS = 64
    const val MAX_BOUNDARY_ITERATIONS = 255
    const val SEARCH_ATTEMPTS = 2048
  }

  private var juliaCHandle: Int = 0

  // Current Julia c parameter
  private var juliaCx = FALLBACK_CX
  private var juliaCy = FALLBACK_CY

  // Next Julia c and zoom target (pre-computed in background as a pair)
  // Initialized to fallback values, so always safe to use
  @Volatile
  private var nextJuliaCx = FALLBACK_CX
  @Volatile
  private var nextJuliaCy = FALLBACK_CY
  @Volatile
  private var nextZoomX = FALLBACK_ZOOM_X
  @Volatile
  private var nextZoomY = FALLBACK_ZOOM_Y

  // Initial view bounds for Julia set (centered at origin)
  override val initialXMin = -2.0
  override val initialYMin = -2.0
  override val initialWidth = 4.0
  override val initialHeight = 4.0
  override val initialTargetX = FALLBACK_ZOOM_X
  override val initialTargetY = FALLBACK_ZOOM_Y
  override val resetDuration = 30.0
  override val targetZoomIterations = 128

  override fun getFragmentShaderResource(): Int = R.raw.julia_fragment_shader

  // Return current Julia c parameter for search operations
  override fun getCurrentCx(): Double = juliaCx
  override fun getCurrentCy(): Double = juliaCy

  override fun onSurfaceCreatedAfter() {
    // Get the handle for the Julia-specific uniform
    juliaCHandle = GLES20.glGetUniformLocation(program, "u_julia_c")

    // Start background search for first c+zoom pair so it's ready before first reset
    requestNewJuliaC()
  }

  override fun onReset() {
    // Swap to next Julia c and its matching zoom target
    juliaCx = nextJuliaCx
    juliaCy = nextJuliaCy
    // Store the matching zoom target in the parent's next target variables
    // so super.onReset() will use it
    setNextTarget(nextZoomX, nextZoomY)

    // Call parent reset logic - this will use our pre-set target
    super.onReset()

    // Request a new c and zoom target pair for the next reset
    requestNewJuliaC()
  }

  override fun setupFractalUniforms() {
    // Set the Julia c parameter uniform
    GLES20.glUniform2f(juliaCHandle, juliaCx.toFloat(), juliaCy.toFloat())
  }

  // Julia iteration for a single point (CPU-based)
  // For Julia: z starts at pixel position, iterates z = z² + c where c is from parameters
  override fun iteratePixel(pixelX: Double, pixelY: Double, cX: Double, cY: Double): Int {
    var zx = pixelX
    var zy = pixelY
    var i = 0
    while (i < MAX_ITERATIONS && zx * zx + zy * zy < ESCAPE_RADIUS_SQUARED) {
      val temp = zx * zx - zy * zy + cX
      zy = 2.0 * zx * zy + cY
      zx = temp
      i++
    }
    return i
  }

  // Mandelbrot iteration to test if a c value is near the boundary
  private fun iterateMandelbrot(cx: Double, cy: Double): Int {
    var zx = 0.0
    var zy = 0.0
    var i = 0
    while (i < MAX_ITERATIONS && zx * zx + zy * zy < ESCAPE_RADIUS_SQUARED) {
      val temp = zx * zx - zy * zy + cx
      zy = 2.0 * zx * zy + cy
      zx = temp
      i++
    }
    return i
  }

  /**
   * Search for an interesting Julia c value by finding points near the Mandelbrot boundary.
   */
  private fun searchInterestingJuliaC(): Pair<Double, Double> {
    repeat(SEARCH_ATTEMPTS) {
      val cx = SEARCH_X_MIN + Random.nextDouble() * (SEARCH_X_MAX - SEARCH_X_MIN)
      val cy = SEARCH_Y_MIN + Random.nextDouble() * (SEARCH_Y_MAX - SEARCH_Y_MIN)

      val iterations = iterateMandelbrot(cx, cy)

      // Points near the boundary (moderate iteration count) create interesting Julia sets
      if (iterations in MIN_BOUNDARY_ITERATIONS..MAX_BOUNDARY_ITERATIONS) {
        return Pair(cx, cy)
      }
    }

    // Fallback if no good point found
    return Pair(FALLBACK_CX, FALLBACK_CY)
  }

  private fun requestNewJuliaC() {
    scope.launch(Dispatchers.Default) {
      // Find a new interesting Julia c value
      val (newCx, newCy) = searchInterestingJuliaC()

      // Find a good zoom point for this specific c value (without modifying current c)
      val (zoomX, zoomY) = searchZoomPoint(newCx, newCy)

      // Store the new c and its matching zoom point
      nextJuliaCx = newCx
      nextJuliaCy = newCy
      nextZoomX = zoomX
      nextZoomY = zoomY
    }
  }
}
