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
            val newScale = (scaleFactor * detector.scaleFactor).coerceIn(minScale, maxScale)
            scaleFactor = newScale
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
                // Yakınlaştırma kapalıyken pozisyonu sıfırla
                if (scaleFactor <= 1f) {
                    posX = 0f; posY = 0f
                }
            }
        }
        return true
    }

    /** Çift dokunma ile sıfırlama (isteğe bağlı kullanım için dışarıdan çağrılabilir) */
    fun resetZoom() {
        scaleFactor = 1f
        posX = 0f; posY = 0f
        applyMatrix()
    }

    private fun applyMatrix() {
        matrixValues.reset()
        matrixValues.postScale(scaleFactor, scaleFactor, width / 2f, height / 2f)
        matrixValues.postTranslate(posX, posY)
        imageMatrix = matrixValues
    }
}
