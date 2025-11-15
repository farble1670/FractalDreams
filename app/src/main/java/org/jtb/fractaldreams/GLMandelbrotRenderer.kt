package org.jtb.fractaldreams

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.pow
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GLMandelbrotRenderer(
  private val context: Context,
  private val fpsDisplay: FpsDisplay,
  private val scope: CoroutineScope
) : GLSurfaceView.Renderer {
  private val TAG = "GLMandelbrotRenderer"

  // Animation constants
  private companion object {
    // Zoom and rotation speeds (time-based, not frame-based)
    const val ZOOM_RATE = 0.9985                // Zoom factor per frame at 60 FPS (closer to 1.0 = slower)
    const val ROTATION_SPEED = 0.125f           // Rotation speed in radians per second
    const val TIME_INCREMENT_PER_SEC = 0.5f     // Time increment per second for color cycling
    const val TARGET_FPS = 60.0f                // Reference frame rate for zoom calculations

    // Target point (seahorse valley coordinates - fallback)
    const val SEAHORSE_VALLEY_X = -0.7451968299999999
    const val SEAHORSE_VALLEY_Y = 0.10186988500000009

    // Fraction of total pixels to sample to find an interesting zoom point
    // This value yields ~4096 samples on a 1920x1080 display
    const val ZOOM_SEARCH_FRACTION = 1.0f / 506.25f
    const val ZOOM_SEARCH_MIN = 1024
    const val MAX_ITERATIONS = 256              // Must match shader
    const val ESCAPE_RADIUS_SQUARED = 4.0       // Must match shader

    // Initial view bounds (using doubles for high precision)
    const val INITIAL_X_MIN = -2.0              // Initial left edge in complex plane
    const val INITIAL_Y_MIN = -1.5              // Initial bottom edge in complex plane
    const val INITIAL_WIDTH = 3.0               // Initial view width in complex plane
    const val INITIAL_HEIGHT = 3.0              // Initial view height in complex plane

    // Reset threshold
    const val MIN_ZOOM_THRESHOLD = 0.00005      // When to reset zoom (approaching precision limit)
  }

  private var program: Int = 0
  private var positionHandle: Int = 0
  private var resolutionHandle: Int = 0
  private var timeHandle: Int = 0
  private var viewCenterHandle: Int = 0
  private var viewSizeHandle: Int = 0
  private var cosAngleHandle: Int = 0
  private var sinAngleHandle: Int = 0
  private var swapCoordsHandle: Int = 0
  private var maxIterationsHandle: Int = 0
  private var targetHandle: Int = 0

  private val resolution = FloatArray(2)
  private var swapCoords = 0f  // 1.0 for portrait, 0.0 for landscape

  // Current view bounds in complex plane (using doubles for high precision)
  private var cXmin = INITIAL_X_MIN
  private var cYmin = INITIAL_Y_MIN
  private var cWidth = INITIAL_WIDTH
  private var cHeight = INITIAL_HEIGHT
  private var angle = 0.0

  // Current zoom target (using doubles for high precision)
  private var targetX = SEAHORSE_VALLEY_X
  private var targetY = SEAHORSE_VALLEY_Y

  // Next zoom target (pre-computed in background)
  @Volatile
  private var nextTargetX = SEAHORSE_VALLEY_X
  @Volatile
  private var nextTargetY = SEAHORSE_VALLEY_Y
  @Volatile
  private var nextTargetReady = false

  // Time tracking
  private var lastFrameTime = 0L
  private var time = 0.0

  private val vertexBuffer: FloatBuffer

  // A simple square that fills the screen
  private val squareCoords = floatArrayOf(
      -1.0f, 1.0f,
      -1.0f, -1.0f,
      1.0f, -1.0f,
      1.0f, 1.0f
  )

  init {
    vertexBuffer = ByteBuffer.allocateDirect(squareCoords.size * 4)
      .order(ByteOrder.nativeOrder())
      .asFloatBuffer().apply {
        put(squareCoords)
        position(0)
      }
  }

  private fun loadShader(type: Int, shaderCode: String): Int {
    val shader = GLES20.glCreateShader(type)
    GLES20.glShaderSource(shader, shaderCode)
    GLES20.glCompileShader(shader)

    // Check for compile errors
    val compiled = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
    if (compiled[0] == 0) {
      val info = GLES20.glGetShaderInfoLog(shader)
      GLES20.glDeleteShader(shader)
      throw IllegalStateException("Could not compile shader, type: $type, info: $info")
    }

    return shader
  }

  private fun readShaderFromResources(resourceId: Int) =
      context.resources.openRawResource(resourceId).use { inputStream ->
        val reader = BufferedReader(InputStreamReader(inputStream))
        StringBuilder().apply {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                append(line).append("\n")
            }
        }.toString()
      }

  private fun updateAnimation() {
    // Calculate delta time
    val currentTime = SystemClock.elapsedRealtime()
    val deltaTime = if (lastFrameTime == 0L) {
      1.0 / TARGET_FPS
    } else {
      (currentTime - lastFrameTime) / 1000.0
    }
    lastFrameTime = currentTime

    // Reset if we've zoomed too far
    if (cWidth < MIN_ZOOM_THRESHOLD) {
      cXmin = INITIAL_X_MIN
      cYmin = INITIAL_Y_MIN
      cWidth = INITIAL_WIDTH
      cHeight = INITIAL_HEIGHT
      angle = 0.0
      lastFrameTime = currentTime  // Reset time to avoid huge delta on next frame

      // Use pre-computed target if ready, otherwise search now
      if (nextTargetReady) {
        targetX = nextTargetX
        targetY = nextTargetY
        nextTargetReady = false
      } else {
        // Fallback: search synchronously (shouldn't happen often)
        val (newTargetX, newTargetY) = searchZoomPoint()
        targetX = newTargetX
        targetY = newTargetY
      }

      // Request a new target to be computed in background for next reset
      requestNewTarget()
    } else {
      // Zoom towards the target point (time-based, double precision)
      val zoomPower = deltaTime * TARGET_FPS
      val zoomFactor = ZOOM_RATE.pow(zoomPower)
      val newWidth = cWidth * zoomFactor
      val newHeight = cHeight * zoomFactor

      // Adjust bounds to move towards target while zooming
      cXmin += (cWidth - newWidth) * (targetX - cXmin) / cWidth
      cYmin += (cHeight - newHeight) * (targetY - cYmin) / cHeight
      cWidth = newWidth
      cHeight = newHeight

      angle += ROTATION_SPEED * deltaTime
    }

    time += TIME_INCREMENT_PER_SEC * deltaTime
  }

  override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
    GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)

    val vertexShaderCode = readShaderFromResources(R.raw.vertex_shader)
    val fragmentShaderCode = readShaderFromResources(R.raw.fragment_shader)

    val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
    val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

    program = GLES20.glCreateProgram()
    GLES20.glAttachShader(program, vertexShader)
    GLES20.glAttachShader(program, fragmentShader)
    GLES20.glLinkProgram(program)

    // Check for link errors
    val linked = IntArray(1)
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
    if (linked[0] == 0) {
      val info = GLES20.glGetProgramInfoLog(program)
      GLES20.glDeleteProgram(program)
      throw RuntimeException("Could not link program: $info")
    }

    positionHandle = GLES20.glGetAttribLocation(program, "vPosition")
    resolutionHandle = GLES20.glGetUniformLocation(program, "u_resolution")
    timeHandle = GLES20.glGetUniformLocation(program, "u_time")
    viewCenterHandle = GLES20.glGetUniformLocation(program, "u_view_center")
    viewSizeHandle = GLES20.glGetUniformLocation(program, "u_view_size")
    cosAngleHandle = GLES20.glGetUniformLocation(program, "u_cos_angle")
    sinAngleHandle = GLES20.glGetUniformLocation(program, "u_sin_angle")
    swapCoordsHandle = GLES20.glGetUniformLocation(program, "u_swap_coords")
    maxIterationsHandle = GLES20.glGetUniformLocation(program, "u_max_iterations")
    targetHandle = GLES20.glGetUniformLocation(program, "u_target")
  }

  override fun onDrawFrame(gl: GL10) {
    // Update animation state on the GL thread
    updateAnimation()

    // Track FPS on the GL thread for accurate measurement
    fpsDisplay.update()

    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

    GLES20.glUseProgram(program)

    // Calculate view center (double precision math, convert to float for shader)
    val viewCenterX = (cXmin + cWidth / 2.0).toFloat()
    val viewCenterY = (cYmin + cHeight / 2.0).toFloat()

    // Pre-calculate trig functions on CPU (once per frame instead of once per pixel)
    val cosAngle = kotlin.math.cos(angle)
    val sinAngle = kotlin.math.sin(angle)

    // Pass uniforms to the shader (convert doubles to floats)
    GLES20.glUniform2fv(resolutionHandle, 1, resolution, 0)
    GLES20.glUniform1f(timeHandle, time.toFloat())
    GLES20.glUniform2f(viewCenterHandle, viewCenterX, viewCenterY)
    GLES20.glUniform2f(viewSizeHandle, cWidth.toFloat(), cHeight.toFloat())
    GLES20.glUniform1f(cosAngleHandle, cosAngle.toFloat())
    GLES20.glUniform1f(sinAngleHandle, sinAngle.toFloat())
    GLES20.glUniform1f(swapCoordsHandle, swapCoords)
    GLES20.glUniform1i(maxIterationsHandle, MAX_ITERATIONS)
    GLES20.glUniform2f(targetHandle, targetX.toFloat(), targetY.toFloat())

    GLES20.glEnableVertexAttribArray(positionHandle)
    GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4)

    GLES20.glDisableVertexAttribArray(positionHandle)
  }

  override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
    GLES20.glViewport(0, 0, width, height)

    // Swap width/height on portrait screens so fractal is always landscape-oriented
    val isPortrait = height > width
    if (isPortrait) {
      resolution[0] = height.toFloat()
      resolution[1] = width.toFloat()
      swapCoords = 1.0f
    } else {
      resolution[0] = width.toFloat()
      resolution[1] = height.toFloat()
      swapCoords = 0.0f
    }
  }

  // Mandelbrot iteration for a single point (CPU-based)
  @Suppress("NOTHING_TO_INLINE")
  private inline fun iteratePixel(cx: Double, cy: Double): Int {
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
   * Search for an random, interesting zoom point in the Mandelbrot set.
   *
   * This is (normally) called on a background thread while the GPU is rendering.
   */
  // Search for an interesting zoom point within the current view
  internal fun searchZoomPoint(): Pair<Double, Double> {
    var bestX = SEAHORSE_VALLEY_X
    var bestY = SEAHORSE_VALLEY_Y

    var maxIterations = 0

    // Calculate search count as fraction of total pixels
    val searchCount = (resolution[0] * resolution[1] * ZOOM_SEARCH_FRACTION).toInt()
      .coerceAtLeast(ZOOM_SEARCH_MIN)

    repeat(searchCount) {
      val randX = Random.nextDouble()
      val randY = Random.nextDouble()

      val cx = cXmin + cWidth * randX
      val cy = cYmin + cHeight * randY

      val iterations = iteratePixel(cx, cy)

      if (iterations > maxIterations && iterations < MAX_ITERATIONS) {
        maxIterations = iterations
        bestX = cx
        bestY = cy
      }
    }

    return Pair(bestX, bestY)
  }

  fun setNextTarget(x: Double, y: Double) {
    nextTargetX = x
    nextTargetY = y
    nextTargetReady = true
  }

  private fun requestNewTarget() {
    scope.launch(Dispatchers.Default) {
      val (nextX, nextY) = searchZoomPoint()
      setNextTarget(nextX, nextY)
    }
  }
}
