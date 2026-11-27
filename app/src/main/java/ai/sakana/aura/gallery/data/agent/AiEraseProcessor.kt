package ai.sakana.aura.gallery.data.agent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/** Runs LaMa locally over a context crop around the user's mask, then composites only masked pixels. */
object AiEraseProcessor {
    private const val INPUT_SIZE = 512

    fun erase(original: Bitmap, mask: Bitmap, modelFile: File): Bitmap {
        require(original.width == mask.width && original.height == mask.height) {
            "The erase mask must match the source image dimensions."
        }
        require(modelFile.isFile && modelFile.length() >= 80_000_000L) { "Download the local LaMa inpainting model first." }
        val bounds = maskBounds(mask) ?: error("Paint over an object or use Detect Text to choose an erase region.")
        val contextPadding = max(96, max(bounds.width(), bounds.height()) / 2)
        val left = (bounds.left - contextPadding).coerceAtLeast(0)
        val top = (bounds.top - contextPadding).coerceAtLeast(0)
        val right = (bounds.right + contextPadding).coerceAtMost(original.width)
        val bottom = (bounds.bottom + contextPadding).coerceAtMost(original.height)
        val width = right - left
        val height = bottom - top
        require(width > 0 && height > 0) { "The erase mask is empty." }

        val imageCrop = Bitmap.createBitmap(original, left, top, width, height)
        val maskCrop = Bitmap.createBitmap(mask, left, top, width, height)
        val scaledImage = Bitmap.createScaledBitmap(imageCrop, INPUT_SIZE, INPUT_SIZE, true)
        val scaledMask = Bitmap.createScaledBitmap(maskCrop, INPUT_SIZE, INPUT_SIZE, true)
        try {
            val pixelCount = INPUT_SIZE * INPUT_SIZE
            val pixels = IntArray(pixelCount)
            scaledImage.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            val imageValues = FloatArray(pixelCount * 3)
            val maskValues = FloatArray(pixelCount)
            pixels.forEachIndexed { index, pixel ->
                imageValues[index] = Color.red(pixel) / 255f
                imageValues[pixelCount + index] = Color.green(pixel) / 255f
                imageValues[(pixelCount * 2) + index] = Color.blue(pixel) / 255f
            }
            scaledMask.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            pixels.forEachIndexed { index, pixel ->
                maskValues[index] = if (Color.alpha(pixel) > 12) 1f else 0f
            }

            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
            val session = environment.createSession(modelFile.absolutePath, options)
            options.close()
            val outputPixels = try {
                val imageTensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(imageValues), longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()))
                val maskTensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(maskValues), longArrayOf(1, 1, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()))
                imageTensor.use { imageInput ->
                    maskTensor.use { maskInput ->
                        session.run(mapOf("image" to imageInput, "mask" to maskInput)).use { result ->
                            val output = result.get(0) as? OnnxTensor ?: error("LaMa returned no image tensor.")
                            val values = flatten(output.value)
                            require(values.size == pixelCount * 3) { "The inpainting model returned an unexpected image shape." }
                            val filled = IntArray(pixelCount)
                            for (i in 0 until pixelCount) {
                                val red = values[i].toInt().coerceIn(0, 255)
                                val green = values[pixelCount + i].toInt().coerceIn(0, 255)
                                val blue = values[(pixelCount * 2) + i].toInt().coerceIn(0, 255)
                                filled[i] = Color.rgb(red, green, blue)
                            }
                            filled
                        }
                    }
                }
            } finally {
                session.close()
            }

            val generatedCrop = Bitmap.createBitmap(outputPixels, INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
            val fillAtSourceSize = Bitmap.createScaledBitmap(generatedCrop, width, height, true)
            val maskedFill = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val maskedCanvas = Canvas(maskedFill)
            maskedCanvas.drawBitmap(fillAtSourceSize, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            }
            maskedCanvas.drawBitmap(maskCrop, 0f, 0f, maskPaint)
            maskPaint.xfermode = null

            val output = original.copy(Bitmap.Config.ARGB_8888, true)
                ?: error("Aura couldn't allocate a new image for the edited copy.")
            Canvas(output).drawBitmap(maskedFill, left.toFloat(), top.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))

            generatedCrop.recycle()
            fillAtSourceSize.recycle()
            maskedFill.recycle()
            return output
        } finally {
            if (!scaledImage.isRecycled) scaledImage.recycle()
            if (!scaledMask.isRecycled) scaledMask.recycle()
            if (!imageCrop.isRecycled) imageCrop.recycle()
            if (!maskCrop.isRecycled) maskCrop.recycle()
        }
    }

    private fun maskBounds(mask: Bitmap): android.graphics.Rect? {
        val step = 1
        var left = mask.width
        var top = mask.height
        var right = -1
        var bottom = -1
        var y = 0
        while (y < mask.height) {
            var x = 0
            while (x < mask.width) {
                if (Color.alpha(mask.getPixel(x, y)) > 12) {
                    left = min(left, x)
                    top = min(top, y)
                    right = max(right, x)
                    bottom = max(bottom, y)
                }
                x += step
            }
            y += step
        }
        if (right < left || bottom < top) return null
        return android.graphics.Rect(
            (left - step).coerceAtLeast(0),
            (top - step).coerceAtLeast(0),
            (right + step + 1).coerceAtMost(mask.width),
            (bottom + step + 1).coerceAtMost(mask.height),
        )
    }

    private fun flatten(value: Any?): FloatArray = when (value) {
        is FloatArray -> value
        is Array<*> -> value.flatMap { flatten(it).asList() }.toFloatArray()
        else -> error("Unsupported LaMa output: ${value?.javaClass?.name ?: "null"}")
    }
}
