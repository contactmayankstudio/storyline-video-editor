package com.video.engine.camera

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark

data class SmoothedFaceData(
    val hasFace: Boolean,
    val bounds: RectF = RectF(),
    val leftEye: PointF? = null,
    val rightEye: PointF? = null,
    val noseBase: PointF? = null,
    val mouthCenter: PointF? = null,
    val leftCheek: PointF? = null,
    val rightCheek: PointF? = null,
    val mouthLeft: PointF? = null,
    val mouthRight: PointF? = null,
    val headEulerZ: Float = 0f,
    val headEulerY: Float = 0f,
    val headEulerX: Float = 0f,
    val isSmiling: Boolean = false,
    val smilingProbability: Float = 0f,
    val isWinking: Boolean = false,
    val isLeftEyeWinking: Boolean = false,
    val isRightEyeWinking: Boolean = false,
    val isMouthOpen: Boolean = false,
    val mouthOpenRatio: Float = 0f
)

class FaceLandmarkTracker {

    private val detector: FaceDetector

    // Smoothing state
    private var smoothedData: SmoothedFaceData = SmoothedFaceData(hasFace = false)
    private val smoothingFactor = 0.35f // 0 = previous, 1 = instant

    @Volatile
    private var isProcessing = false

    init {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.12f)
            .enableTracking()
            .build()

        detector = FaceDetection.getClient(options)
    }

    fun processFrame(
        bitmap: Bitmap,
        isFrontCamera: Boolean,
        onResult: (SmoothedFaceData) -> Unit
    ): Boolean {
        if (isProcessing) return false
        isProcessing = true

        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            detector.process(image)
                .addOnSuccessListener { faces ->
                    try {
                        val primaryFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                        if (primaryFace != null) {
                            val frameWidth = bitmap.width.toFloat()
                            val frameHeight = bitmap.height.toFloat()
                            val updated = mapAndSmoothFace(primaryFace, frameWidth, frameHeight, isFrontCamera)
                            smoothedData = updated
                            onResult(updated)
                        } else {
                            // Gradual fade out
                            smoothedData = smoothedData.copy(hasFace = false)
                            onResult(smoothedData)
                        }
                    } finally {
                        isProcessing = false
                        try { bitmap.recycle() } catch (_: Exception) {}
                    }
                }
                .addOnFailureListener {
                    isProcessing = false
                    try { bitmap.recycle() } catch (_: Exception) {}
                }
            return true
        } catch (e: Exception) {
            isProcessing = false
            try { bitmap.recycle() } catch (_: Exception) {}
            return false
        }
    }

    private fun mapAndSmoothFace(
        face: Face,
        frameW: Float,
        frameH: Float,
        isFrontCamera: Boolean
    ): SmoothedFaceData {
        // Raw bounding box normalized [0..1]
        val rawBox = face.boundingBox
        var left = rawBox.left.toFloat() / frameW
        var right = rawBox.right.toFloat() / frameW
        val top = rawBox.top.toFloat() / frameH
        val bottom = rawBox.bottom.toFloat() / frameH

        // Mirror X for front camera
        if (isFrontCamera) {
            val origLeft = left
            left = 1f - right
            right = 1f - origLeft
        }

        val targetBounds = RectF(
            left.coerceIn(0f, 1f),
            top.coerceIn(0f, 1f),
            right.coerceIn(0f, 1f),
            bottom.coerceIn(0f, 1f)
        )

        // Landmarks
        val rawLeftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val rawRightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val rawNose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position
        val rawMouthBottom = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position
        val rawLeftCheek = face.getLandmark(FaceLandmark.LEFT_CHEEK)?.position
        val rawRightCheek = face.getLandmark(FaceLandmark.RIGHT_CHEEK)?.position
        val rawMouthLeft = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
        val rawMouthRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position

        val mapPoint = { p: PointF? ->
            if (p != null) {
                var px = p.x / frameW
                if (isFrontCamera) {
                    px = 1f - px
                }
                PointF(px.coerceIn(0f, 1f), (p.y / frameH).coerceIn(0f, 1f))
            } else null
        }

        // mapPoint already reflects X (1f - px) on front camera.
        // Direct assignment keeps targetLeftEye on screen-left (x ~ 0.30) and targetRightEye on screen-right (x ~ 0.70)
        val targetLeftEye = mapPoint(rawLeftEye)
        val targetRightEye = mapPoint(rawRightEye)
        val targetNose = mapPoint(rawNose)
        val targetMouthBottom = mapPoint(rawMouthBottom)

        // Cheeks
        var targetLeftCheek = mapPoint(rawLeftCheek)
        var targetRightCheek = mapPoint(rawRightCheek)

        // Mouth corners
        val targetMouthLeft = mapPoint(rawMouthLeft)
        val targetMouthRight = mapPoint(rawMouthRight)

        // Fallbacks for cheeks if ML Kit didn't detect them
        if (targetLeftCheek == null && targetLeftEye != null) {
            val cheekY = if (targetNose != null) targetNose.y else targetLeftEye.y + 0.12f
            targetLeftCheek = PointF(targetLeftEye.x, cheekY.coerceIn(0f, 1f))
        }
        if (targetRightCheek == null && targetRightEye != null) {
            val cheekY = if (targetNose != null) targetNose.y else targetRightEye.y + 0.12f
            targetRightCheek = PointF(targetRightEye.x, cheekY.coerceIn(0f, 1f))
        }

        // Accurate mouth center
        val targetMouthCenterX = when {
            targetMouthLeft != null && targetMouthRight != null -> (targetMouthLeft.x + targetMouthRight.x) / 2f
            targetNose != null -> targetNose.x
            else -> targetBounds.centerX()
        }
        val targetMouthCenterY = when {
            targetNose != null && targetMouthBottom != null -> (targetNose.y + targetMouthBottom.y * 2f) / 3f
            targetMouthBottom != null -> targetMouthBottom.y - 0.03f
            targetNose != null -> targetNose.y + 0.08f
            else -> targetBounds.top + targetBounds.height() * 0.72f
        }
        val targetMouthCenter = PointF(targetMouthCenterX.coerceIn(0f, 1f), targetMouthCenterY.coerceIn(0f, 1f))

        val targetEulerZ = if (isFrontCamera) -face.headEulerAngleZ else face.headEulerAngleZ
        val targetEulerY = face.headEulerAngleY
        val targetEulerX = face.headEulerAngleX

        // Smile detection
        val rawSmilingProb = (face.smilingProbability ?: 0f).coerceIn(0f, 1f)
        val isSmiling = rawSmilingProb > 0.65f

        // Asymmetric wink detection: screen left vs screen right eye
        val leftOpen = face.leftEyeOpenProbability ?: 1f
        val rightOpen = face.rightEyeOpenProbability ?: 1f
        val isLeftEyeWinking = leftOpen < 0.25f && rightOpen > 0.70f
        val isRightEyeWinking = rightOpen < 0.25f && leftOpen > 0.70f
        val isWinking = isLeftEyeWinking || isRightEyeWinking

        // Calculate mouth openness ratio based on nose-to-mouth distance vs face height
        var mouthDistRatio = 0f
        if (rawNose != null && rawMouthBottom != null) {
            val faceH = rawBox.height().toFloat().coerceAtLeast(1f)
            val dist = rawMouthBottom.y - rawNose.y
            mouthDistRatio = (dist / faceH).coerceIn(0f, 1f)
        }
        val isMouthOpen = mouthDistRatio > 0.31f
        val mouthOpenRatio = ((mouthDistRatio - 0.22f) / 0.18f).coerceIn(0f, 1f)

        // Apply smoothing
        val prev = smoothedData
        if (!prev.hasFace) {
            return SmoothedFaceData(
                hasFace = true,
                bounds = targetBounds,
                leftEye = targetLeftEye,
                rightEye = targetRightEye,
                noseBase = targetNose,
                mouthCenter = targetMouthCenter,
                leftCheek = targetLeftCheek,
                rightCheek = targetRightCheek,
                mouthLeft = targetMouthLeft,
                mouthRight = targetMouthRight,
                headEulerZ = targetEulerZ,
                headEulerY = targetEulerY,
                headEulerX = targetEulerX,
                isSmiling = isSmiling,
                smilingProbability = rawSmilingProb,
                isWinking = isWinking,
                isLeftEyeWinking = isLeftEyeWinking,
                isRightEyeWinking = isRightEyeWinking,
                isMouthOpen = isMouthOpen,
                mouthOpenRatio = mouthOpenRatio
            )
        }

        val smoothF = { curr: Float, target: Float -> curr + (target - curr) * smoothingFactor }
        val smoothPoint = { prevP: PointF?, targetP: PointF? ->
            if (prevP != null && targetP != null) {
                PointF(smoothF(prevP.x, targetP.x), smoothF(prevP.y, targetP.y))
            } else targetP ?: prevP
        }

        val smoothedBounds = RectF(
            smoothF(prev.bounds.left, targetBounds.left),
            smoothF(prev.bounds.top, targetBounds.top),
            smoothF(prev.bounds.right, targetBounds.right),
            smoothF(prev.bounds.bottom, targetBounds.bottom)
        )

        val smoothedMouthRatio = smoothF(prev.mouthOpenRatio, mouthOpenRatio)
        val smoothedSmilingProb = smoothF(prev.smilingProbability, rawSmilingProb)

        return SmoothedFaceData(
            hasFace = true,
            bounds = smoothedBounds,
            leftEye = smoothPoint(prev.leftEye, targetLeftEye),
            rightEye = smoothPoint(prev.rightEye, targetRightEye),
            noseBase = smoothPoint(prev.noseBase, targetNose),
            mouthCenter = smoothPoint(prev.mouthCenter, targetMouthCenter),
            leftCheek = smoothPoint(prev.leftCheek, targetLeftCheek),
            rightCheek = smoothPoint(prev.rightCheek, targetRightCheek),
            mouthLeft = smoothPoint(prev.mouthLeft, targetMouthLeft),
            mouthRight = smoothPoint(prev.mouthRight, targetMouthRight),
            headEulerZ = smoothF(prev.headEulerZ, targetEulerZ),
            headEulerY = smoothF(prev.headEulerY, targetEulerY),
            headEulerX = smoothF(prev.headEulerX, targetEulerX),
            isSmiling = smoothedSmilingProb > 0.60f || isSmiling,
            smilingProbability = smoothedSmilingProb,
            isWinking = isWinking,
            isLeftEyeWinking = isLeftEyeWinking,
            isRightEyeWinking = isRightEyeWinking,
            isMouthOpen = smoothedMouthRatio > 0.38f || isMouthOpen,
            mouthOpenRatio = smoothedMouthRatio
        )
    }

    fun release() {
        try {
            detector.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
