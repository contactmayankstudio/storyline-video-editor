package com.video.engine

// All content commented out to temporarily disable TimelineView
/*
import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Simple multi-layer timeline view. Host Activity should handle file picking and call
 * `addClipToLayer(layerIndex, path, type)` with a filesystem path.
 *
 * This component manages layers, displays clip blocks, selection and deletion,
 * and calls into `VideoPreviewView` to add/remove clips via JNI.
 */
class TimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val layersContainer: LinearLayout
    private val toolbar: LinearLayout

    // Simple model: list of layers, each is list of ClipInfo
    data class ClipInfo(var clipId: Long, val path: String, val view: View, val type: String)
    private val layers = mutableListOf<MutableList<ClipInfo>>()

    // Callback for when add buttons are pressed. Host should show file picker and then
    // call `addClipToLayer(layerIndex, path, type)` with the chosen path.
    var onRequestPickFile: ((layerIndex: Int, type: String) -> Unit)? = null

    // Link to preview view to call JNI methods
    var previewView: VideoPreviewView? = null

    init {
        orientation = VERTICAL

        // Toolbar with add-layer and add-clip buttons
        toolbar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnAddLayer = Button(context).apply { text = "+ Layer" }
        val btnAddVideo = Button(context).apply { text = "+ Video" }
        val btnAddPhoto = Button(context).apply { text = "+ Photo" }
        val btnAddAudio = Button(context).apply { text = "+ Audio" }
        val btnAddText = Button(context).apply { text = "+ Text" }

        toolbar.addView(btnAddLayer)
        toolbar.addView(btnAddVideo)
        toolbar.addView(btnAddPhoto)
        toolbar.addView(btnAddAudio)
        toolbar.addView(btnAddText)

        addView(toolbar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        layersContainer = LinearLayout(context).apply {
            orientation = VERTICAL
        }
        addView(ScrollView(context).apply { addView(layersContainer) }, LayoutParams(LayoutParams.MATCH_PARENT, 400))

        btnAddLayer.setOnClickListener { addLayer() }

        btnAddVideo.setOnClickListener {
            // default: pick file for top layer or create new layer
            val idx = if (layers.isEmpty()) { addLayer(); 0 } else 0
            onRequestPickFile?.invoke(idx, "video")
        }

        btnAddPhoto.setOnClickListener {
            val idx = if (layers.isEmpty()) { addLayer(); 0 } else 0
            onRequestPickFile?.invoke(idx, "photo")
        }

        btnAddAudio.setOnClickListener {
            val idx = if (layers.isEmpty()) { addLayer(); 0 } else 0
            onRequestPickFile?.invoke(idx, "audio")
        }

        btnAddText.setOnClickListener {
            val idx = if (layers.isEmpty()) { addLayer(); 0 } else 0
            // Launch text input UI via host Activity callback if provided
            // If not provided, show a simple dialog inline
            if (onRequestPickFile != null) {
                // Inform host to show a text editor - reuse file picker mechanism with type "text"
                onRequestPickFile?.invoke(idx, "text")
            } else {
                // Fallback: show basic text dialog
                if (context is android.app.Activity) {
                    val act = context as android.app.Activity
                            TextInputDialog(act) { text, fontSize, color ->
                                val pv = previewView ?: return@TextInputDialog
                                val overlayId = pv.addTextOverlay(-1, text, 0.5f, 0.5f, fontSize.toFloat(), 0.0f, color, 0, 10000)
                                addClipToLayer(idx, "[TEXT] $text", "text", overlayId.toLong())
                    }.show()
                }
            }
        }
    }

    /** Add an empty layer and return its index. */
    fun addLayer(): Int {
        val layerIndex = layers.size
        layers.add(mutableListOf())

        // Create UI row for layer
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(8, 8, 8, 8)
            gravity = Gravity.CENTER_VERTICAL
        }

        val label = TextView(context).apply { text = "Layer ${layerIndex + 1}" }
        val scroll = HorizontalScrollView(context)
        val clipsRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        scroll.addView(clipsRow)

        val btnAdd = ImageButton(context).apply { setImageResource(android.R.drawable.ic_input_add) }

        row.addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        row.addView(scroll, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(btnAdd, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        layersContainer.addView(row)

        btnAdd.setOnClickListener {
            onRequestPickFile?.invoke(layerIndex, "video")
        }

        return layerIndex
    }

    /**
     * Add a clip UI block to a layer. This calls nativeAddClip via `previewView.addClip`.
     * `type` is informational ("video" | "photo" | "audio").
     */
    fun addClipToLayer(layerIndex: Int, path: String, type: String = "video", providedClipId: Long? = null) {
        if (layerIndex < 0 || layerIndex >= layers.size) return
        val pv = previewView ?: return

        // Ask native to add clip if not a text block and no providedClipId
        val clipId: Long = when {
            type == "text" -> (providedClipId ?: -1L)
            providedClipId != null -> providedClipId
            else -> pv.addClip(path).toLong()
        }

        val clipType = type
        // Create a simple clip block view
        val clipView = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(12, 8, 12, 8)
            setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
            val tv = TextView(context).apply { text = "${type.uppercase()} (${formatPath(path)})" }
            addView(tv)
        }

        // Add delete on long click
        clipView.setOnLongClickListener {
            // Remove from native and UI
            if (clipType == "text") {
                pv.removeTextOverlay(clipId.toInt())
            } else {
                pv.removeClip(clipId.toInt())
            }
            removeClipView(layerIndex, clipId)
            true
        }

        // Append to layer's clip row
        val row = layersContainer.getChildAt(layerIndex) as LinearLayout
        val scroll = row.getChildAt(1) as HorizontalScrollView
        val clipsRow = scroll.getChildAt(0) as LinearLayout
        clipsRow.addView(clipView)

        layers[layerIndex].add(ClipInfo(clipId, path, clipView, type))
    }

    private fun removeClipView(layerIndex: Int, clipId: Long) {
        val list = layers.getOrNull(layerIndex) ?: return
        val it = list.find { it.clipId == clipId || (it.clipId == -1L && it.path.startsWith("[TEXT]")) } ?: return
        val row = layersContainer.getChildAt(layerIndex) as LinearLayout
        val scroll = row.getChildAt(1) as HorizontalScrollView
        val clipsRow = scroll.getChildAt(0) as LinearLayout
        clipsRow.removeView(it.view)
        list.remove(it)
    }

    private fun formatPath(path: String): String {
        val f = path.split('/').lastOrNull() ?: path
        return if (f.length > 12) f.take(9) + "…" else f
    }

    /**
     * Get all clips for display in LayersPanel.
     * Returns flattened list of LayerClipInfo with layer index.
     */
    fun getAllClips(): List<LayerClipInfo> {
        val result = mutableListOf<LayerClipInfo>()
        for ((layerIdx, clipsList) in layers.withIndex()) {
            for (clip in clipsList) {
                result.add(
                    LayerClipInfo(
                        clipId = clip.clipId,
                        type = clip.type,
                        layerIndex = layerIdx,
                        isVisible = true  // TODO: track visibility in ClipInfo
                    )
                )
            }
        }
        return result
    }

    /**
     * Remove a clip by its ID, used by LayersPanel delete.
     */
    fun removeClipById(clipId: Long) {
        for ((layerIdx, list) in layers.withIndex()) {
            val clip = list.find { it.clipId == clipId }
            if (clip != null) {
                removeClipView(layerIdx, clipId)
                return
            }
        }
    }
}

/**
 * Data class for passing clip info from TimelineView to LayersPanel.
 */
data class LayerClipInfo(
    val clipId: Long,
    val type: String,
    val layerIndex: Int,
    var isVisible: Boolean
)
*/