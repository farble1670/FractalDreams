package org.jtb.fractaldreams

import android.app.ActivityManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.service.dreams.DreamService
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

abstract class GLFractalDreamService : DreamService() {
  private companion object {
    private const val TAG = "GLFractalDreamService"

    /**
     * Update the overlay view this often
     */
    val OVERLAY_UPDATE_MS = 250.milliseconds.inWholeMilliseconds
    /**
     * Initial (down) scaling factor (start at 1-1, no scaling)
     */
    const val INITIAL_RENDER_SCALE = 1
    /**
     * If FPS drops below this value, we'll start scaling to get better FPS
     */
    const val LOW_FPS_THRESHOLD = 20.0f
    /**
     * Check if the FPS has dropped below [LOW_FPS_THRESHOLD] this often.
     */
    val FPS_CHECK_INTERVAL_MS = 2.seconds.inWholeMilliseconds
    /**
     * Don't increase the render scale over this value.
     */
    const val MAX_RENDER_SCALE = 4
    /**
     * We require at least this version of GL ES to run this app.
     */
    private const val REQUIRED_GL_ES_VERSION = 0x20000
  }

  private lateinit var glSurfaceView: ScaledGLSurfaceView
  private lateinit var glRenderer: GLFractalRenderer
  private lateinit var overlayView: OverlayView

  private val serviceJob = SupervisorJob()
  private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

  private val fpsDisplay = FpsDisplay()

  private var currentRenderScale = INITIAL_RENDER_SCALE

  protected abstract fun createRenderer(
    context: Context,
    fpsDisplay: FpsDisplay,
    scope: CoroutineScope
  ): GLFractalRenderer

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()

    isFullscreen = true
    // Any touch exits
    isInteractive = false

    // Make us fullscreen
    systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        or View.SYSTEM_UI_FLAG_FULLSCREEN)

    if (glEsVersion < REQUIRED_GL_ES_VERSION) {
      throw IllegalStateException(
          "This device does not support OpenGL ES ${REQUIRED_GL_ES_VERSION}}: glEsVersion=${
            Integer.toHexString(
                glEsVersion
            )
          }"
      )
    }

    glSurfaceView = ScaledGLSurfaceView(this, INITIAL_RENDER_SCALE)
    glSurfaceView.setEGLContextClientVersion(2)
    glRenderer = createRenderer(this, fpsDisplay, serviceScope)
    glSurfaceView.setRenderer(glRenderer)
    // Use continuous mode - GL thread controls frame rate with vsync
    glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

    overlayView = OverlayView(this)

    // Use FrameLayout to layer the GLSurfaceView and overlay
    val frameLayout = FrameLayout(this)
    frameLayout.addView(glSurfaceView)
    frameLayout.addView(overlayView)

    setContentView(frameLayout)
  }

  private val glEsVersion: Int by lazy {
    val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val configurationInfo = activityManager.deviceConfigurationInfo
    configurationInfo.reqGlEsVersion
  }

  override fun onDreamingStarted() {
    super.onDreamingStarted()

    glSurfaceView.onResume()

    serviceScope.launch { updateOverlay() }
    serviceScope.launch { observeFps() }
  }

  private suspend fun observeFps() {
    delay(FpsDisplay.WINDOW_SIZE_MS)

    while (coroutineContext.isActive && currentRenderScale < MAX_RENDER_SCALE) {
      val fps = fpsDisplay.fps
      if (fps < LOW_FPS_THRESHOLD) {
        currentRenderScale *= 2
        Log.i(TAG, "Low FPS: ($fps), reducing quality to scale=$currentRenderScale")

        withContext(Dispatchers.Main) {
          glSurfaceView.changeScale(currentRenderScale)
        }

        // Wait for FPS window to completely replace old samples
        delay(FpsDisplay.WINDOW_SIZE_MS)
      } else {
        delay(FPS_CHECK_INTERVAL_MS)
      }
    }
  }

  override fun onDreamingStopped() {
    serviceJob.cancel()
    glSurfaceView.onPause()

    super.onDreamingStopped()
  }

  private var systemUiVisibility: Int
    get() = window.decorView.systemUiVisibility
    set(value) {
      window.decorView.systemUiVisibility = value
    }

  private suspend fun updateOverlay() {
    // GL rendering happens continuously on GL thread with vsync
    // This just updates the overlay (FPS counter, etc.)
    while (coroutineContext.isActive) {
      overlayView.postInvalidate()
      delay(OVERLAY_UPDATE_MS)
    }
  }

  private class ScaledGLSurfaceView(
    context: Context,
    initialScale: Int,
  ) : GLSurfaceView(context) {

    var scale: Int = initialScale.coerceAtLeast(1)
      set(value) {
        field = value.coerceAtLeast(1)
        requestLayout()
      }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
      super.onMeasure(widthMeasureSpec, heightMeasureSpec)
      val width = MeasureSpec.getSize(widthMeasureSpec)
      val height = MeasureSpec.getSize(heightMeasureSpec)

      // Set the actual GL surface to be smaller
      holder.setFixedSize(width / scale, height / scale)
    }

    fun changeScale(newScale: Int) {
      scale = newScale
      requestLayout()
    }
  }

  private inner class OverlayView(context: Context) : View(context) {
    init {
      setWillNotDraw(false)
      setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onDraw(canvas: Canvas) {
      super.onDraw(canvas)

      // Draw any subviews here
      fpsDisplay.draw(canvas)
    }
  }
}
