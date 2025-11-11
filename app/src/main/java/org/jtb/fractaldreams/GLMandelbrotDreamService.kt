package org.jtb.fractaldreams

import android.app.ActivityManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.service.dreams.DreamService
import android.view.View
import android.widget.FrameLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.log2

class GLMandelbrotDreamService : DreamService() {
  private companion object {
    const val OVERLAY_UPDATE_MS = 100L
    const val RENDER_SCALE = 1
  }

  private lateinit var glSurfaceView: GLSurfaceView
  private lateinit var glRenderer: GLMandelbrotRenderer
  private lateinit var overlayView: OverlayView

  private val job = SupervisorJob()
  private val serviceScope = CoroutineScope(Dispatchers.Default + job)

  private val fpsDisplay = FpsDisplay()

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()

    isFullscreen = true
    isInteractive = true

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
      throw RuntimeException(
          "This device does not support OpenGL ES 2.0: reqGlEsVersion=${
            Integer.toHexString(
                configurationInfo.reqGlEsVersion
            )
          }"
      )
    }

    glSurfaceView = ScaledGLSurfaceView(this, RENDER_SCALE)
    glSurfaceView.setEGLContextClientVersion(2)
    glRenderer = GLMandelbrotRenderer(this, fpsDisplay) {
      // Callback: search for next zoom target in background
      searchNextZoomTarget()
    }
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

    // Start computing the first target in background
    searchNextZoomTarget()
  }

  override fun onDreamingStopped() {
    job.cancel()
    super.onDreamingStopped()
    glSurfaceView.onPause()
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
  }

  private var systemUiVisibility: Int
    get() = window.decorView.systemUiVisibility
    set(value) {
      window.decorView.systemUiVisibility = value
    }

  private suspend fun updateOverlay() {
    // GL rendering happens continuously on GL thread with vsync
    // This just updates the overlay (FPS counter, etc.)
    while (serviceScope.isActive) {
      overlayView.postInvalidate()
      delay(OVERLAY_UPDATE_MS)
    }
  }

  private fun searchNextZoomTarget() {
    // Launch background search for next zoom target
    serviceScope.launch(Dispatchers.Default) {
      val (nextX, nextY) = glRenderer.searchZoomPoint()
      glRenderer.setNextTarget(nextX, nextY)
    }
  }

  private class ScaledGLSurfaceView(context: Context, private val scale: Int) :
      GLSurfaceView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
      super.onMeasure(widthMeasureSpec, heightMeasureSpec)
      val width = MeasureSpec.getSize(widthMeasureSpec)
      val height = MeasureSpec.getSize(heightMeasureSpec)

      // Set the actual GL surface to be smaller
      holder.setFixedSize(width / scale, height / scale)
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
