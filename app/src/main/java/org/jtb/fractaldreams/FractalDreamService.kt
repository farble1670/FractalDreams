package org.jtb.fractaldreams

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.service.dreams.DreamService
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log2

abstract class FractalDreamService : DreamService() {
    private val job = SupervisorJob()
    protected val serviceScope = CoroutineScope(Dispatchers.Default + job)

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

        setContentView(createFractalView(this))
    }

    override fun onDetachedFromWindow() {
        job.cancel()
        super.onDetachedFromWindow()
    }

    private var systemUiVisibility: Int
        get() = window.decorView.systemUiVisibility
        set(value) {
            window.decorView.systemUiVisibility = value
        }

    protected abstract fun createFractalView(context: Context): View

    data class AffineTransform(
        @JvmField val zx_x: Double, @JvmField val zx_y: Double, @JvmField val zx_c: Double,
        @JvmField val zy_x: Double, @JvmField val zy_y: Double, @JvmField val zy_c: Double
    )

    /**
     * Might be overkill, but ensure we're inlining these hot spot functions.
     */
    object Colors {
        @Suppress("NOTHING_TO_INLINE")
        inline fun r(color: Int): Int = (color shr 16) and 0xFF

        @Suppress("NOTHING_TO_INLINE")
        inline fun g(color: Int): Int = (color shr 8) and 0xFF

        @Suppress("NOTHING_TO_INLINE")
        inline fun b(color: Int): Int = color and 0xFF

        @Suppress("NOTHING_TO_INLINE")
        inline fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    abstract inner class FractalView(context: Context, private val serviceScope: CoroutineScope) : View(context) {
        protected val paint = Paint()
        private var renderJob: Job? = null
        private var frontBitmap: Bitmap? = null
        private var backBitmap: Bitmap? = null
        private val bufferLock = Any()
        protected val destRect = Rect()
        private val fpsDisplay = FpsDisplay()
        protected var colorOffset = 0
        protected val log2LookupTable = DoubleArray(65536)

        protected val colorPalette = IntArray(MAX_ITERATIONS + 1) { i ->
            if (i == MAX_ITERATIONS) Color.BLACK else Color.HSVToColor(
                floatArrayOf(
                    (i % 256) / 256f * 360,
                    1f,
                    1f
                )
            )
        }

        protected var targetX = 0.0
        protected var targetY = 0.0
        protected var cXmin = -2.0
        protected var cYmin = -1.5
        protected var cWidth = 3.0
        protected var cHeight = 3.0
        protected var angle = 0.0

        private val gestureDetector: GestureDetector

        init {
            isClickable = true

            for (i in log2LookupTable.indices) {
                log2LookupTable[i] = log2(i.toDouble())
            }

            val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    this@FractalDreamService.finish()
                    return true
                }

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    val touchX = e.x;
                    val touchY = e.y

                    val isPortrait = height > width
                    // Map screen coordinates to the un-rotated complex plane
                    val zx = if (isPortrait)
                        cXmin + cWidth * touchY / height
                    else
                        cXmin + cWidth * touchX / width
                    val zy = if (isPortrait)
                        cYmin + cHeight * (width - touchX) / width
                    else
                        cYmin + cHeight * touchY / height

                    // Now, apply the current rotation to find the actual complex number at that point
                    val sinAngle = kotlin.math.sin(angle)
                    val cosAngle = kotlin.math.cos(angle)

                    val centerX = cXmin + cWidth / 2.0
                    val centerY = cYmin + cHeight / 2.0

                    val zxRel = zx - centerX
                    val zyRel = zy - centerY

                    val finalZx = zxRel * cosAngle - zyRel * sinAngle + centerX
                    val finalZy = zxRel * sinAngle + zyRel * cosAngle + centerY

                    // This is the point we want to center on
                    targetX = finalZx
                    targetY = finalZy
                    cXmin = finalZx - cWidth / 2.0
                    cYmin = finalZy - cHeight / 2.0

                    return true
                }
            }

            gestureDetector = GestureDetector(context, gestureListener)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)

            if (w > 0 && h > 0) {
                val renderWidth: Int
                val renderHeight: Int

                if (cropToSquare) {
                    val size = kotlin.math.min(w, h)
                    renderWidth = size
                    renderHeight = size
                    val xOffset = (w - size) / 2
                    val yOffset = (h - size) / 2
                    destRect.set(xOffset, yOffset, w - xOffset, h - yOffset)
                } else {
                    renderWidth = w
                    renderHeight = h
                    destRect.set(0, 0, w, h)
                }

                val scaledWidth = renderWidth / scaleFactor
                val scaledHeight = renderHeight / scaleFactor

                synchronized(bufferLock) {
                    frontBitmap = createBitmap(scaledWidth, scaledHeight)
                    backBitmap = createBitmap(scaledWidth, scaledHeight)
                }
                renderJob?.cancel()
                renderJob = serviceScope.launch { animate(scaledWidth, scaledHeight) }
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
        }

        private suspend fun animate(width: Int, height: Int) {
            val isPortrait = height > width
            val (cXminInitial, cYminInitial, cWidthInitial, cHeightInitial) = getInitialCoordinates(isPortrait, width, height)
            cXmin = cXminInitial; cYmin = cYminInitial; cWidth = cWidthInitial; cHeight = cHeightInitial
            angle = 0.0

            var newTarget = searchZoomPoint(width, height, isPortrait, cXmin, cYmin, cWidth, cHeight)
            targetX = newTarget.first
            targetY = newTarget.second

            while (serviceScope.isActive) {
                colorOffset++ // Increment the color offset each frame

                if (cWidth < precisionLimit) {
                    cXmin = cXminInitial; cYmin = cYminInitial; cWidth = cWidthInitial; cHeight = cHeightInitial
                    angle = 0.0
                    newTarget = searchZoomPoint(width, height, isPortrait, cXmin, cYmin, cWidth, cHeight)
                    targetX = newTarget.first
                    targetY = newTarget.second
                    continue // Skip the rest of the loop and start fresh
                }

                render(width, height, isPortrait, cXmin, cYmin, cWidth, cHeight, angle)
                synchronized(bufferLock) {
                    val temp = frontBitmap;
                    frontBitmap = backBitmap;
                    backBitmap = temp
                }

                fpsDisplay.update(); postInvalidate()

                // Reactive check as a fallback
                if (isBoring(backBitmap, width, height)) {
                    cXmin = cXminInitial;
                    cYmin = cYminInitial;
                    cWidth = cWidthInitial;
                    cHeight = cHeightInitial
                    angle = 0.0

                    newTarget = searchZoomPoint(width, height, isPortrait, cXmin, cYmin, cWidth, cHeight)
                    targetX = newTarget.first
                    targetY = newTarget.second
                }

                val newWidth = cWidth * zoomFactor;
                val newHeight = cHeight * zoomFactor
                cXmin += (cWidth - newWidth) * (targetX - cXmin) / cWidth
                cYmin += (cHeight - newHeight) * (targetY - cYmin) / cHeight
                cWidth = newWidth; cHeight = newHeight
                angle += 0.01
            }
        }

        private fun isBoring(bitmap: Bitmap?, width: Int, height: Int): Boolean {
            bitmap ?: return false
            val sampleGridSize = 16
            val colorRangeThreshold = 32

            var minR = 255;
            var maxR = 0
            var minG = 255;
            var maxG = 0
            var minB = 255;
            var maxB = 0

            var i = 0
            while (i < sampleGridSize) {
                var j = 0
                while (j < sampleGridSize) {
                    val x = (i * width) / sampleGridSize
                    val y = (j * height) / sampleGridSize

                    val color = bitmap[x, y]
                    val r = Colors.r(color);
                    val g = Colors.g(color);
                    val b = Colors.b(color)

                    if (r < minR) minR = r; if (r > maxR) maxR = r
                    if (g < minG) minG = g; if (g > maxG) maxG = g
                    if (b < minB) minB = b; if (b > maxB) maxB = b
                    j++
                }
                i++
            }

            return (maxR - minR < colorRangeThreshold) &&
                    (maxG - minG < colorRangeThreshold) &&
                    (maxB - minB < colorRangeThreshold)
        }

        private suspend fun render(
            width: Int,
            height: Int,
            isPortrait: Boolean,
            cXmin: Double,
            cYmin: Double,
            cWidth: Double,
            cHeight: Double,
            angle: Double
        ) = withContext(Dispatchers.Default) {
            val sinAngle = kotlin.math.sin(angle)
            val cosAngle = kotlin.math.cos(angle)
            val centerX = cXmin + cWidth / 2.0
            val centerY = cYmin + cHeight / 2.0
            val w = width.toDouble()
            val h = height.toDouble()

            val transform = if (isPortrait) {
                AffineTransform(
                    zx_x = cHeight / w * sinAngle,
                    zx_y = cWidth / h * cosAngle,
                    zx_c = -(cWidth / 2.0) * cosAngle - (cHeight / 2.0) * sinAngle + centerX,
                    zy_x = -cHeight / w * cosAngle,
                    zy_y = cWidth / h * sinAngle,
                    zy_c = -(cWidth / 2.0) * sinAngle + (cHeight / 2.0) * cosAngle + centerY
                )
            } else {
                AffineTransform(
                    zx_x = cWidth / w * cosAngle,
                    zx_y = -cHeight / h * sinAngle,
                    zx_c = -(cWidth / 2.0) * cosAngle + (cHeight / 2.0) * sinAngle + centerX,
                    zy_x = cWidth / w * sinAngle,
                    zy_y = cHeight / h * cosAngle,
                    zy_c = -(cWidth / 2.0) * sinAngle - (cHeight / 2.0) * cosAngle + centerY
                )
            }

            val idealBlockArea = (width * height) / blockDensityDivisor
            val blockSize = kotlin.math.sqrt(idealBlockArea.toDouble()).toInt().coerceIn(minBlockSize, maxBlockSize)

            val sliceHeight = height / slices
            val jobs = (0 until slices).map { core ->
                async {
                    val startY = core * sliceHeight
                    val endY = if (core == slices - 1) height else startY + sliceHeight
                    val colorArray = IntArray(blockSize * blockSize)
                    val isPixelSet = BooleanArray(blockSize * blockSize)

                    var blockY = startY
                    while (blockY < endY) {
                        var blockX = 0
                        while (blockX < width) {
                            val currentBlockHeight =
                                if (blockY + blockSize > endY) endY - blockY else blockSize
                            val currentBlockWidth =
                                if (blockX + blockSize > width) width - blockX else blockSize

                            if (useBlockOptimization) {
                                isPixelSet.fill(false, 0, currentBlockWidth * currentBlockHeight)
                                var allSame = true
                                var firstColor = -1

                                samplingLoop@ for (j in 0 until samplesPerAxis) {
                                    val y = j * (currentBlockHeight - 1) / (samplesPerAxis - 1)
                                    for (i in 0 until samplesPerAxis) {
                                        val x = i * (currentBlockWidth - 1) / (samplesPerAxis - 1)
                                        val color = pixelColor(blockX + x, blockY + y, transform)
                                        val index = y * currentBlockWidth + x
                                        colorArray[index] = color
                                        isPixelSet[index] = true

                                        if (i == 0 && j == 0) {
                                            firstColor = color
                                        } else if (color != firstColor) {
                                            allSame = false
                                            break@samplingLoop
                                        }
                                    }
                                }

                                if (allSame) {
                                    fillBlock(currentBlockHeight, currentBlockWidth, colorArray, blockX, blockY, firstColor)
                                } else {
                                    renderBlock(currentBlockHeight, currentBlockWidth, colorArray, blockX, blockY, transform, isPixelSet)
                                }
                            } else {
                                renderBlock(currentBlockHeight, currentBlockWidth, colorArray, blockX, blockY, transform)
                            }
                            blockX += blockSize
                        }
                        blockY += blockSize
                    }
                }
            }
            jobs.awaitAll()
        }

        @Suppress("NOTHING_TO_INLINE")
        private inline fun renderBlock(
            currentBlockHeight: Int,
            currentBlockWidth: Int,
            colorArray: IntArray,
            blockX: Int,
            blockY: Int,
            transform: AffineTransform,
            isPixelSet: BooleanArray? = null
        ) {
            var y = 0
            while (y < currentBlockHeight) {
                var x = 0
                while (x < currentBlockWidth) {
                    val index = y * currentBlockWidth + x
                    if (isPixelSet == null || !isPixelSet[index]) {
                        colorArray[index] =
                            pixelColor(blockX + x, blockY + y, transform)
                    }
                    x++
                }
                y++
            }

            backBitmap?.setPixels(
                colorArray,
                0,
                currentBlockWidth,
                blockX,
                blockY,
                currentBlockWidth,
                currentBlockHeight
            )
        }

        @Suppress("NOTHING_TO_INLINE")
        private inline fun fillBlock(
            currentBlockHeight: Int,
            currentBlockWidth: Int,
            colorArray: IntArray,
            blockX: Int,
            blockY: Int,
            sampleColor: Int
        ) {
            var i = 0
            val limit = currentBlockWidth * currentBlockHeight
            while (i < limit) {
                colorArray[i] = sampleColor
                i++
            }
            backBitmap?.setPixels(
                colorArray,
                0,
                currentBlockWidth,
                blockX,
                blockY,
                currentBlockWidth,
                currentBlockHeight
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            canvas.drawColor(Color.BLACK)
            synchronized(bufferLock) {
                frontBitmap?.let { canvas.drawBitmap(it, null, destRect, paint) }
            }
            fpsDisplay.draw(canvas)
        }

        abstract fun pixelColor(x: Int, y: Int, transform: AffineTransform): Int
        abstract fun searchZoomPoint(width: Int, height: Int, isPortrait: Boolean, cXmin: Double, cYmin: Double, cWidth: Double, cHeight: Double): Pair<Double, Double>
        abstract fun getInitialCoordinates(isPortrait: Boolean, width: Int, height: Int): DoubleArray
        abstract val scaleFactor: Int
        abstract val precisionLimit: Double
        abstract val zoomFactor: Double
        abstract val slices: Int
        abstract val useBlockOptimization: Boolean
        abstract val blockDensityDivisor: Int
        abstract val minBlockSize: Int
        abstract val maxBlockSize: Int
        abstract val samplesPerAxis: Int
        abstract val cropToSquare: Boolean
    }

    companion object {
        const val MAX_ITERATIONS = 256
        const val LOOKUP_SCALE_FACTOR = 65535.0 / 4.0
    }
}
