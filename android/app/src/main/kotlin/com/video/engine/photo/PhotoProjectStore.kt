package com.video.engine.photo

import android.content.Context
import android.graphics.Color
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class PhotoProjectState(
    val imageUri: String?,
    val canvasBackgroundColor: Int,
    val backgroundRotationDeg: Float,
    val canvasOutputWidth: Int,
    val canvasOutputHeight: Int,
    val canvasSizeLabel: String,
    val imageBrightness: Float,
    val imageContrast: Float,
    val imageSaturation: Float,
    val imageTemperature: Float,
    val imageBlur: Float,
    val imageVignette: Float,
    val imageFilterIntensity: Float,
    val imageTintColor: Int,
    val imageTintStrength: Float,
    val imageMaskMode: Int,
    val imageCutoutMode: Int,
    val imageCutoutThreshold: Float,
    val imageCutoutFeather: Float,
    val imageCurveShadows: Float,
    val imageCurveMidtones: Float,
    val imageCurveHighlights: Float,
    val transparentBackground: Boolean,
    val layers: List<PhotoTextLayer>,
    val imageLayers: List<PhotoImageLayer>,
    val shapeLayers: List<PhotoShapeLayer>,
    val brushStrokes: List<PhotoBrushStroke>,
)

object PhotoProjectStore {
    private const val PROJECT_FILE = "photo_autosave.json"
    private const val PREFS_NAME = "photo_project_store"
    private const val PREF_ACTIVE_PROJECT_PATH = "active_project_path"

    fun latest(context: Context): File? =
        all(context).firstOrNull()

    fun all(context: Context): List<File> {
        val legacyFile = legacyAutosaveFile(context).takeIf { it.isFile && it.length() > 0L }
        val projectFiles = projectsDir(context)
            .listFiles { file -> file.isFile && file.extension.equals("json", ignoreCase = true) }
            ?.toList()
            .orEmpty()
        return buildList {
            legacyFile?.let(::add)
            addAll(projectFiles)
        }
            .distinctBy { it.absolutePath }
            .sortedByDescending { it.lastModified() }
    }

    fun startNewProject(context: Context) {
        setActiveProject(context, null)
    }

    fun setActiveProject(context: Context, file: File?) {
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        if (file == null) {
            editor.remove(PREF_ACTIVE_PROJECT_PATH)
        } else {
            editor.putString(PREF_ACTIVE_PROJECT_PATH, file.absolutePath)
        }
        editor.apply()
    }

    fun displayName(file: File): String {
        val prefix = if (file.name.equals(PROJECT_FILE, ignoreCase = true)) {
            "Last Photo Draft"
        } else {
            "Photo Draft"
        }
        val updatedAt = file.lastModified().takeIf { it > 0L } ?: return prefix
        val date = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(updatedAt))
        return "$prefix $date"
    }

    fun delete(context: Context, file: File): Boolean {
        val activePath = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_ACTIVE_PROJECT_PATH, null)
        val deleted = if (file.exists()) {
            runCatching { file.delete() }.getOrDefault(false)
        } else {
            true
        }
        if (activePath == file.absolutePath) {
            setActiveProject(context, null)
        }
        return deleted || !file.exists()
    }

    fun save(context: Context, imageUri: String?, canvasView: PhotoCanvasView, projectFile: File? = null) {
        val file = resolveProjectFile(context, projectFile)
        file.parentFile?.mkdirs()
        val layers = JSONArray()
        canvasView.textLayers.forEach { layer ->
            val item = JSONObject()
            item.put("id", layer.id)
            item.put("text", layer.text)
            item.put("x", layer.x.toDouble())
            item.put("y", layer.y.toDouble())
            item.put("scale", layer.scale.toDouble())
            item.put("rotationDeg", layer.rotationDeg.toDouble())
            item.put("flipX", layer.flipX)
            item.put("flipY", layer.flipY)
            item.put("textColor", layer.textColor)
            item.put("backgroundColor", layer.backgroundColor)
            item.put("strokeColor", layer.strokeColor)
            item.put("strokeWidth", layer.strokeWidth.toDouble())
            item.put("shadowEnabled", layer.shadowEnabled)
            item.put("bold", layer.bold)
            item.put("italic", layer.italic)
            item.put("underline", layer.underline)
            item.put("fontFamily", layer.fontFamily)
            item.put("fontSize", layer.fontSize.toDouble())
            item.put("letterSpacing", layer.letterSpacing.toDouble())
            item.put("lineHeight", layer.lineHeight.toDouble())
            item.put("alignment", layer.alignment)
            item.put("backgroundPadding", layer.backgroundPadding.toDouble())
            item.put("backgroundCornerRadius", layer.backgroundCornerRadius.toDouble())
            item.put("gradientEnabled", layer.gradientEnabled)
            item.put("gradientStartColor", layer.gradientStartColor)
            item.put("gradientEndColor", layer.gradientEndColor)
            item.put("shadowBlur", layer.shadowBlur.toDouble())
            item.put("shadowOffsetX", layer.shadowOffsetX.toDouble())
            item.put("shadowOffsetY", layer.shadowOffsetY.toDouble())
            item.put("shadowOpacity", layer.shadowOpacity)
            item.put("curveAmount", layer.curveAmount.toDouble())
            item.put("depth", layer.depth.toDouble())
            item.put("opacity", layer.opacity)
            item.put("visible", layer.visible)
            item.put("locked", layer.locked)
            item.put("blendMode", layer.blendMode)
            layers.put(item)
        }
        val shapes = JSONArray()
        canvasView.shapeLayers.forEach { layer ->
            val item = JSONObject()
            item.put("id", layer.id)
            item.put("shapeType", layer.shapeType)
            item.put("x", layer.x.toDouble())
            item.put("y", layer.y.toDouble())
            item.put("width", layer.width.toDouble())
            item.put("height", layer.height.toDouble())
            item.put("scale", layer.scale.toDouble())
            item.put("rotationDeg", layer.rotationDeg.toDouble())
            item.put("flipX", layer.flipX)
            item.put("flipY", layer.flipY)
            item.put("fillColor", layer.fillColor)
            item.put("strokeColor", layer.strokeColor)
            item.put("strokeWidth", layer.strokeWidth.toDouble())
            item.put("cornerRadius", layer.cornerRadius.toDouble())
            item.put("gradientEnabled", layer.gradientEnabled)
            item.put("gradientStartColor", layer.gradientStartColor)
            item.put("gradientEndColor", layer.gradientEndColor)
            item.put("shadowEnabled", layer.shadowEnabled)
            item.put("glowEnabled", layer.glowEnabled)
            item.put("opacity", layer.opacity)
            item.put("visible", layer.visible)
            item.put("locked", layer.locked)
            item.put("blendMode", layer.blendMode)
            shapes.put(item)
        }
        val imageLayers = JSONArray()
        canvasView.imageLayers.forEach { layer ->
            val item = JSONObject()
            item.put("id", layer.id)
            if (layer.uri == null) {
                item.put("uri", JSONObject.NULL)
            } else {
                item.put("uri", layer.uri)
            }
            item.put("x", layer.x.toDouble())
            item.put("y", layer.y.toDouble())
            item.put("width", layer.width.toDouble())
            item.put("height", layer.height.toDouble())
            item.put("scale", layer.scale.toDouble())
            item.put("rotationDeg", layer.rotationDeg.toDouble())
            item.put("flipX", layer.flipX)
            item.put("flipY", layer.flipY)
            item.put("brightness", layer.brightness.toDouble())
            item.put("contrast", layer.contrast.toDouble())
            item.put("saturation", layer.saturation.toDouble())
            item.put("temperature", layer.temperature.toDouble())
            item.put("blur", layer.blur.toDouble())
            item.put("borderColor", layer.borderColor)
            item.put("borderWidth", layer.borderWidth.toDouble())
            item.put("cornerRadius", layer.cornerRadius.toDouble())
            item.put("shadowEnabled", layer.shadowEnabled)
            item.put("shadowColor", layer.shadowColor)
            item.put("shadowBlur", layer.shadowBlur.toDouble())
            item.put("shadowOffsetX", layer.shadowOffsetX.toDouble())
            item.put("shadowOffsetY", layer.shadowOffsetY.toDouble())
            item.put("opacity", layer.opacity)
            item.put("visible", layer.visible)
            item.put("locked", layer.locked)
            item.put("blendMode", layer.blendMode)
            imageLayers.put(item)
        }
        val strokes = JSONArray()
        canvasView.brushStrokes.forEach { stroke ->
            val item = JSONObject()
            item.put("id", stroke.id)
            item.put("brushType", stroke.brushType)
            item.put("color", stroke.color)
            item.put("size", stroke.size.toDouble())
            item.put("opacity", stroke.opacity)
            item.put("eraser", stroke.eraser)
            item.put("visible", stroke.visible)
            val points = JSONArray()
            stroke.points.forEach { point ->
                points.put(
                    JSONObject()
                        .put("x", point.x.toDouble())
                        .put("y", point.y.toDouble()),
                )
            }
            item.put("points", points)
            strokes.put(item)
        }
        val root = JSONObject()
        root.put("version", 3)
        root.put("updatedAt", System.currentTimeMillis())
        root.put("projectName", displayName(file))
        if (imageUri == null) {
            root.put("imageUri", JSONObject.NULL)
        } else {
            root.put("imageUri", imageUri)
        }
        root.put("canvasBackgroundColor", canvasView.canvasBackgroundColor)
        root.put("backgroundRotationDeg", canvasView.backgroundRotationDeg.toDouble())
        root.put("canvasOutputWidth", canvasView.canvasOutputWidth)
        root.put("canvasOutputHeight", canvasView.canvasOutputHeight)
        root.put("canvasSizeLabel", canvasView.canvasSizeLabel)
        root.put("imageBrightness", canvasView.imageBrightness.toDouble())
        root.put("imageContrast", canvasView.imageContrast.toDouble())
        root.put("imageSaturation", canvasView.imageSaturation.toDouble())
        root.put("imageTemperature", canvasView.imageTemperature.toDouble())
        root.put("imageBlur", canvasView.imageBlur.toDouble())
        root.put("imageVignette", canvasView.imageVignette.toDouble())
        root.put("imageFilterIntensity", canvasView.imageFilterIntensity.toDouble())
        root.put("imageTintColor", canvasView.imageTintColor)
        root.put("imageTintStrength", canvasView.imageTintStrength.toDouble())
        root.put("imageMaskMode", canvasView.imageMaskMode)
        root.put("imageCutoutMode", canvasView.imageCutoutMode)
        root.put("imageCutoutThreshold", canvasView.imageCutoutThreshold.toDouble())
        root.put("imageCutoutFeather", canvasView.imageCutoutFeather.toDouble())
        root.put("imageCurveShadows", canvasView.imageCurveShadows.toDouble())
        root.put("imageCurveMidtones", canvasView.imageCurveMidtones.toDouble())
        root.put("imageCurveHighlights", canvasView.imageCurveHighlights.toDouble())
        root.put("transparentBackground", canvasView.transparentBackground)
        root.put("layers", layers)
        root.put("imageLayers", imageLayers)
        root.put("shapeLayers", shapes)
        root.put("brushStrokes", strokes)
        file.writeText(root.toString())
        setActiveProject(context, file)
    }

    fun load(context: Context, projectFile: File? = null): PhotoProjectState? {
        val file = projectFile?.takeIf { it.isFile && it.length() > 0L }
            ?: activeProjectFile(context)
            ?: latest(context)
            ?: return null
        val root = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null
        setActiveProject(context, file)
        val layersJson = root.optJSONArray("layers") ?: JSONArray()
        val layers = ArrayList<PhotoTextLayer>(layersJson.length())
        for (index in 0 until layersJson.length()) {
            val item = layersJson.optJSONObject(index) ?: continue
            layers.add(
                PhotoTextLayer(
                    id = item.optInt("id", index + 1),
                    text = item.optString("text", "Text"),
                    x = item.optDouble("x", 540.0).toFloat(),
                    y = item.optDouble("y", 540.0).toFloat(),
                    scale = item.optDouble("scale", 1.0).toFloat(),
                    rotationDeg = item.optDouble("rotationDeg", 0.0).toFloat(),
                    flipX = item.optBoolean("flipX", false),
                    flipY = item.optBoolean("flipY", false),
                    textColor = item.optInt("textColor", Color.rgb(20, 54, 84)),
                    backgroundColor = item.optInt("backgroundColor", Color.TRANSPARENT),
                    strokeColor = item.optInt("strokeColor", Color.TRANSPARENT),
                    strokeWidth = item.optDouble("strokeWidth", 0.0).toFloat(),
                    shadowEnabled = item.optBoolean("shadowEnabled", false),
                    bold = item.optBoolean("bold", true),
                    italic = item.optBoolean("italic", false),
                    underline = item.optBoolean("underline", false),
                    fontFamily = item.optString("fontFamily", "sans"),
                    fontSize = item.optDouble("fontSize", 72.0).toFloat(),
                    letterSpacing = item.optDouble("letterSpacing", 0.0).toFloat(),
                    lineHeight = item.optDouble("lineHeight", 1.1).toFloat(),
                    alignment = item.optInt("alignment", TEXT_ALIGN_CENTER),
                    backgroundPadding = item.optDouble("backgroundPadding", 18.0).toFloat(),
                    backgroundCornerRadius = item.optDouble("backgroundCornerRadius", 12.0).toFloat(),
                    gradientEnabled = item.optBoolean("gradientEnabled", false),
                    gradientStartColor = item.optInt("gradientStartColor", Color.WHITE),
                    gradientEndColor = item.optInt("gradientEndColor", Color.rgb(72, 198, 255)),
                    shadowBlur = item.optDouble("shadowBlur", 10.0).toFloat(),
                    shadowOffsetX = item.optDouble("shadowOffsetX", 4.0).toFloat(),
                    shadowOffsetY = item.optDouble("shadowOffsetY", 5.0).toFloat(),
                    shadowOpacity = item.optInt("shadowOpacity", 180),
                    curveAmount = item.optDouble("curveAmount", 0.0).toFloat(),
                    depth = item.optDouble("depth", 0.0).toFloat(),
                    opacity = item.optInt("opacity", 255),
                    visible = item.optBoolean("visible", true),
                    locked = item.optBoolean("locked", false),
                    blendMode = item.optInt("blendMode", BLEND_NORMAL),
                ),
            )
        }
        val shapeLayersJson = root.optJSONArray("shapeLayers") ?: JSONArray()
        val shapeLayers = ArrayList<PhotoShapeLayer>(shapeLayersJson.length())
        for (index in 0 until shapeLayersJson.length()) {
            val item = shapeLayersJson.optJSONObject(index) ?: continue
            shapeLayers.add(
                PhotoShapeLayer(
                    id = item.optInt("id", index + 1001),
                    shapeType = item.optInt("shapeType", SHAPE_RECTANGLE),
                    x = item.optDouble("x", 540.0).toFloat(),
                    y = item.optDouble("y", 540.0).toFloat(),
                    width = item.optDouble("width", 360.0).toFloat(),
                    height = item.optDouble("height", 240.0).toFloat(),
                    scale = item.optDouble("scale", 1.0).toFloat(),
                    rotationDeg = item.optDouble("rotationDeg", 0.0).toFloat(),
                    flipX = item.optBoolean("flipX", false),
                    flipY = item.optBoolean("flipY", false),
                    fillColor = item.optInt("fillColor", Color.rgb(46, 180, 132)),
                    strokeColor = item.optInt("strokeColor", Color.WHITE),
                    strokeWidth = item.optDouble("strokeWidth", 0.0).toFloat(),
                    cornerRadius = item.optDouble("cornerRadius", 24.0).toFloat(),
                    gradientEnabled = item.optBoolean("gradientEnabled", false),
                    gradientStartColor = item.optInt("gradientStartColor", Color.rgb(255, 214, 64)),
                    gradientEndColor = item.optInt("gradientEndColor", Color.rgb(77, 171, 247)),
                    shadowEnabled = item.optBoolean("shadowEnabled", false),
                    glowEnabled = item.optBoolean("glowEnabled", false),
                    opacity = item.optInt("opacity", 255),
                    visible = item.optBoolean("visible", true),
                    locked = item.optBoolean("locked", false),
                    blendMode = item.optInt("blendMode", BLEND_NORMAL),
                ),
            )
        }
        val imageLayersJson = root.optJSONArray("imageLayers") ?: JSONArray()
        val imageLayers = ArrayList<PhotoImageLayer>(imageLayersJson.length())
        for (index in 0 until imageLayersJson.length()) {
            val item = imageLayersJson.optJSONObject(index) ?: continue
            imageLayers.add(
                PhotoImageLayer(
                    id = item.optInt("id", index + 3001),
                    uri = item.optString("uri").takeIf { it.isNotBlank() && it != "null" },
                    bitmap = null,
                    x = item.optDouble("x", 540.0).toFloat(),
                    y = item.optDouble("y", 540.0).toFloat(),
                    width = item.optDouble("width", 520.0).toFloat(),
                    height = item.optDouble("height", 520.0).toFloat(),
                    scale = item.optDouble("scale", 1.0).toFloat(),
                    rotationDeg = item.optDouble("rotationDeg", 0.0).toFloat(),
                    flipX = item.optBoolean("flipX", false),
                    flipY = item.optBoolean("flipY", false),
                    brightness = item.optDouble("brightness", 0.0).toFloat(),
                    contrast = item.optDouble("contrast", 1.0).toFloat(),
                    saturation = item.optDouble("saturation", 1.0).toFloat(),
                    temperature = item.optDouble("temperature", 0.0).toFloat(),
                    blur = item.optDouble("blur", 0.0).toFloat(),
                    borderColor = item.optInt("borderColor", Color.WHITE),
                    borderWidth = item.optDouble("borderWidth", 0.0).toFloat(),
                    cornerRadius = item.optDouble("cornerRadius", 0.0).toFloat(),
                    shadowEnabled = item.optBoolean("shadowEnabled", false),
                    shadowColor = item.optInt("shadowColor", Color.argb(150, 0, 0, 0)),
                    shadowBlur = item.optDouble("shadowBlur", 22.0).toFloat(),
                    shadowOffsetX = item.optDouble("shadowOffsetX", 8.0).toFloat(),
                    shadowOffsetY = item.optDouble("shadowOffsetY", 10.0).toFloat(),
                    opacity = item.optInt("opacity", 255),
                    visible = item.optBoolean("visible", true),
                    locked = item.optBoolean("locked", false),
                    blendMode = item.optInt("blendMode", BLEND_NORMAL),
                ),
            )
        }
        val strokesJson = root.optJSONArray("brushStrokes") ?: JSONArray()
        val brushStrokes = ArrayList<PhotoBrushStroke>(strokesJson.length())
        for (index in 0 until strokesJson.length()) {
            val item = strokesJson.optJSONObject(index) ?: continue
            val pointsJson = item.optJSONArray("points") ?: JSONArray()
            val points = ArrayList<PhotoPoint>(pointsJson.length())
            for (pointIndex in 0 until pointsJson.length()) {
                val point = pointsJson.optJSONObject(pointIndex) ?: continue
                points.add(
                    PhotoPoint(
                        x = point.optDouble("x", 0.0).toFloat(),
                        y = point.optDouble("y", 0.0).toFloat(),
                    ),
                )
            }
            brushStrokes.add(
                PhotoBrushStroke(
                    id = item.optInt("id", index + 2001),
                    brushType = item.optInt("brushType", BRUSH_PEN),
                    color = item.optInt("color", Color.WHITE),
                    size = item.optDouble("size", 18.0).toFloat(),
                    opacity = item.optInt("opacity", 255),
                    eraser = item.optBoolean("eraser", false),
                    points = points.toMutableList(),
                    visible = item.optBoolean("visible", true),
                ),
            )
        }
        return PhotoProjectState(
            imageUri = root.optString("imageUri").takeIf { it.isNotBlank() && it != "null" },
            canvasBackgroundColor = root.optInt("canvasBackgroundColor", Color.rgb(248, 252, 255)),
            backgroundRotationDeg = root.optDouble("backgroundRotationDeg", 0.0).toFloat(),
            canvasOutputWidth = root.optInt("canvasOutputWidth", PhotoCanvasView.DEFAULT_CANVAS_WIDTH),
            canvasOutputHeight = root.optInt("canvasOutputHeight", PhotoCanvasView.DEFAULT_CANVAS_HEIGHT),
            canvasSizeLabel = root.optString("canvasSizeLabel", "Square").ifBlank { "Square" },
            imageBrightness = root.optDouble("imageBrightness", 0.0).toFloat(),
            imageContrast = root.optDouble("imageContrast", 1.0).toFloat(),
            imageSaturation = root.optDouble("imageSaturation", 1.0).toFloat(),
            imageTemperature = root.optDouble("imageTemperature", 0.0).toFloat(),
            imageBlur = root.optDouble("imageBlur", 0.0).toFloat(),
            imageVignette = root.optDouble("imageVignette", 0.0).toFloat(),
            imageFilterIntensity = root.optDouble("imageFilterIntensity", 1.0).toFloat(),
            imageTintColor = root.optInt("imageTintColor", Color.TRANSPARENT),
            imageTintStrength = root.optDouble("imageTintStrength", 0.0).toFloat(),
            imageMaskMode = root.optInt("imageMaskMode", PhotoCanvasView.IMAGE_MASK_NONE),
            imageCutoutMode = root.optInt("imageCutoutMode", PhotoCanvasView.IMAGE_CUTOUT_OFF),
            imageCutoutThreshold = root.optDouble("imageCutoutThreshold", 42.0).toFloat(),
            imageCutoutFeather = root.optDouble("imageCutoutFeather", 24.0).toFloat(),
            imageCurveShadows = root.optDouble("imageCurveShadows", 0.0).toFloat(),
            imageCurveMidtones = root.optDouble("imageCurveMidtones", 0.0).toFloat(),
            imageCurveHighlights = root.optDouble("imageCurveHighlights", 0.0).toFloat(),
            transparentBackground = root.optBoolean("transparentBackground", false),
            layers = layers,
            imageLayers = imageLayers,
            shapeLayers = shapeLayers,
            brushStrokes = brushStrokes,
        )
    }

    private fun resolveProjectFile(context: Context, requestedFile: File?): File {
        requestedFile?.let {
            setActiveProject(context, it)
            return it
        }
        activeProjectFile(context)?.let { return it }
        return newProjectFile(context)
    }

    private fun activeProjectFile(context: Context): File? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_ACTIVE_PROJECT_PATH, null)
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.isFile && it.length() > 0L }

    private fun newProjectFile(context: Context): File {
        val dir = projectsDir(context)
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return File(dir, "photo_$stamp.json")
    }

    private fun legacyAutosaveFile(context: Context): File =
        File(baseDir(context), "photo/$PROJECT_FILE")

    private fun projectsDir(context: Context): File =
        File(baseDir(context), "photo/projects")

    private fun baseDir(context: Context): File =
        context.getExternalFilesDir(null) ?: context.filesDir
}
