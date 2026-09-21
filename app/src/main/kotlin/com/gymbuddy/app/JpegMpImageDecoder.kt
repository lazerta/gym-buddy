package com.gymbuddy.app

import android.graphics.BitmapFactory
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.gymbuddy.frames.FrameDecoder

class JpegMpImageDecoder : FrameDecoder<MPImage> {
    override fun decode(
        encodedBytes: ByteArray,
        mimeType: String,
        width: Int,
        height: Int,
    ): MPImage {
        require(mimeType == "image/jpeg" || mimeType == "image/png") {
            "Unsupported simulator frame mime type: " + mimeType
        }

        val bitmap = BitmapFactory.decodeByteArray(
            encodedBytes,
            0,
            encodedBytes.size,
        ) ?: error("Failed to decode simulator frame")

        require(bitmap.width == width && bitmap.height == height) {
            "Decoded frame size " +
                bitmap.width + "x" + bitmap.height +
                " does not match descriptor " +
                width + "x" + height
        }

        return BitmapImageBuilder(bitmap).build()
    }
}
