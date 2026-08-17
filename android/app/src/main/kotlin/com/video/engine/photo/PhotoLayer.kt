package com.video.engine.photo

import android.graphics.Bitmap
import android.graphics.Color

data class PhotoPoint(
    val x: Float,
    val y: Float,
)

data class PhotoTextLayer(
    val id: Int,
    var text: String = "Double tap to edit",
    var x: Float,
    var y: Float,
    var scale: Float = 1f,
    var rotationDeg: Float = 0f,
    var flipX: Boolean = false,
    var flipY: Boolean = false,
    var textColor: Int = Color.rgb(20, 54, 84),
    var backgroundColor: Int = Color.TRANSPARENT,
    var strokeColor: Int = Color.TRANSPARENT,
    var strokeWidth: Float = 0f,
    var shadowEnabled: Boolean = false,
    var bold: Boolean = true,
    var italic: Boolean = false,
    var underline: Boolean = false,
    var fontFamily: String = "sans",
    var fontSize: Float = 72f,
    var letterSpacing: Float = 0f,
    var lineHeight: Float = 1.1f,
    var alignment: Int = TEXT_ALIGN_CENTER,
    var backgroundPadding: Float = 18f,
    var backgroundCornerRadius: Float = 12f,
    var gradientEnabled: Boolean = false,
    var gradientStartColor: Int = Color.WHITE,
    var gradientEndColor: Int = Color.rgb(72, 198, 255),
    var shadowBlur: Float = 10f,
    var shadowOffsetX: Float = 4f,
    var shadowOffsetY: Float = 5f,
    var shadowOpacity: Int = 180,
    var curveAmount: Float = 0f,
    var depth: Float = 0f,
    var opacity: Int = 255,
    var visible: Boolean = true,
    var locked: Boolean = false,
    var blendMode: Int = BLEND_NORMAL,
)

data class PhotoShapeLayer(
    val id: Int,
    var shapeType: Int = SHAPE_RECTANGLE,
    var x: Float,
    var y: Float,
    var width: Float = 360f,
    var height: Float = 240f,
    var scale: Float = 1f,
    var rotationDeg: Float = 0f,
    var flipX: Boolean = false,
    var flipY: Boolean = false,
    var fillColor: Int = Color.rgb(46, 180, 132),
    var strokeColor: Int = Color.WHITE,
    var strokeWidth: Float = 0f,
    var cornerRadius: Float = 24f,
    var gradientEnabled: Boolean = false,
    var gradientStartColor: Int = Color.rgb(255, 214, 64),
    var gradientEndColor: Int = Color.rgb(77, 171, 247),
    var shadowEnabled: Boolean = false,
    var glowEnabled: Boolean = false,
    var opacity: Int = 255,
    var visible: Boolean = true,
    var locked: Boolean = false,
    var blendMode: Int = BLEND_NORMAL,
)

data class PhotoImageLayer(
    val id: Int,
    var uri: String? = null,
    var bitmap: Bitmap? = null,
    var x: Float,
    var y: Float,
    var width: Float = 520f,
    var height: Float = 520f,
    var scale: Float = 1f,
    var rotationDeg: Float = 0f,
    var flipX: Boolean = false,
    var flipY: Boolean = false,
    var brightness: Float = 0f,
    var contrast: Float = 1f,
    var saturation: Float = 1f,
    var temperature: Float = 0f,
    var blur: Float = 0f,
    var borderColor: Int = Color.WHITE,
    var borderWidth: Float = 0f,
    var cornerRadius: Float = 0f,
    var shadowEnabled: Boolean = false,
    var shadowColor: Int = Color.argb(150, 0, 0, 0),
    var shadowBlur: Float = 22f,
    var shadowOffsetX: Float = 8f,
    var shadowOffsetY: Float = 10f,
    var opacity: Int = 255,
    var visible: Boolean = true,
    var locked: Boolean = false,
    var blendMode: Int = BLEND_NORMAL,
)

data class PhotoBrushStroke(
    val id: Int,
    var brushType: Int = BRUSH_PEN,
    var color: Int = Color.WHITE,
    var size: Float = 18f,
    var opacity: Int = 255,
    var eraser: Boolean = false,
    var points: MutableList<PhotoPoint> = mutableListOf(),
    var visible: Boolean = true,
)

const val TEXT_ALIGN_LEFT = 0
const val TEXT_ALIGN_CENTER = 1
const val TEXT_ALIGN_RIGHT = 2

const val SHAPE_RECTANGLE = 0
const val SHAPE_CIRCLE = 1
const val SHAPE_TRIANGLE = 2
const val SHAPE_LINE = 3
const val SHAPE_STAR = 4
const val SHAPE_ARROW = 5

const val BRUSH_PEN = 0
const val BRUSH_MARKER = 1
const val BRUSH_NEON = 2

const val BLEND_NORMAL = 0
const val BLEND_MULTIPLY = 1
const val BLEND_OVERLAY = 2
const val BLEND_SCREEN = 3

const val PHOTO_LAYER_TEXT = 0
const val PHOTO_LAYER_SHAPE = 1
const val PHOTO_LAYER_BRUSH = 2
const val PHOTO_LAYER_IMAGE = 3
const val PHOTO_LAYER_BACKGROUND = 4

data class PhotoLayerItem(
    val type: Int,
    val id: Int,
    val title: String,
    val visible: Boolean,
    val locked: Boolean,
)
