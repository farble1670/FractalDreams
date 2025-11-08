package org.jtb.fractaldreams

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.pow
import kotlin.random.Random

class GLMandelbrotRenderer(
    private val context: Context,
    private val fpsDisplay: FpsDisplay,
    private val onRequestNewTarget: () -> Unit
) : GLSurfaceView.Renderer {
    private val TAG = "GLMandelbrotRenderer"

    // Animation constants
    private companion object {
        // Zoom and rotation speeds (time-based, not frame-based)
        const val ZOOM_SPEED = 0.25f                 // Zoom speed multiplier (1.0 = normal, <1.0 = slower, >1.0 = faster)
        const val ROTATION_SPEED = 0.125f             // Rotation speed in radians per second
        const val TIME_INCREMENT_PER_SEC = 0.5f    // Time increment per second for color cycling
        const val TARGET_FPS = 60.0f                // Reference frame rate for zoom calculations

        // Target point (seahorse valley coordinates - fallback)
        const val SEAHORSE_VALLEY_X = -0.7451968299999999f
        const val SEAHORSE_VALLEY_Y = 0.10186988500000009f

        // Zoom target search
        const val ZOOM_SEARCH_MAX = 1024            // Number of random samples to try
        const val MAX_ITERATIONS = 256              // Must match shader
        const val ESCAPE_RADIUS_SQUARED = 4.0       // Must match shader

        // Initial view bounds
        const val INITIAL_X_MIN = -2.0f             // Initial left edge in complex plane
        const val INITIAL_Y_MIN = -1.5f             // Initial bottom edge in complex plane
        const val INITIAL_WIDTH = 3.0f              // Initial view width in complex plane
        const val INITIAL_HEIGHT = 3.0f             // Initial view height in complex plane

        // Reset threshold
        const val MIN_ZOOM_THRESHOLD = 0.00005f     // When to reset zoom (approaching precision limit)
    }

    private var program: Int = 0
    private var positionHandle: Int = 0
    private var resolutionHandle: Int = 0
    private var timeHandle: Int = 0
    private var viewCenterHandle: Int = 0
    private var viewSizeHandle: Int = 0
    private var angleHandle: Int = 0
    private var swapCoordsHandle: Int = 0

    private val resolution = FloatArray(2)
    private var swapCoords = 0f  // 1.0 for portrait, 0.0 for landscape

    // Current view bounds in complex plane
    private var cXmin = INITIAL_X_MIN
    private var cYmin = INITIAL_Y_MIN
    private var cWidth = INITIAL_WIDTH
    private var cHeight = INITIAL_HEIGHT
    private var angle = 0f

    // Current zoom target
    private var targetX = SEAHORSE_VALLEY_X
    private var targetY = SEAHORSE_VALLEY_Y

    // Next zoom target (pre-computed in background)
    @Volatile private var nextTargetX = SEAHORSE_VALLEY_X
    @Volatile private var nextTargetY = SEAHORSE_VALLEY_Y
    @Volatile private var nextTargetReady = false

    // Time tracking
    private var lastFrameTime = 0L
    private var time = 0f

    private val vertexBuffer: FloatBuffer

    // A simple square that fills the screen
    private val squareCoords = floatArrayOf(
        -1.0f,  1.0f,
        -1.0f, -1.0f,
         1.0f, -1.0f,
         1.0f,  1.0f
    )

    init {
        val bb = ByteBuffer.allocateDirect(squareCoords.size * 4)
        bb.order(ByteOrder.nativeOrder())
        vertexBuffer = bb.asFloatBuffer()
        vertexBuffer.put(squareCoords)
        vertexBuffer.position(0)
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
            throw RuntimeException("Could not compile shader $type:\n$info")
        }

        return shader
    }

    private fun readShaderFromResources(resourceId: Int): String {
        val inputStream = context.resources.openRawResource(resourceId)
        val reader = BufferedReader(InputStreamReader(inputStream))
        val sb = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            sb.append(line).append("\n")
        }
        return sb.toString()
    }

    private fun updateAnimation() {
        // Calculate delta time
        val currentTime = System.currentTimeMillis()
        val deltaTime = if (lastFrameTime == 0L) {
            1.0f / TARGET_FPS  // First frame, use target FPS
        } else {
            (currentTime - lastFrameTime) / 1000.0f  // Convert ms to seconds
        }
        lastFrameTime = currentTime

        // Reset if we've zoomed too far
        if (cWidth < MIN_ZOOM_THRESHOLD) {
            cXmin = INITIAL_X_MIN
            cYmin = INITIAL_Y_MIN
            cWidth = INITIAL_WIDTH
            cHeight = INITIAL_HEIGHT
            angle = 0f
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
            onRequestNewTarget()
        } else {
            // Zoom towards the target point (time-based)
            // At 60 FPS with ZOOM_SPEED=1.0, this matches the old 0.99 per-frame behavior
            val zoomPower = deltaTime * TARGET_FPS * ZOOM_SPEED
            val zoomFactor = 0.99.toDouble().pow(zoomPower.toDouble()).toFloat()
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
            throw RuntimeException("Could not link program:\n$info")
        }

        positionHandle = GLES20.glGetAttribLocation(program, "vPosition")
        resolutionHandle = GLES20.glGetUniformLocation(program, "u_resolution")
        timeHandle = GLES20.glGetUniformLocation(program, "u_time")
        viewCenterHandle = GLES20.glGetUniformLocation(program, "u_view_center")
        viewSizeHandle = GLES20.glGetUniformLocation(program, "u_view_size")
        angleHandle = GLES20.glGetUniformLocation(program, "u_angle")
        swapCoordsHandle = GLES20.glGetUniformLocation(program, "u_swap_coords")
    }

    override fun onDrawFrame(gl: GL10?) {
        // Update animation state on the GL thread
        updateAnimation()

        // Track FPS on the GL thread for accurate measurement
        fpsDisplay.update()

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)

        // Calculate view center
        val viewCenterX = cXmin + cWidth / 2.0f
        val viewCenterY = cYmin + cHeight / 2.0f

        // Pass uniforms to the shader
        GLES20.glUniform2fv(resolutionHandle, 1, resolution, 0)
        GLES20.glUniform1f(timeHandle, time)
        GLES20.glUniform2f(viewCenterHandle, viewCenterX, viewCenterY)
        GLES20.glUniform2f(viewSizeHandle, cWidth, cHeight)
        GLES20.glUniform1f(angleHandle, angle)
        GLES20.glUniform1f(swapCoordsHandle, swapCoords)

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
    private fun iteratePixel(cx: Double, cy: Double): Int {
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

    // Search for an interesting zoom point within the current view
    fun searchZoomPoint(): Pair<Float, Float> {
        var bestX = SEAHORSE_VALLEY_X.toDouble()
        var bestY = SEAHORSE_VALLEY_Y.toDouble()
        var maxIterationsFound = 0

        val currentCXmin = cXmin.toDouble()
        val currentCYmin = cYmin.toDouble()
        val currentCWidth = cWidth.toDouble()
        val currentCHeight = cHeight.toDouble()

        repeat(ZOOM_SEARCH_MAX) {
            val randX = Random.nextDouble()
            val randY = Random.nextDouble()

            val cx = currentCXmin + currentCWidth * randX
            val cy = currentCYmin + currentCHeight * randY

            val iterations = iteratePixel(cx, cy)

            if (iterations > maxIterationsFound && iterations < MAX_ITERATIONS) {
                maxIterationsFound = iterations
                bestX = cx
                bestY = cy
            }
        }

        // If we didn't find anything interesting, use seahorse valley
        if (maxIterationsFound == 0) {
            return Pair(SEAHORSE_VALLEY_X, SEAHORSE_VALLEY_Y)
        }

        return Pair(bestX.toFloat(), bestY.toFloat())
    }

    // Set the next target (called from background thread)
    fun setNextTarget(x: Float, y: Float) {
        nextTargetX = x
        nextTargetY = y
        nextTargetReady = true
    }
}
