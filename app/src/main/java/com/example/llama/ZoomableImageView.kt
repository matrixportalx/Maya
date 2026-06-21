package tr.maya

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView

/**
 * İki parmakla yakınlaştırma (pinch-to-zoom) ve sürükleme desteği olan ImageView.
 * Mayagram'da tam ekran görüntü önizlemesi için kullanılır.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ImageView(context, attrs) {

    private val matrixValues = Matrix()
    private var scaleFactor = 1f
    private val minScale = 1f
    private val maxScale = 5f

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var posX = 0f
    private var posY = 0f
    private var isDragging = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(minScale, maxScale)
            applyMatrix()
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging && scaleFactor > 1f) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    posX += dx
                    posY += dy
                    lastTouchX = event.x
                    lastTouchY = event.y
                    applyMatrix()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                if (scaleFactor <= 1f) {
                    posX = 0f; posY = 0f
                    applyMatrix()
                }
            }
        }
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitImageToView()
    }

    /** Bitmap'i view alanına ortalayıp FIT_CENTER gibi yerleştirir (başlangıç durumu). */
    private fun fitImageToView() {
        val d = drawable ?: return
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val drawableWidth = d.intrinsicWidth.toFloat()
        val drawableHeight = d.intrinsicHeight.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f || drawableWidth <= 0f || drawableHeight <= 0f) return

        val scale = minOf(viewWidth / drawableWidth, viewHeight / drawableHeight)
        val dx = (viewWidth - drawableWidth * scale) / 2f
        val dy = (viewHeight - drawableHeight * scale) / 2f

        matrixValues.reset()
        matrixValues.postScale(scale, scale)
        matrixValues.postTranslate(dx, dy)
        imageMatrix = matrixValues

        // Zoom hesaplamalarının baz alacağı taban matrix'i sakla
        baseMatrix.set(matrixValues)
        scaleFactor = 1f
        posX = 0f; posY = 0f
    }

    private val baseMatrix = Matrix()

    private fun applyMatrix() {
        matrixValues.set(baseMatrix)
        matrixValues.postScale(scaleFactor, scaleFactor, width / 2f, height / 2f)
        matrixValues.postTranslate(posX, posY)
        imageMatrix = matrixValues
    }
}
