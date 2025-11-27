package org.jtb.fractaldreams

import android.app.ActivityManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.service.dreams.DreamService
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
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

class GLMandelbrotDreamService : DreamService() {
  private companion object {
    const val OVERLAY_UPDATE_MS = 100L
    const val INITIAL_RENDER_SCALE = 1
    const val LOW_FPS_THRESHOLD = 20.0f
    const val FPS_CHECK_INTERVAL_MS = 2000L
    const val MAX_RENDER_SCALE = 4
  }

  private lateinit var glSurfaceView: ScaledGLSurfaceView
  private lateinit var glRenderer: GLMandelbrotRenderer
  private lateinit var overlayView: OverlayView

  private val serviceJob = SupervisorJob()
  private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

  private val fpsDisplay = FpsDisplay()

  private var currentRenderScale = INITIAL_RENDER_SCALE

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()

    isFullscreen = true
    isInteractive = false

    systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        or View.SYSTEM_UI_FLAG_FULLSCREEN)

    // Check if the device supports OpenGL ES 2.0.
    val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val configurationInfo = activityManager.deviceConfigurationInfo
    if (configurationInfo.reqGlEsVersion < 0x20000) {
      throw IllegalStateException(
          "This device does not support OpenGL ES 2.0: reqGlEsVersion=${
            Integer.toHexString(
                configurationInfo.reqGlEsVersion
            )
          }"
      )
    }

    glSurfaceView = ScaledGLSurfaceView(this, INITIAL_RENDER_SCALE)
    glSurfaceView.setEGLContextClientVersion(2)
    glRenderer = GLMandelbrotRenderer(this, fpsDisplay, serviceScope)
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

  override fun onDreamingStarted() {
    super.onDreamingStarted()

    glSurfaceView.onResume()

    serviceScope.launch { updateOverlay() }
    serviceScope.launch { observeFps() }
  }

  private suspend fun observeFps() {
    delay(FpsDisplay.WINDOW_SIZE_MS)

    while (coroutineContext.isActive && currentRenderScale < MAX_RENDER_SCALE) {
      val fps = fpsDisplay.getFps()
      if (fps < LOW_FPS_THRESHOLD) {
        currentRenderScale *= 2
        Log.i("GLMandelbrotDream", "Low FPS: ($fps), reducing quality to scale=$currentRenderScale")

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
    var scale: Int
  ) : GLSurfaceView(context) {

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
      fpsDisplay.draw(canvas)
    }
  }
}
