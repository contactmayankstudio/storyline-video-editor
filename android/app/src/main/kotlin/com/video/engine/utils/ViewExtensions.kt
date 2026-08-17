package com.video.engine.utils

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

fun View.addPremiumPressEffect() {
    this.setOnTouchListener { v, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(150).start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
            }
        }
        false // Let click listeners still handle the tap
    }
}

fun View.addBouncyTouchEffect() {
    val scaleXAnim = SpringAnimation(this, DynamicAnimation.SCALE_X, 1.0f).apply {
        spring.stiffness = SpringForce.STIFFNESS_MEDIUM
        spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
    }
    val scaleYAnim = SpringAnimation(this, DynamicAnimation.SCALE_Y, 1.0f).apply {
        spring.stiffness = SpringForce.STIFFNESS_MEDIUM
        spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
    }

    this.setOnTouchListener { v, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                scaleXAnim.animateToFinalPosition(0.85f)
                scaleYAnim.animateToFinalPosition(0.85f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                scaleXAnim.animateToFinalPosition(1.0f)
                scaleYAnim.animateToFinalPosition(1.0f)
            }
        }
        false
    }
}
