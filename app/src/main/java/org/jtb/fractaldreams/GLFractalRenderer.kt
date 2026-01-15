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

abstract class GLFractalRenderer(
  private val context: Context,
  private val fpsDisplay: FpsDisplay,
  private val scope: CoroutineScope
) : GLSurfaceView.Renderer {

  // Animation constants
  protected companion object {
    const val ZOOM_RATE = 0.9960                // Zoom factor per frame at 60 FPS (closer to 1.0 = slower)
    const val ROTATION_SPEED = 0.125f           // Rotation speed in radians per second
    const val TIME_INCREMENT_PER_SEC = 0.5f     // Time increment per second for color cycling
    const val TARGET_FPS = 60.0f                // Reference frame rate for zoom calculations

    // Fraction of total pixels to sample to find an interesting zoom point
    // This value yields ~4096 samples on a 1920x1080 display
    const val ZOOM_SEARCH_FRACTION = 1.0f / 506.25f
    const val ZOOM_SEARCH_MIN = 1024
    const val MAX_ITERATIONS = 256
    const val ESCAPE_RADIUS_SQUARED = 4.0
  }

  // Abstract property for subclasses to define reset duration
  protected abstract val resetDuration: Double  // Seconds until reset

  // Abstract properties - subclasses must define initial bounds
  protected abstract val initialXMin: Double
  protected abstract val initialYMin: Double
  protected abstract val initialWidth: Double
  protected abstract val initialHeight: Double
  protected abstract val initialTargetX: Double  // Initial zoom target
  protected abstract val initialTargetY: Double
  protected abstract val targetZoomIterations: Int  // Target iteration count for interesting zoom points

  // Abstract methods - fractal-specific behavior
  protected abstract fun getFragmentShaderResource(): Int
  protected abstract fun iteratePixel(pixelX: Double, pixelY: Double, cX: Double, cY: Double): Int
  protected abstract fun getCurrentCx(): Double
  protected abstract fun getCurrentCy(): Double

  // Open method for subclasses to add fractal-specific uniforms
  protected open fun setupFractalUniforms() {
    // Default: no additional uniforms needed
  }

  // Open method called after surface created - subclasses can get uniform handles here
  protected open fun onSurfaceCreatedAfter() {
    // Default: no additional setup needed
  }

  // Open method called when zoom resets - subclasses can override to add fractal-specific reset logic
  // Subclasses should call super.onReset() to ensure base reset logic runs
  protected open fun onReset() {
    // Use pre-computed next target
    targetX = nextTargetX
    targetY = nextTargetY

    // Request a new target to be computed in background for next reset
    requestNewTarget()
  }

  protected var program: Int = 0
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
  private var escapeRadiusSquaredHandle: Int = 0

  protected val resolution = FloatArray(2)
  private var swapCoords = 0f  // 1.0 for portrait, 0.0 for landscape

  // Current view bounds in complex plane (using doubles for high precision)
  protected var cXmin = 0.0
  protected var cYmin = 0.0
  protected var cWidth = 0.0
  protected var cHeight = 0.0
  private var angle = 0.0

  // Current zoom target (using doubles for high precision)
  protected var targetX = 0.0
  protected var targetY = 0.0

  // Next zoom target (pre-computed in background)
  @Volatile
  private var nextTargetX = 0.0
  @Volatile
  private var nextTargetY = 0.0

  // Time tracking
  private var lastFrameTime = 0L
  private var time = 0.0
  private var resetStartTime = 0L

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

    // Reset after duration (resetDuration is in seconds, convert to millis)
    if (currentTime - resetStartTime > resetDuration * 1000) {
      cXmin = initialXMin
      cYmin = initialYMin
      cWidth = initialWidth
      cHeight = initialHeight
      angle = 0.0
      resetStartTime = currentTime
      lastFrameTime = currentTime  // Reset time to avoid huge delta on next frame

      // Notify subclasses that a reset occurred (includes target search and background request)
      onReset()
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
    val fragmentShaderCode = readShaderFromResources(getFragmentShaderResource())

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
    escapeRadiusSquaredHandle = GLES20.glGetUniformLocation(program, "u_escape_radius_squared")

    // Initialize targets now that subclass properties are available
    targetX = initialTargetX
    targetY = initialTargetY
    nextTargetX = initialTargetX
    nextTargetY = initialTargetY

    // Call hook for subclasses to get their uniform handles
    onSurfaceCreatedAfter()
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
    GLES20.glUniform1f(escapeRadiusSquaredHandle, ESCAPE_RADIUS_SQUARED.toFloat())

    // Call subclass hook to set fractal-specific uniforms
    setupFractalUniforms()

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

  // Search for an interesting zoom point within the current view, using specified c values
  internal open fun searchZoomPoint(cX: Double, cY: Double): Pair<Double, Double> {
    var bestX = targetX
    var bestY = targetY

    var bestScore = Int.MAX_VALUE  // We want iterations closest to target

    // Calculate search count as fraction of total pixels
    val searchCount = (resolution[0] * resolution[1] * ZOOM_SEARCH_FRACTION).toInt()
      .coerceAtLeast(ZOOM_SEARCH_MIN)

    repeat(searchCount) {
      val randX = Random.nextDouble()
      val randY = Random.nextDouble()

      val pixelX = cXmin + cWidth * randX
      val pixelY = cYmin + cHeight * randY

      val iterations = iteratePixel(pixelX, pixelY, cX, cY)

      // Find point with iterations closest to target (and not in the set)
      if (iterations < MAX_ITERATIONS) {
        val score = kotlin.math.abs(iterations - targetZoomIterations)
        if (score < bestScore) {
          bestScore = score
          bestX = pixelX
          bestY = pixelY
        }
      }
    }

    return Pair(bestX, bestY)
  }

  // Search using current c values (for use by base class reset logic)
  private fun searchZoomPointCurrent(): Pair<Double, Double> {
    return searchZoomPoint(getCurrentCx(), getCurrentCy())
  }

  fun setNextTarget(x: Double, y: Double) {
    nextTargetX = x
    nextTargetY = y
  }

  private fun requestNewTarget() {
    scope.launch(Dispatchers.Default) {
      val (nextX, nextY) = searchZoomPointCurrent()
      setNextTarget(nextX, nextY)
    }
  }
}
