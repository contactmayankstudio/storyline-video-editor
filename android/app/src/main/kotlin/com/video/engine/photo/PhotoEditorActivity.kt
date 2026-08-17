package com.video.engine.photo

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.video.engine.AdsController
import com.video.engine.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class PhotoEditorActivity : Activity() {
    private enum class PhotoStudioTab { CANVAS, PRESETS, EDIT, MAGIC, TEXT, ELEMENTS, LAYERS }

    companion object {
        const val EXTRA_RESUME_PHOTO = "com.video.engine.photo.RESUME_PHOTO"
        const val EXTRA_PHOTO_PROJECT_PATH = "com.video.engine.photo.PROJECT_PATH"
        private const val PICK_IMAGE_REQUEST = 6101
        private const val PICK_BACKGROUND_IMAGE_REQUEST = 6102
        private const val MAX_IMPORT_SIDE = 1600
        private const val AUTOSAVE_DELAY_MS = 350L
        private const val PROJECT_NAME_PREF = "photo_project_name"
        private const val PENDING_PHOTO_PICK_PREF = "pending_photo_pick"
        private const val TOOL_PANEL_COMPACT_HEIGHT_DP = 92
        private const val TOOL_PANEL_CONTENT_HEIGHT_DP = 178
        private const val TOOL_PANEL_TALL_HEIGHT_DP = 222
        private const val MIN_CUSTOM_CANVAS_SIDE = 256
        private const val MAX_CUSTOM_CANVAS_SIDE = 4096
    }

    private lateinit var canvasView: PhotoCanvasView
    private lateinit var statusText: TextView
    private lateinit var activeToolPanel: LinearLayout
    private lateinit var activeToolScroll: ScrollView
    private lateinit var studioToolPanel: LinearLayout
    private lateinit var studioTabRow: LinearLayout
    private lateinit var floatingQuickBar: LinearLayout
    private lateinit var floatingQuickBarScroll: HorizontalScrollView
    private lateinit var magicWipeLineHandle: FrameLayout
    private lateinit var projectNameEdit: EditText
    private val autosaveHandler = Handler(Looper.getMainLooper())
    private val autosaveRunnable = Runnable { savePhotoProjectIfDirty() }
    private val adsHandler = Handler(Looper.getMainLooper())
    private val photoBannerRunnable = Runnable { attachPhotoTopBanner() }
    private var photoTopBannerContainer: FrameLayout? = null
    private var adsController: AdsController? = null
    private var sourceImageUri: String? = null
    private var dirtySinceOpen = false
    private var activeStudioTab: PhotoStudioTab = PhotoStudioTab.EDIT
    private var magicWipeDragOffsetX = 0f

    private val colorOptions = listOf(
        "White" to Color.WHITE,
        "Black" to Color.BLACK,
        "Yellow" to Color.rgb(255, 214, 64),
        "Orange" to Color.rgb(255, 138, 60),
        "Red" to Color.rgb(239, 83, 80),
        "Pink" to Color.rgb(236, 64, 122),
        "Blue" to Color.rgb(66, 165, 245),
        "Cyan" to Color.rgb(38, 198, 218),
        "Green" to Color.rgb(102, 187, 106),
        "Purple" to Color.rgb(171, 71, 188),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        adsController = AdsController(this)
        projectNameEdit.setText(getPreferences(MODE_PRIVATE).getString(PROJECT_NAME_PREF, "Untitled Photo"))
        val requestedProjectFile = intent.getStringExtra(EXTRA_PHOTO_PROJECT_PATH)
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
        val resumeRequested = intent.getBooleanExtra(EXTRA_RESUME_PHOTO, false) || requestedProjectFile != null
        val pickerRestoreRequested = getPreferences(MODE_PRIVATE).getBoolean(PENDING_PHOTO_PICK_PREF, false)
        if (requestedProjectFile != null) {
            PhotoProjectStore.setActiveProject(this, requestedProjectFile)
        } else if (!resumeRequested && !pickerRestoreRequested) {
            PhotoProjectStore.startNewProject(this)
        }
        val restored = (resumeRequested || pickerRestoreRequested) &&
            restoreRecentPhoto(showToast = resumeRequested, projectFile = requestedProjectFile)
        canvasView.onDesignChanged = {
            dirtySinceOpen = true
            schedulePhotoAutosave()
        }
        collapseToolPanel()
        switchStudioTab(if (restored) PhotoStudioTab.EDIT else PhotoStudioTab.PRESETS)
        showImageFilterPanel()
        refreshStatus()
        schedulePhotoTopBanner(delayMs = 1_800L)
    }

    override fun onResume() {
        super.onResume()
        adsController?.onResume()
        schedulePhotoTopBanner(delayMs = 1_200L)
    }

    override fun onPause() {
        adsHandler.removeCallbacks(photoBannerRunnable)
        adsController?.onPause()
        if (::canvasView.isInitialized) canvasView.commitMagicSweep()
        saveProjectName()
        savePhotoProjectIfDirty()
        super.onPause()
    }

    override fun onDestroy() {
        autosaveHandler.removeCallbacks(autosaveRunnable)
        adsHandler.removeCallbacks(photoBannerRunnable)
        adsController?.onDestroy()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val photoPickerRequest = requestCode == PICK_IMAGE_REQUEST || requestCode == PICK_BACKGROUND_IMAGE_REQUEST
        if (photoPickerRequest) setPendingPhotoPicker(false)
        if (!photoPickerRequest || resultCode != RESULT_OK) return
        val resultData = data ?: return
        val uri = resultData.data ?: return
        contentResolver.takePersistableUriPermissionSafe(resultData, uri)
        val bitmap = decodeBitmap(uri)
        if (bitmap == null) {
            Toast.makeText(this, "Image import failed", Toast.LENGTH_SHORT).show()
            return
        }
        val imageUri = uri.toString()
        if (requestCode == PICK_BACKGROUND_IMAGE_REQUEST) {
            setImportedBackgroundImage(imageUri, bitmap)
        } else {
            showImportPlacementDialog(imageUri, bitmap)
        }
    }

    private fun showImportPlacementDialog(uri: String, bitmap: Bitmap) {
        AlertDialog.Builder(this)
            .setTitle("Import image as")
            .setMessage("Image Layer can move, resize and rotate. Background stays fixed behind the canvas.")
            .setPositiveButton("Image Layer") { _, _ -> addImportedImageLayer(uri, bitmap) }
            .setNegativeButton("Background") { _, _ -> setImportedBackgroundImage(uri, bitmap) }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun addImportedImageLayer(uri: String?, bitmap: Bitmap) {
        canvasView.addImageLayer(uri, bitmap)
        showImagePanel()
        refreshStatus()
        Toast.makeText(this, "Image layer added", Toast.LENGTH_SHORT).show()
    }

    private fun setImportedBackgroundImage(uri: String?, bitmap: Bitmap) {
        sourceImageUri = uri
        canvasView.setBaseImage(bitmap)
        showBackgroundPanel()
        refreshStatus()
        Toast.makeText(this, "Background image set", Toast.LENGTH_SHORT).show()
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(2, 6, 10))
        }
        root.addView(buildTopBar())
        root.addView(buildPhotoTopBannerSlot())
        root.addView(
            FrameLayout(this).apply {
                background = editorWorkspaceBackground()
                setPadding(dp(0), dp(0), dp(0), dp(0))
                canvasView = PhotoCanvasView(this@PhotoEditorActivity).apply {
                    onSelectionChanged = { refreshStatus() }
                    onSelectionPicked = {
                        refreshStatus()
                        switchStudioTabForSelection(selectedLayerItem())
                    }
                }
                addView(
                    canvasView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER,
                    ),
                )
                magicWipeLineHandle = buildMagicWipeLineHandle()
                addView(
                    magicWipeLineHandle,
                    FrameLayout.LayoutParams(
                        dp(28),
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.START or Gravity.TOP,
                    ),
                )
                val syncMagicLineAfterLayout =
                    View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                        val sizeChanged = right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop
                        val positionChanged = left != oldLeft || top != oldTop
                        if (sizeChanged || positionChanged) {
                            magicWipeLineHandle.post { updateMagicWipeLinePosition(canvasView.magicSweepRevealFraction()) }
                        }
                    }
                canvasView.addOnLayoutChangeListener(syncMagicLineAfterLayout)
                addOnLayoutChangeListener(syncMagicLineAfterLayout)
                post { updateMagicWipeLinePosition(1f) }
                post { syncMagicWipeLineVisibility() }
                floatingQuickBar = LinearLayout(this@PhotoEditorActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), 0, dp(10), 0)
                }
                floatingQuickBarScroll = HorizontalScrollView(this@PhotoEditorActivity).apply {
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                    visibility = View.GONE
                    background = floatingBarBackground()
                    elevation = dp(4).toFloat()
                    addView(
                        floatingQuickBar,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        ),
                    )
                }
                addView(
                    floatingQuickBarScroll,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        dp(50),
                        Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                    ).apply {
                        marginStart = dp(22)
                        marginEnd = dp(22)
                        bottomMargin = dp(12)
                    },
                )
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        statusText = TextView(this).apply {
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setTextColor(Color.rgb(148, 163, 184))
            textSize = 11.5f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(7, 10, 15))
        }
        root.addView(statusText)
        root.addView(buildToolStrip())
        return root
    }

    private fun buildMagicWipeLineHandle(): FrameLayout =
        FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            alpha = 0.78f
            isClickable = true
            isFocusable = false
            addView(
                View(this@PhotoEditorActivity).apply {
                    background = magicWipeLineBackground()
                },
                FrameLayout.LayoutParams(
                    dp(3).coerceAtLeast(2),
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER,
                ),
            )
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        magicWipeDragOffsetX = event.x
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        canvasView.beginMagicSweep()
                        handleMagicWipeLineDrag(view, event.x)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        handleMagicWipeLineDrag(view, event.x)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        handleMagicWipeLineDrag(view, event.x)
                        canvasView.commitMagicSweep()
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                        true
                    }
                    else -> true
                }
            }
        }

    private fun handleMagicWipeLineDrag(handle: View, localX: Float) {
        val bounds = canvasView.magicSweepViewportBounds()
        if (!updateMagicWipeLineBounds(bounds)) return
        val lineInset = dp(6).toFloat()
        val minX = bounds.left + lineInset - handle.width / 2f
        val maxX = bounds.right - lineInset - handle.width / 2f
        if (maxX < minX) return
        val nextX = (handle.x + localX - magicWipeDragOffsetX).coerceIn(minX, maxX)
        handle.x = nextX
        val revealFraction = canvasView.magicSweepFractionForViewX(nextX + handle.width / 2f)
        canvasView.previewMagicSweep(revealFraction)
    }

    private fun updateMagicWipeLinePosition(fraction: Float) {
        if (!::magicWipeLineHandle.isInitialized) return
        val bounds = canvasView.magicSweepViewportBounds()
        if (!updateMagicWipeLineBounds(bounds) || magicWipeLineHandle.width <= 0) {
            magicWipeLineHandle.post { updateMagicWipeLinePosition(fraction) }
            return
        }
        val lineInset = dp(6).toFloat()
        val minX = bounds.left + lineInset - magicWipeLineHandle.width / 2f
        val maxX = bounds.right - lineInset - magicWipeLineHandle.width / 2f
        if (maxX < minX) return
        magicWipeLineHandle.x =
            (canvasView.magicSweepViewXForFraction(fraction) - magicWipeLineHandle.width / 2f).coerceIn(minX, maxX)
    }

    private fun updateMagicWipeLineBounds(bounds: RectF): Boolean {
        if (bounds.width() <= 1f || bounds.height() <= 1f) return false
        magicWipeLineHandle.y = bounds.top
        val nextHeight = bounds.height().toInt().coerceAtLeast(dp(1))
        val params = magicWipeLineHandle.layoutParams as? FrameLayout.LayoutParams ?: return true
        if (params.height != nextHeight) {
            params.height = nextHeight
            magicWipeLineHandle.layoutParams = params
        }
        return true
    }

    private fun buildPhotoTopBannerSlot(): View {
        return FrameLayout(this).apply {
            id = R.id.photoTopBannerContainer
            photoTopBannerContainer = this
            contentDescription = getString(R.string.photo_top_ad_desc)
            setBackgroundColor(Color.rgb(18, 18, 18))
            visibility = View.GONE
        }.also { slot ->
            slot.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(56),
            )
        }
    }

    private fun schedulePhotoTopBanner(delayMs: Long) {
        if (!resources.getBoolean(R.bool.storyline_runtime_startup_ads_enabled)) {
            adsController?.releaseBanner(photoTopBannerContainer)
            return
        }
        adsHandler.removeCallbacks(photoBannerRunnable)
        adsHandler.postDelayed(photoBannerRunnable, delayMs.coerceAtLeast(600L))
    }

    private fun attachPhotoTopBanner() {
        if (isFinishing || isDestroyed) return
        if (!resources.getBoolean(R.bool.storyline_runtime_startup_ads_enabled)) return
        adsController?.attachTopBanner(photoTopBannerContainer)
    }

    private fun buildTopBar(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(Color.rgb(5, 8, 13))
            projectNameEdit = EditText(this@PhotoEditorActivity).apply {
                visibility = View.GONE
                setText("Untitled Photo")
                setSingleLine(true)
            }
            addView(projectNameEdit, LinearLayout.LayoutParams(0, 0))

            val actionRow = LinearLayout(this@PhotoEditorActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(topCircleButton("Back") { finish() })
                addView(
                    TextView(this@PhotoEditorActivity).apply {
                        text = "Photo Edit"
                        setTextColor(Color.WHITE)
                        textSize = 18f
                        gravity = Gravity.CENTER_VERTICAL
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setSingleLine(true)
                        includeFontPadding = false
                    },
                    LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                        marginStart = dp(8)
                        marginEnd = dp(4)
                    },
                )
                addView(topCircleButton("Undo") { handleUndo() })
                addView(topCircleButton("Redo") { handleRedo() })
                addView(topCircleButton("Draft") { forceSavePhotoProject() })
                addView(topSaveButton("Save") { showExportPanel() })
            }
            addView(
                actionRow,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)),
            )
        }
    }

    private fun buildToolStrip(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bottomStudioBackground()
            elevation = dp(12).toFloat()
        }

        activeToolPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        activeToolScroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            setBackgroundColor(Color.TRANSPARENT)
            addView(
                activeToolPanel,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        root.addView(
            activeToolScroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0),
        )
        activeToolScroll.visibility = View.GONE

        studioToolPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(4))
        }
        root.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(studioToolPanel)
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )

        studioTabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(1), dp(8), dp(6))
        }
        PhotoStudioTab.values().forEach { tab ->
            studioTabRow.addView(studioTabButton(tab))
        }
        root.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(studioTabRow)
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )

        return root
    }

    private fun switchStudioTab(tab: PhotoStudioTab, collapsePanel: Boolean = true) {
        activeStudioTab = tab
        if (collapsePanel && ::activeToolScroll.isInitialized) collapseToolPanel()
        if (!::studioToolPanel.isInitialized) return
        studioToolPanel.removeAllViews()
        when (tab) {
            PhotoStudioTab.CANVAS -> {
                addStudioTool("Image", "IMG") { pickImage() }
                addStudioTool("Import BG", "BG+") { pickBackgroundImage() }
                addStudioTool("Rotate BG", "ROT") { canvasView.rotateBackgroundBy(90f) }
                addStudioTool("Size", "1:1") { showSizePanel() }
                addStudioTool("BG Color", "BG") { showCanvasBackgroundMixer() }
                addStudioTool("Clear", "CLR") { canvasView.transparentBackground = true }
                addStudioTool("Grid", "GRID") { canvasView.gridVisible = !canvasView.gridVisible }
            }
            PhotoStudioTab.PRESETS -> {
                addStudioTool("Image", "IMG") { pickImage() }
                addStudioTool("Poster", "POST") { applySmartPosterStyle() }
                addStudioTool("Clean", "CLN") { applyCleanTitleStyle() }
                addStudioTool("Thumb", "POP") { applyThumbnailPopStyle() }
                addStudioTool("Birthday", "BDAY") { applyBirthdayStoryStyle() }
                addStudioTool("Story", "9:16") { applyStoryTemplate() }
            }
            PhotoStudioTab.EDIT -> {
                addStudioTool("Delete", "DEL", danger = true) { deleteSelectedOrTopLayer() }
                addStudioTool("Transform", "MOVE") { showLayerTransformPanel() }
                addStudioTool("Blend", "BLND") { showBlendModePanel() }
                addStudioTool("Adjust", "ADJ") { showImageAdjustForSelection() }
                addStudioTool("Effects", "FX") { showProEffectsPanel() }
                addStudioTool("Curves", "CRV") { showImageCurvePanel() }
                addStudioTool("Tint", "TINT") { showImageTintMixer() }
                addStudioTool("Filters", "FILM") { showImageFilterPanel() }
            }
            PhotoStudioTab.MAGIC -> {
                addStudioTool("BG Remove", "CUT", isMagic = true) { applyAiBackgroundRemove() }
                addStudioTool("Green Cut", "KEY", isMagic = true) { applyGreenScreenCutout() }
                addStudioTool("White Cut", "WHT", isMagic = true) { applyLightCutout() }
                addStudioTool("Enhance", "AI", isMagic = true) { applyImageLook(8f, 1.08f, 1.08f, Color.TRANSPARENT, 0f) }
                addStudioTool("AI Poster", "POST", isMagic = true) { applySmartPosterStyle() }
            }
            PhotoStudioTab.TEXT -> {
                addStudioTool("Delete", "DEL", danger = true) { deleteSelectedOrTopLayer() }
                addStudioTool("Add", "Aa+") { addText() }
                addStudioTool("Transform", "MOVE") { showLayerTransformPanel() }
                addStudioTool("Blend", "BLND") { showBlendModePanel() }
                addStudioTool("Font", "FONT") { showTextFontPanel() }
                addStudioTool("Style", "B/I") { showTextStylePanel() }
                addStudioTool("Color", "COL") { showTextColorMixer() }
                addStudioTool("Shadow", "SHD") { showTextShadowPanel() }
                addStudioTool("3D", "3D") { showTextCurvePanel() }
            }
            PhotoStudioTab.ELEMENTS -> {
                addStudioTool("Delete", "DEL", danger = true) { deleteSelectedOrTopLayer() }
                addStudioTool("Image", "IMG") { pickImage() }
                addStudioTool("Transform", "MOVE") { showLayerTransformPanel() }
                addStudioTool("Blend", "BLND") { showBlendModePanel() }
                addStudioTool("Shapes", "SHP") { showShapesPanel() }
                addStudioTool("Stickers", "STK") { showStickersPanel() }
                addStudioTool("Draw", "PEN") { showDrawPanel() }
            }
            PhotoStudioTab.LAYERS -> {
                addStudioTool("Delete", "DEL", danger = true) { deleteSelectedOrTopLayer() }
                addStudioTool("Layers", "LIST") { showLayerPanel() }
                addStudioTool("Hide", "EYE") { canvasView.toggleSelectedVisibility(); refreshStatus() }
                addStudioTool("Lock", "LOCK") { canvasView.toggleSelectedLock(); refreshStatus() }
                addStudioTool("Copy", "COPY") { canvasView.duplicateSelectedLayer(); refreshStatus() }
                addStudioTool("Export", "OUT") { showExportPanel() }
            }
        }
        refreshStudioTabs()
        syncMagicWipeLineVisibility()
    }

    private fun syncMagicWipeLineVisibility() {
        if (!::magicWipeLineHandle.isInitialized) return
        magicWipeLineHandle.visibility = View.VISIBLE
        magicWipeLineHandle.alpha = if (activeStudioTab == PhotoStudioTab.MAGIC) 1f else 0.68f
        if (::canvasView.isInitialized) {
            magicWipeLineHandle.post {
                updateMagicWipeLinePosition(canvasView.magicSweepRevealFraction())
            }
        }
    }

    private fun switchStudioTabForSelection(item: PhotoLayerItem?) {
        val nextTab = when (item?.type) {
            PHOTO_LAYER_IMAGE -> PhotoStudioTab.EDIT
            PHOTO_LAYER_BACKGROUND -> PhotoStudioTab.CANVAS
            PHOTO_LAYER_TEXT -> PhotoStudioTab.TEXT
            PHOTO_LAYER_SHAPE, PHOTO_LAYER_BRUSH -> PhotoStudioTab.ELEMENTS
            else -> activeStudioTab
        }
        if (nextTab != activeStudioTab) {
            switchStudioTab(nextTab)
        }
    }

    private fun addStudioTool(
        label: String,
        icon: String,
        isMagic: Boolean = false,
        danger: Boolean = false,
        onClick: () -> Unit,
    ) {
        if (!::studioToolPanel.isInitialized) return
        val accent = when {
            danger -> Color.rgb(255, 69, 58)
            isMagic -> Color.rgb(142, 90, 255)
            else -> accentForLabel(label)
        }
        val toolView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(5), 0, dp(5), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                canvasView.drawModeEnabled = false
                onClick()
            }
        }
        toolView.addView(
            FrameLayout(this).apply {
                addView(
                    TextView(this@PhotoEditorActivity).apply {
                        text = icon
                        setTextColor(if (isMagic) Color.rgb(218, 205, 255) else Color.WHITE)
                        textSize = if (icon.length <= 3) 14f else 9f
                        gravity = Gravity.CENTER
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        background = studioIconBackground(accent, isMagic, danger)
                    },
                    FrameLayout.LayoutParams(dp(48), dp(42), Gravity.CENTER),
                )
                if (isMagic) {
                    addView(
                        TextView(this@PhotoEditorActivity).apply {
                            text = "AI"
                            setTextColor(Color.BLACK)
                            textSize = 7f
                            gravity = Gravity.CENTER
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            background = roundSolid(Color.rgb(255, 214, 64), dp(4).toFloat())
                            setPadding(dp(4), dp(1), dp(4), dp(1))
                        },
                        FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                            topMargin = dp(3)
                            marginEnd = dp(2)
                        },
                    )
                }
            },
            LinearLayout.LayoutParams(dp(56), dp(44)),
        )
        toolView.addView(
            TextView(this).apply {
                text = label
                setTextColor(if (isMagic) Color.rgb(181, 152, 255) else Color.rgb(174, 174, 178))
                textSize = 8.5f
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setSingleLine(true)
            },
            LinearLayout.LayoutParams(dp(62), dp(18)),
        )
        studioToolPanel.addView(toolView)
    }

    private fun studioTabButton(tab: PhotoStudioTab): TextView {
        val label = when (tab) {
            PhotoStudioTab.CANVAS -> "Crop"
            PhotoStudioTab.PRESETS -> "Filters"
            PhotoStudioTab.EDIT -> "Adjust"
            PhotoStudioTab.MAGIC -> "Effects"
            PhotoStudioTab.TEXT -> "Text"
            PhotoStudioTab.ELEMENTS -> "Sticker"
            PhotoStudioTab.LAYERS -> "Layers"
        }
        return TextView(this).apply {
            text = label
            tag = tab
            textSize = 11f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(9), dp(14), dp(9))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                switchStudioTab(tab)
            }
        }
    }

    private fun refreshStudioTabs() {
        if (!::studioTabRow.isInitialized) return
        for (index in 0 until studioTabRow.childCount) {
            val tabView = studioTabRow.getChildAt(index) as? TextView ?: continue
            val active = tabView.tag == activeStudioTab
            tabView.setTextColor(if (active) Color.rgb(10, 132, 255) else Color.WHITE)
            tabView.background =
                if (active) {
                    tabActiveBackground()
                } else {
                    roundSolid(Color.TRANSPARENT, dp(16).toFloat())
                }
        }
    }

    private fun topCircleButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.rgb(232, 238, 247))
            textSize = 8f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setSingleLine(true)
            includeFontPadding = false
            background = topIconBackground()
            isClickable = true
            isFocusable = true
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onClick()
            }
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(36)).apply {
                marginEnd = dp(4)
            }
        }
    }

    private fun topSaveButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setSingleLine(true)
            includeFontPadding = false
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(255, 112, 20), Color.rgb(255, 127, 35)),
            ).apply {
                cornerRadius = dp(12).toFloat()
            }
            elevation = dp(3).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onClick()
            }
            layoutParams = LinearLayout.LayoutParams(dp(78), dp(36)).apply {
                marginStart = dp(4)
            }
        }
    }

    private fun toolButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setSingleLine(true)
            setPadding(dp(12), 0, dp(12), 0)
            background = roundSolid(Color.rgb(8, 90, 178), dp(15).toFloat(), Color.rgb(55, 165, 255))
            elevation = dp(2).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onClick()
            }
            layoutParams = LinearLayout.LayoutParams(dp(82), dp(36)).apply {
                marginEnd = dp(8)
            }
        }
    }

    private fun mainToolButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.rgb(20, 54, 84))
            textSize = 12f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            val accent = accentForLabel(label)
            setTextColor(accent)
            background = chipBackground(accent)
            elevation = dp(1).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                canvasView.drawModeEnabled = false
                onClick()
            }
            layoutParams = LinearLayout.LayoutParams(dp(94), dp(58)).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
        }
    }

    private fun panelButton(
        label: String,
        danger: Boolean = false,
        onClick: () -> Unit,
    ): TextView {
        return TextView(this).apply {
            text = label
            val accent = accentForLabel(label, danger)
            setTextColor(accent)
            textSize = 11.5f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setSingleLine(true)
            setPadding(dp(8), 0, dp(8), 0)
            background = chipBackground(accent, danger)
            elevation = dp(1).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onClick()
            }
        }
    }

    private fun accentForLabel(label: String, danger: Boolean = false): Int {
        if (danger) return Color.rgb(204, 55, 66)
        val key = label.lowercase(Locale.US)
        return when {
            "delete" in key || "reset" in key || "clear" in key -> Color.rgb(215, 73, 79)
            "text" in key || "font" in key || "bold" in key || "italic" in key || "under" in key -> Color.rgb(82, 92, 196)
            "image" in key || "photo" in key || "import" in key || "gallery" in key -> Color.rgb(25, 137, 188)
            "adjust" in key || "effect" in key || "filter" in key || "bright" in key || "contrast" in key -> Color.rgb(31, 132, 215)
            "color" in key || "tint" in key || "gradient" in key || "bg" in key -> Color.rgb(207, 82, 143)
            "size" in key || "crop" in key || "mask" in key || "cutout" in key || "fit" in key || "fill" in key -> Color.rgb(229, 139, 34)
            "shape" in key || "rect" in key || "circle" in key || "triangle" in key || "star" in key -> Color.rgb(111, 105, 202)
            "draw" in key || "brush" in key || "pen" in key || "marker" in key || "neon" in key -> Color.rgb(36, 158, 121)
            "layer" in key || "front" in key || "lock" in key || "copy" in key -> Color.rgb(96, 120, 150)
            "export" in key || "save" in key || "png" in key || "jpg" in key -> Color.rgb(24, 154, 98)
            "ai" in key || "template" in key || "poster" in key || "clean" in key -> Color.rgb(126, 85, 205)
            "hide" in key || "back" in key || "undo" in key || "redo" in key || "settings" in key -> Color.rgb(35, 86, 128)
            else -> Color.rgb(22, 113, 172)
        }
    }

    private fun chipBackground(accent: Int, danger: Boolean = false): GradientDrawable {
        val start =
            if (danger) {
                Color.rgb(47, 22, 25)
            } else {
                blendWithBlack(accent, 0.84f)
            }
        val end =
            if (danger) {
                Color.rgb(30, 18, 20)
            } else {
                Color.rgb(28, 28, 30)
            }
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(start, end)).apply {
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1).coerceAtLeast(1), if (danger) Color.rgb(255, 69, 58) else blendWithWhite(accent, 0.35f))
        }
    }

    private fun editorWorkspaceBackground(): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.rgb(4, 8, 13), Color.rgb(2, 6, 10)),
        )

    private fun bottomStudioBackground(): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.rgb(11, 15, 22), Color.rgb(4, 7, 12)),
        ).apply {
            setStroke(dp(1).coerceAtLeast(1), Color.rgb(25, 31, 40))
        }

    private fun magicWipeLineBackground(): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.argb(245, 255, 255, 255),
                Color.rgb(255, 48, 64),
                Color.argb(245, 255, 255, 255),
            ),
        ).apply {
            cornerRadius = dp(2).toFloat()
        }

    private fun topIconBackground(): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.rgb(11, 16, 24))
            cornerRadius = dp(13).toFloat()
            setStroke(dp(1).coerceAtLeast(1), Color.rgb(38, 47, 60))
        }

    private fun tabActiveBackground(): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.rgb(8, 20, 35))
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1).coerceAtLeast(1), Color.rgb(10, 132, 255))
        }

    private fun filterCardBackground(startColor: Int, endColor: Int, active: Boolean): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(startColor, endColor),
        ).apply {
            cornerRadius = dp(10).toFloat()
            setStroke(
                dp(if (active) 2 else 1).coerceAtLeast(1),
                if (active) Color.rgb(10, 132, 255) else Color.rgb(38, 45, 56),
            )
        }

    private fun studioIconBackground(accent: Int, isMagic: Boolean, danger: Boolean): GradientDrawable {
        val fill =
            when {
                danger -> Color.rgb(49, 20, 24)
                isMagic -> Color.rgb(26, 16, 51)
                else -> Color.rgb(28, 28, 30)
            }
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(16).toFloat()
            setStroke(dp(if (isMagic || danger) 2 else 1).coerceAtLeast(1), accent)
        }
    }

    private fun roundSolid(color: Int, radius: Float, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (strokeColor != null) setStroke(dp(1).coerceAtLeast(1), strokeColor)
        }

    private fun blendWithWhite(color: Int, whiteAmount: Float): Int {
        val keep = (1f - whiteAmount).coerceIn(0f, 1f)
        val white = whiteAmount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(color) * keep + 255f * white).toInt().coerceIn(0, 255),
            (Color.green(color) * keep + 255f * white).toInt().coerceIn(0, 255),
            (Color.blue(color) * keep + 255f * white).toInt().coerceIn(0, 255),
        )
    }

    private fun blendWithBlack(color: Int, blackAmount: Float): Int {
        val keep = (1f - blackAmount).coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(color) * keep).toInt().coerceIn(0, 255),
            (Color.green(color) * keep).toInt().coerceIn(0, 255),
            (Color.blue(color) * keep).toInt().coerceIn(0, 255),
        )
    }

    private fun floatingBarBackground(): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.WHITE, Color.rgb(246, 251, 255)),
        ).apply {
            cornerRadius = dp(24).toFloat()
            setStroke(dp(1).coerceAtLeast(1), Color.rgb(216, 229, 241))
        }

    private fun refreshFloatingQuickBar() {
        if (!::floatingQuickBar.isInitialized || !::floatingQuickBarScroll.isInitialized) return
        floatingQuickBar.removeAllViews()
        floatingQuickBarScroll.visibility = View.GONE
    }

    private fun quickBarButton(action: PhotoToolAction, prominent: Boolean): TextView {
        val accent = accentForLabel(action.label, action.danger)
        return TextView(this).apply {
            text = action.label
            setTextColor(accent)
            textSize = if (prominent) 11f else 10.5f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setSingleLine(true)
            setPadding(dp(6), 0, dp(6), 0)
            background = if (prominent || action.danger || action.label.equals("Filters", true)) {
                chipBackground(accent, action.danger)
            } else {
                GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = dp(14).toFloat()
                }
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                action.onClick()
                refreshStatus()
            }
        }
    }

    private fun quickActionsForSelection(): List<PhotoToolAction> {
        return when (canvasView.selectedLayerItem()?.type) {
            PHOTO_LAYER_IMAGE -> listOf(
                PhotoToolAction("Delete", danger = true) { deleteSelectedOrTopLayer() },
                PhotoToolAction("Adjust") { showImageLayerPanel() },
                PhotoToolAction("Transform") { showLayerTransformPanel() },
                PhotoToolAction("Blend") { showBlendModePanel() },
                PhotoToolAction("Top") { moveSelectedToFront() },
                PhotoToolAction("Copy") { canvasView.duplicateSelectedLayer() },
                PhotoToolAction("Bigger") { scaleSelected(1.12f) },
                PhotoToolAction("Smaller") { scaleSelected(0.90f) },
            )
            PHOTO_LAYER_BACKGROUND -> listOf(
                PhotoToolAction("Delete", danger = true) { deleteSelectedOrTopLayer() },
                PhotoToolAction("BG Image") { pickBackgroundImage() },
                PhotoToolAction("BG Color") { showCanvasBackgroundMixer() },
                PhotoToolAction("Filters") { showImageFilterPanel() },
                PhotoToolAction("Curves") { showImageCurvePanel() },
            )
            PHOTO_LAYER_TEXT -> listOf(
                PhotoToolAction("Delete", danger = true) { deleteSelectedOrTopLayer() },
                PhotoToolAction("Text FX") { showTextGradientPanel() },
                PhotoToolAction("Transform") { showLayerTransformPanel() },
                PhotoToolAction("Blend") { showBlendModePanel() },
                PhotoToolAction("Style") { showTextStylePanel() },
                PhotoToolAction("Color") { showTextColorMixer() },
                PhotoToolAction("Shadow") { showTextShadowPanel() },
                PhotoToolAction("Copy") { canvasView.duplicateSelectedLayer() },
                PhotoToolAction("Top") { moveSelectedToFront() },
            )
            PHOTO_LAYER_SHAPE -> listOf(
                PhotoToolAction("Delete", danger = true) { deleteSelectedOrTopLayer() },
                PhotoToolAction("Shape FX") { showShapeAdjustPanel() },
                PhotoToolAction("Transform") { showLayerTransformPanel() },
                PhotoToolAction("Blend") { showBlendModePanel() },
                PhotoToolAction("Fill") { showShapeFillMixer() },
                PhotoToolAction("Stroke") { showShapeStrokePanel() },
                PhotoToolAction("Shadow") {
                    canvasView.updateSelectedShapeLayer { it.shadowEnabled = !it.shadowEnabled }
                },
                PhotoToolAction("Copy") { canvasView.duplicateSelectedLayer() },
            )
            PHOTO_LAYER_BRUSH -> listOf(
                PhotoToolAction("Delete", danger = true) { deleteSelectedOrTopLayer() },
                PhotoToolAction("Brush") { showDrawPanel() },
                PhotoToolAction("Color") { showBrushColorMixer() },
                PhotoToolAction("Eraser") { setBrush(BRUSH_PEN, eraser = true) },
                PhotoToolAction("Undo") { canvasView.clearLastBrushStroke() },
                PhotoToolAction("Layers") { showLayerPanel() },
            )
            else -> listOf(
                PhotoToolAction("Add Text") { addText() },
                PhotoToolAction("Import") { pickImage() },
                PhotoToolAction("Shapes") { showShapesPanel() },
                PhotoToolAction("BG") { showBackgroundPanel() },
                PhotoToolAction("AI") { showAiPanel() },
            )
        }
    }

    private fun showPanel(
        title: String,
        rows: List<List<PhotoToolAction>>,
        toolbarActions: List<PhotoToolAction> = emptyList(),
        backAction: PhotoToolAction? = null,
    ) {
        if (!::activeToolPanel.isInitialized) return
        expandToolPanel()
        activeToolPanel.removeAllViews()
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (backAction != null) {
            headerRow.addView(
                panelButton(backAction.label, backAction.danger, backAction.onClick),
                LinearLayout.LayoutParams(dp(72), dp(32)).apply {
                    marginStart = dp(4)
                    marginEnd = dp(6)
                },
            )
        }
        headerRow.addView(
            TextView(this).apply {
                text = title
                setTextColor(Color.rgb(23, 116, 171))
                textSize = 11.5f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, dp(4), dp(3))
            },
            LinearLayout.LayoutParams(0, dp(32), 1f),
        )
        headerRow.addView(
            panelButton("Hide") { collapseToolPanel() },
            LinearLayout.LayoutParams(dp(70), dp(32)).apply {
                marginStart = dp(6)
                marginEnd = dp(4)
            },
        )
        activeToolPanel.addView(
            headerRow,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(32)),
        )
        val actionRail = (toolbarActions + rows.flatten())
            .distinctBy { "${it.label}:${it.danger}" }
        if (actionRail.isNotEmpty()) {
            activeToolPanel.addView(buildToolbarScroller(actionRail))
        }
        activeToolScroll.post { activeToolScroll.scrollTo(0, 0) }
    }

    private fun expandToolPanel(heightDp: Int = TOOL_PANEL_COMPACT_HEIGHT_DP) {
        if (!::activeToolScroll.isInitialized) return
        setToolPanelHeight(heightDp)
        activeToolScroll.visibility = View.VISIBLE
    }

    private fun setToolPanelHeight(heightDp: Int) {
        if (!::activeToolScroll.isInitialized) return
        val params = activeToolScroll.layoutParams
        val targetHeight = dp(heightDp)
        if (params.height != targetHeight) {
            params.height = targetHeight
            activeToolScroll.layoutParams = params
        }
    }

    private fun collapseToolPanel() {
        if (!::activeToolScroll.isInitialized) return
        canvasView.drawModeEnabled = false
        activeToolPanel.removeAllViews()
        val params = activeToolScroll.layoutParams
        if (params.height != 0) {
            params.height = 0
            activeToolScroll.layoutParams = params
        }
        activeToolScroll.visibility = View.GONE
    }

    private fun showToolsForSelection(item: PhotoLayerItem?) {
        when (item?.type) {
            PHOTO_LAYER_IMAGE -> showImagePanel()
            PHOTO_LAYER_BACKGROUND -> showBackgroundPanel()
            PHOTO_LAYER_TEXT -> showTextTools()
            PHOTO_LAYER_SHAPE -> showShapesPanel()
            PHOTO_LAYER_BRUSH -> showDrawPanel()
        }
    }

    private fun buildToolbarScroller(actions: List<PhotoToolAction>): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(1), 0, dp(5))
        }
        actions.forEach { action ->
            row.addView(
                panelButton(action.label, action.danger, action.onClick),
                LinearLayout.LayoutParams(dp(82), dp(36)).apply {
                    marginStart = dp(3)
                    marginEnd = dp(3)
                },
            )
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun showWelcomePanel() {
        showPanel(
            "Photo Studio",
            listOf(
                listOf(
                    PhotoToolAction("Add Text") { addText() },
                    PhotoToolAction("Add Image") { pickImage() },
                    PhotoToolAction("Template") { showAiPanel() },
                ),
                listOf(
                    PhotoToolAction("Shapes") { showShapesPanel() },
                    PhotoToolAction("Draw") { showDrawPanel() },
                    PhotoToolAction("Layers") { showLayerPanel() },
                ),
                listOf(
                    PhotoToolAction("BG Color") { showCanvasBackgroundMixer() },
                    PhotoToolAction("Export") { showExportPanel() },
                    PhotoToolAction("Settings") { showSettingsPanel() },
                ),
            ),
        )
    }

    private fun showTextTools() {
        showPanel(
            "Text Tools",
            listOf(
                listOf(
                    PhotoToolAction("Delete", danger = true) { deleteSelectedOrTopLayer() },
                    PhotoToolAction("Add") { addText() },
                    PhotoToolAction("Edit") { editText() },
                ),
                listOf(
                    PhotoToolAction("Transform") { showLayerTransformPanel() },
                    PhotoToolAction("Blend") { showBlendModePanel() },
                    PhotoToolAction("Font") { showTextFontPanel() },
                ),
                listOf(
                    PhotoToolAction("Style") { showTextStylePanel() },
                    PhotoToolAction("Color") { showTextColorMixer() },
                    PhotoToolAction("Gradient") { showTextGradientPanel() },
                ),
                listOf(
                    PhotoToolAction("BG") { showTextBackgroundMixer() },
                    PhotoToolAction("Stroke") { showStrokePanel() },
                    PhotoToolAction("Shadow") { showTextShadowPanel() },
                    PhotoToolAction("Spacing") { showTextSpacingPanel() },
                    PhotoToolAction("Curve") { showTextCurvePanel() },
                    PhotoToolAction("Preset") { showTextPresets() },
                ),
            ),
        )
    }

    private fun showTextFontPanel() {
        val layer = ensureTextLayer()
        showPanel(
            "Font",
            listOf(
                listOf(
                    PhotoToolAction("Sans") { setTextFont("sans") },
                    PhotoToolAction("Serif") { setTextFont("serif") },
                    PhotoToolAction("Mono") { setTextFont("mono") },
                ),
                listOf(
                    PhotoToolAction("Condense") { setTextFont("condensed") },
                    PhotoToolAction("Display") { setTextFont("display") },
                    PhotoToolAction("Edit") { editText() },
                ),
            ),
            backAction = PhotoToolAction("Back") { showTextTools() },
        )
        activeToolPanel.addView(
            sliderRow("Size", 220, layer.fontSize.toInt().coerceIn(18, 220)) { value ->
                canvasView.updateSelectedTextLayer { it.fontSize = value.toFloat().coerceAtLeast(18f) }
            },
        )
    }

    private fun showTextStylePanel() {
        val layer = ensureTextLayer()
        showPanel(
            "Text Style",
            listOf(
                listOf(
                    PhotoToolAction("Bold") { toggleBold() },
                    PhotoToolAction("Italic") { toggleItalic() },
                    PhotoToolAction("Under") { toggleUnderline() },
                ),
                listOf(
                    PhotoToolAction("Left") { setTextAlign(TEXT_ALIGN_LEFT) },
                    PhotoToolAction("Center") { setTextAlign(TEXT_ALIGN_CENTER) },
                    PhotoToolAction("Right") { setTextAlign(TEXT_ALIGN_RIGHT) },
                ),
            ),
            toolbarActions = listOf(
                PhotoToolAction("Color") { showTextColorMixer() },
                PhotoToolAction("BG") { showTextBackgroundMixer() },
                PhotoToolAction("Stroke") { showStrokePanel() },
            ),
            backAction = PhotoToolAction("Back") { showTextTools() },
        )
        activeToolPanel.addView(
            sliderRow("Size", 220, layer.fontSize.toInt().coerceIn(18, 220)) { value ->
                canvasView.updateSelectedTextLayer { it.fontSize = value.toFloat().coerceAtLeast(18f) }
            },
        )
    }

    private fun showTextSpacingPanel() {
        val layer = ensureTextLayer()
        showPanel(
            "Text Spacing",
            emptyList(),
            backAction = PhotoToolAction("Back") { showTextTools() },
        )
        activeToolPanel.addView(
            sliderRow("Letters", 32, (layer.letterSpacing + 8f).toInt().coerceIn(0, 32)) { value ->
                canvasView.updateSelectedTextLayer { it.letterSpacing = value - 8f }
            },
        )
        activeToolPanel.addView(
            sliderRow("Line", 220, (layer.lineHeight * 100f).toInt().coerceIn(80, 220)) { value ->
                canvasView.updateSelectedTextLayer { it.lineHeight = value / 100f }
            },
        )
        activeToolPanel.addView(
            sliderRow("Padding", 90, layer.backgroundPadding.toInt().coerceIn(0, 90)) { value ->
                canvasView.updateSelectedTextLayer { it.backgroundPadding = value.toFloat() }
            },
        )
    }

    private fun showTextCurvePanel() {
        val layer = ensureTextLayer()
        showPanel(
            "Curve and 3D",
            listOf(
                listOf(
                    PhotoToolAction("Flat") { canvasView.updateSelectedTextLayer { it.curveAmount = 0f } },
                    PhotoToolAction("Soft Arc") { canvasView.updateSelectedTextLayer { it.curveAmount = 18f } },
                    PhotoToolAction("Depth") { canvasView.updateSelectedTextLayer { it.depth = 8f } },
                ),
            ),
            backAction = PhotoToolAction("Back") { showTextTools() },
        )
        activeToolPanel.addView(
            sliderRow("Curve", 200, (layer.curveAmount + 100f).toInt().coerceIn(0, 200)) { value ->
                canvasView.updateSelectedTextLayer { it.curveAmount = value - 100f }
            },
        )
        activeToolPanel.addView(
            sliderRow("Depth", 24, layer.depth.toInt().coerceIn(0, 24)) { value ->
                canvasView.updateSelectedTextLayer { it.depth = value.toFloat() }
            },
        )
        activeToolPanel.addView(
            sliderRow("Opacity", 255, layer.opacity.coerceIn(0, 255)) { value ->
                canvasView.updateSelectedTextLayer { it.opacity = value }
            },
        )
    }

    private fun showTextShadowPanel() {
        val layer = ensureTextLayer()
        showPanel(
            "Text Shadow",
            listOf(
                listOf(
                    PhotoToolAction("Toggle") { toggleShadow() },
                    PhotoToolAction("Soft") { setTextShadow(14f, 3f, 5f, 150) },
                    PhotoToolAction("Hard") { setTextShadow(4f, 7f, 7f, 220) },
                ),
            ),
            backAction = PhotoToolAction("Back") { showTextTools() },
        )
        activeToolPanel.addView(
            sliderRow("Blur", 40, layer.shadowBlur.toInt().coerceIn(0, 40)) { value ->
                canvasView.updateSelectedTextLayer {
                    it.shadowEnabled = true
                    it.shadowBlur = value.toFloat()
                }
            },
        )
        activeToolPanel.addView(
            sliderRow("Offset X", 60, (layer.shadowOffsetX + 30f).toInt().coerceIn(0, 60)) { value ->
                canvasView.updateSelectedTextLayer {
                    it.shadowEnabled = true
                    it.shadowOffsetX = value - 30f
                }
            },
        )
        activeToolPanel.addView(
            sliderRow("Opacity", 255, layer.shadowOpacity.coerceIn(0, 255)) { value ->
                canvasView.updateSelectedTextLayer {
                    it.shadowEnabled = true
                    it.shadowOpacity = value
                }
            },
        )
    }

    private fun showTextGradientPanel() {
        val layer = ensureTextLayer()
        showPanel(
            "Text Gradient",
            listOf(
                listOf(
                    PhotoToolAction("On") { canvasView.updateSelectedTextLayer { it.gradientEnabled = true } },
                    PhotoToolAction("Off") { canvasView.updateSelectedTextLayer { it.gradientEnabled = false } },
                    PhotoToolAction("Start") { showTextGradientStartMixer() },
                ),
                listOf(
                    PhotoToolAction("End") { showTextGradientEndMixer() },
                    PhotoToolAction("Sunset") { setTextGradient(Color.rgb(255, 214, 64), Color.rgb(239, 83, 80)) },
                    PhotoToolAction("Ocean") { setTextGradient(Color.rgb(38, 198, 218), Color.rgb(66, 165, 245)) },
                ),
                listOf(
                    PhotoToolAction("Neon") { setTextGradient(Color.rgb(0, 229, 255), Color.rgb(255, 64, 129)) },
                    PhotoToolAction("Gold") { setTextGradient(Color.rgb(255, 236, 139), Color.rgb(255, 145, 0)) },
                    PhotoToolAction("Color") { showTextColorMixer() },
                ),
            ),
            toolbarActions = listOf(
                PhotoToolAction("Start") { showTextGradientStartMixer() },
                PhotoToolAction("End") { showTextGradientEndMixer() },
                PhotoToolAction("Text BG") { showTextBackgroundMixer() },
                PhotoToolAction("Stroke") { showStrokePanel() },
            ),
            backAction = PhotoToolAction("Back") { showTextTools() },
        )
        activeToolPanel.addView(
            sliderRow("Opacity", 255, layer.opacity.coerceIn(0, 255)) { value ->
                canvasView.updateSelectedTextLayer { it.opacity = value }
            },
        )
    }

    private fun showImagePanel() {
        showPanel(
            "Image",
            listOf(
                listOf(
                    PhotoToolAction("Delete", danger = true) { deleteSelectedOrTopLayer() },
                    PhotoToolAction("Add Image") { pickImage() },
                    PhotoToolAction("BG Image") { pickBackgroundImage() },
                ),
                listOf(
                    PhotoToolAction("Transform") { showLayerTransformPanel() },
                    PhotoToolAction("Blend") { showBlendModePanel() },
                    PhotoToolAction("Adjust") { showImageLayerPanel() },
                ),
                listOf(
                    PhotoToolAction("Bigger") { scaleSelected(1.12f) },
                    PhotoToolAction("Smaller") { scaleSelected(0.90f) },
                    PhotoToolAction("Top") { moveSelectedToFront() },
                ),
                listOf(
                    PhotoToolAction("Copy") { canvasView.duplicateSelectedLayer() },
                    PhotoToolAction("Size") { showSizePanel() },
                    PhotoToolAction("BG Tools") { showBackgroundPanel() },
                    PhotoToolAction("Export") { showExportPanel() },
                ),
            ),
            toolbarActions = listOf(
                PhotoToolAction("Delete", danger = true) { deleteSelectedOrTopLayer() },
                PhotoToolAction("Transform") { showLayerTransformPanel() },
                PhotoToolAction("Blend") { showBlendModePanel() },
                PhotoToolAction("Add Image") { pickImage() },
                PhotoToolAction("BG Image") { pickBackgroundImage() },
                PhotoToolAction("Adjust") { showImageLayerPanel() },
                PhotoToolAction("Bigger") { scaleSelected(1.12f) },
                PhotoToolAction("Smaller") { scaleSelected(0.90f) },
                PhotoToolAction("Top") { moveSelectedToFront() },
            ),
        )
    }

    private fun showImageLayerPanel() {
        val layer = canvasView.selectedImageLayer()
        showPanel(
            "Selected Image",
            listOf(
                listOf(
                    PhotoToolAction("Delete", danger = true) { deleteSelectedOrTopLayer() },
                    PhotoToolAction("Add Image") { pickImage() },
                    PhotoToolAction("Copy") { canvasView.duplicateSelectedLayer() },
                ),
                listOf(
                    PhotoToolAction("Clean") { applySelectedImageLook(12f, 1.12f, 1.16f, 0f, 0f) },
                    PhotoToolAction("Vivid") { applySelectedImageLook(10f, 1.24f, 1.42f, 4f, 0f) },
                    PhotoToolAction("Warm") { applySelectedImageLook(8f, 1.12f, 1.20f, 32f, 0f) },
                ),
                listOf(
                    PhotoToolAction("Noir") { applySelectedImageLook(2f, 1.18f, 0f, -4f, 0f) },
                    PhotoToolAction("Reset") { resetSelectedImageLook() },
                    PhotoToolAction("Fit") { canvasView.updateSelectedImageLayer { it.scale = 1f; it.rotationDeg = 0f } },
                ),
                listOf(
                    PhotoToolAction("Border") { showImageBorderMixer() },
                    PhotoToolAction("No Border") { canvasView.updateSelectedImageLayer { it.borderWidth = 0f } },
                    PhotoToolAction("Shadow") { canvasView.updateSelectedImageLayer { it.shadowEnabled = !it.shadowEnabled } },
                ),
                listOf(
                    PhotoToolAction("Transform") { showLayerTransformPanel() },
                    PhotoToolAction("Blend") { showBlendModePanel() },
                    PhotoToolAction("Top") { moveSelectedToFront() },
                ),
                listOf(
                    PhotoToolAction("Bigger") { scaleSelected(1.12f) },
                    PhotoToolAction("Smaller") { scaleSelected(0.90f) },
                ),
            ),
            backAction = PhotoToolAction("Back") { showImagePanel() },
        )
        setToolPanelHeight(TOOL_PANEL_TALL_HEIGHT_DP)
        activeToolPanel.addView(
            sliderRow("Opacity", 255, (layer?.opacity ?: 255).coerceIn(0, 255)) { value ->
                canvasView.updateSelectedImageLayer { it.opacity = value }
            },
        )
        activeToolPanel.addView(
            sliderRow("Scale", 300, (((layer?.scale ?: 1f) * 100f).toInt()).coerceIn(18, 300)) { value ->
                canvasView.updateSelectedImageLayer { it.scale = value / 100f }
            },
        )
        activeToolPanel.addView(
            sliderRow("Bright", 200, (((layer?.brightness ?: 0f) + 100f).toInt()).coerceIn(0, 200)) { value ->
                canvasView.updateSelectedImageLayer { it.brightness = value - 100f }
            },
        )
        activeToolPanel.addView(
            sliderRow("Contrast", 250, (((layer?.contrast ?: 1f) * 100f).toInt()).coerceIn(35, 250)) { value ->
                canvasView.updateSelectedImageLayer { it.contrast = value / 100f }
            },
        )
        activeToolPanel.addView(
            sliderRow("Saturate", 250, (((layer?.saturation ?: 1f) * 100f).toInt()).coerceIn(0, 250)) { value ->
                canvasView.updateSelectedImageLayer { it.saturation = value / 100f }
            },
        )
        activeToolPanel.addView(
            sliderRow("Warmth", 200, (((layer?.temperature ?: 0f) + 100f).toInt()).coerceIn(0, 200)) { value ->
                canvasView.updateSelectedImageLayer { it.temperature = value - 100f }
            },
        )
        activeToolPanel.addView(
            sliderRow("Blur", 48, (layer?.blur ?: 0f).toInt().coerceIn(0, 48)) { value ->
                canvasView.updateSelectedImageLayer { it.blur = value.toFloat() }
            },
        )
        activeToolPanel.addView(
            sliderRow("Border", 64, (layer?.borderWidth ?: 0f).toInt().coerceIn(0, 64)) { value ->
                canvasView.updateSelectedImageLayer {
                    it.borderWidth = value.toFloat()
                    if (value > 0 && Color.alpha(it.borderColor) == 0) it.borderColor = Color.WHITE
                }
            },
        )
        activeToolPanel.addView(
            sliderRow("Radius", 180, (layer?.cornerRadius ?: 0f).toInt().coerceIn(0, 180)) { value ->
                canvasView.updateSelectedImageLayer { it.cornerRadius = value.toFloat() }
            },
        )
        activeToolPanel.addView(
            sliderRow("Shadow", 96, (layer?.shadowBlur ?: 0f).toInt().coerceIn(0, 96)) { value ->
                canvasView.updateSelectedImageLayer {
                    it.shadowEnabled = value > 0
                    it.shadowBlur = value.toFloat()
                }
            },
        )
    }

    private fun showImageAdjustForSelection() {
        if (canvasView.selectedImageLayer() != null) {
            showImageLayerPanel()
        } else {
            showImageAdjustPanel()
        }
    }

    private fun applySelectedImageLook(
        brightness: Float,
        contrast: Float,
        saturation: Float,
        temperature: Float,
        blur: Float,
    ) {
        canvasView.updateSelectedImageLayer {
            it.brightness = brightness
            it.contrast = contrast
            it.saturation = saturation
            it.temperature = temperature
            it.blur = blur
        }
        showImageLayerPanel()
    }

    private fun resetSelectedImageLook() {
        applySelectedImageLook(0f, 1f, 1f, 0f, 0f)
    }

    private fun showImageBorderMixer() {
        chooseColor("Image Border") { color ->
            canvasView.updateSelectedImageLayer {
                it.borderColor = color
                if (it.borderWidth <= 0f) it.borderWidth = 6f
            }
            showImageLayerPanel()
        }
    }

    private fun showLayerTransformPanel() {
        val item = canvasView.selectedLayerItem()
        if (item == null || item.type == PHOTO_LAYER_BACKGROUND) {
            Toast.makeText(this, "Select image, text or shape first", Toast.LENGTH_SHORT).show()
            return
        }
        showPanel(
            "Transform",
            listOf(
                listOf(
                    PhotoToolAction("Delete", danger = true) { deleteSelectedOrTopLayer() },
                    PhotoToolAction("Align L") { canvasView.alignSelectedLayer(horizontal = PhotoCanvasView.ALIGN_START) },
                    PhotoToolAction("Align C") { canvasView.alignSelectedLayer(horizontal = PhotoCanvasView.ALIGN_CENTER) },
                    PhotoToolAction("Align R") { canvasView.alignSelectedLayer(horizontal = PhotoCanvasView.ALIGN_END) },
                ),
                listOf(
                    PhotoToolAction("Top") { canvasView.alignSelectedLayer(vertical = PhotoCanvasView.ALIGN_START) },
                    PhotoToolAction("Middle") { canvasView.alignSelectedLayer(vertical = PhotoCanvasView.ALIGN_CENTER) },
                    PhotoToolAction("Bottom") { canvasView.alignSelectedLayer(vertical = PhotoCanvasView.ALIGN_END) },
                    PhotoToolAction("Front") { moveSelectedToFront() },
                ),
                listOf(
                    PhotoToolAction("Flip X") { canvasView.flipSelectedLayer(horizontal = true) },
                    PhotoToolAction("Flip Y") { canvasView.flipSelectedLayer(horizontal = false) },
                    PhotoToolAction("Rot L") { canvasView.rotateSelectedLayerBy(-15f) },
                    PhotoToolAction("Rot R") { canvasView.rotateSelectedLayerBy(15f) },
                ),
                listOf(
                    PhotoToolAction("Blend") { showBlendModePanel() },
                    PhotoToolAction("Copy") { canvasView.duplicateSelectedLayer() },
                    PhotoToolAction("Lock") { canvasView.toggleSelectedLock() },
                ),
            ),
            backAction = PhotoToolAction("Back") { showToolsForSelection(canvasView.selectedLayerItem()) },
        )
        setToolPanelHeight(TOOL_PANEL_CONTENT_HEIGHT_DP)
        activeToolPanel.addView(
            sliderRow("Scale", 800, (canvasView.selectedLayerScale() * 100f).toInt().coerceIn(18, 800)) { value ->
                canvasView.setSelectedLayerScale(value / 100f)
            },
        )
        activeToolPanel.addView(
            sliderRow("Rotate", 360, (canvasView.selectedLayerRotation() + 180f).toInt().coerceIn(0, 360)) { value ->
                canvasView.setSelectedLayerRotation(value - 180f)
            },
        )
        activeToolPanel.addView(
            sliderRow("Opacity", 255, canvasView.selectedLayerOpacity().coerceIn(0, 255)) { value ->
                canvasView.setSelectedLayerOpacity(value)
            },
        )
    }

    private fun showBlendModePanel() {
        val item = canvasView.selectedLayerItem()
        if (item == null || item.type == PHOTO_LAYER_BACKGROUND) {
            Toast.makeText(this, "Select image, text or shape first", Toast.LENGTH_SHORT).show()
            return
        }
        showPanel(
            "Blend",
            listOf(
                listOf(
                    PhotoToolAction("Normal") { canvasView.setSelectedBlendMode(BLEND_NORMAL) },
                    PhotoToolAction("Multiply") { canvasView.setSelectedBlendMode(BLEND_MULTIPLY) },
                    PhotoToolAction("Screen") { canvasView.setSelectedBlendMode(BLEND_SCREEN) },
                    PhotoToolAction("Overlay") { canvasView.setSelectedBlendMode(BLEND_OVERLAY) },
                ),
                listOf(
                    PhotoToolAction("Transform") { showLayerTransformPanel() },
                    PhotoToolAction("Copy") { canvasView.duplicateSelectedLayer() },
                    PhotoToolAction("Delete", danger = true) { deleteSelectedOrTopLayer() },
                ),
            ),
            backAction = PhotoToolAction("Back") { showToolsForSelection(canvasView.selectedLayerItem()) },
        )
        setToolPanelHeight(TOOL_PANEL_CONTENT_HEIGHT_DP)
        activeToolPanel.addView(
            sliderRow("Opacity", 255, canvasView.selectedLayerOpacity().coerceIn(0, 255)) { value ->
                canvasView.setSelectedLayerOpacity(value)
            },
        )
    }

    private fun showProEffectsPanel() {
        showPanel(
            "Effects",
            listOf(
                listOf(
                    PhotoToolAction("Soft Light") { applySoftLightLook() },
                    PhotoToolAction("Golden") { applyGoldenHourLook() },
                    PhotoToolAction("Cinematic") { applyCinematicLook() },
                ),
                listOf(
                    PhotoToolAction("Curves") { showImageCurvePanel() },
                    PhotoToolAction("Tint") { showImageTintMixer() },
                    PhotoToolAction("AI Cut") { showImageAiPanel() },
                ),
                listOf(
                    PhotoToolAction("Vivid") { applyImageLook(10f, 1.24f, 1.42f, Color.TRANSPARENT, 0f) },
                    PhotoToolAction("Noir") { applyNoirLook() },
                    PhotoToolAction("Clear FX") { clearImageLook() },
                ),
            ),
            toolbarActions = listOf(
                PhotoToolAction("Adjust") { showImageAdjustPanel() },
                PhotoToolAction("Filters") { showImageFilterPanel() },
                PhotoToolAction("Export") { showExportPanel() },
            ),
        )
    }

    private fun showSizePanel() {
        showPanel(
            "Canvas Size - ${canvasView.canvasSizeSummary()}",
            listOf(
                listOf(
                    PhotoToolAction("Square") { applyCanvasSizePresetFromPanel("Square", 1080, 1080) },
                    PhotoToolAction("YT Thumb") { applyCanvasSizePresetFromPanel("YouTube Thumbnail 16:9", 1280, 720) },
                    PhotoToolAction("YT Short") { applyCanvasSizePresetFromPanel("YouTube Shorts 9:16", 1080, 1920) },
                ),
                listOf(
                    PhotoToolAction("Insta 1:1") { applyCanvasSizePresetFromPanel("Instagram Post 1:1", 1080, 1080) },
                    PhotoToolAction("Insta 4:5") { applyCanvasSizePresetFromPanel("Instagram Portrait 4:5", 1080, 1350) },
                    PhotoToolAction("Story 9:16") { applyCanvasSizePresetFromPanel("Story/Reel 9:16", 1080, 1920) },
                ),
                listOf(
                    PhotoToolAction("Facebook") { applyCanvasSizePresetFromPanel("Facebook Feed", 1200, 630) },
                    PhotoToolAction("FB Cover") { applyCanvasSizePresetFromPanel("Facebook Cover", 1640, 924) },
                    PhotoToolAction("Landscape") { applyCanvasSizePresetFromPanel("Landscape 16:9", 1920, 1080) },
                ),
                listOf(
                    PhotoToolAction("Profile") { applyCanvasSizePresetFromPanel("Profile Crop", 1080, 1080) },
                    PhotoToolAction("Banner") { applyCanvasSizePresetFromPanel("Wide Banner", 1500, 500) },
                    PhotoToolAction("Custom") { showCustomSizeDialog() },
                ),
            ),
            toolbarActions = listOf(
                PhotoToolAction("Custom") { showCustomSizeDialog() },
                PhotoToolAction("BG Color") { showCanvasBackgroundMixer() },
                PhotoToolAction("Export") { showExportPanel() },
            ),
        )
    }

    private fun applyCanvasSizePreset(label: String, width: Int, height: Int) {
        canvasView.setCanvasSize(width, height, label)
        refreshStatus()
        Toast.makeText(this, "$label ${width}x${height}", Toast.LENGTH_SHORT).show()
    }

    private fun applyCanvasSizePresetFromPanel(label: String, width: Int, height: Int) {
        applyCanvasSizePreset(label, width, height)
        showSizePanel()
    }

    private fun applyCanvasCropPresetFromPanel(label: String, width: Int, height: Int) {
        applyCanvasSizePreset(label, width, height)
        showImageCropPanel()
    }

    private fun showCustomSizeDialog() {
        val widthInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Width px"
            setSingleLine(true)
            setText(canvasView.canvasOutputWidth.toString())
            selectAll()
        }
        val heightInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Height px"
            setSingleLine(true)
            setText(canvasView.canvasOutputHeight.toString())
            selectAll()
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
            addView(
                TextView(this@PhotoEditorActivity).apply {
                    text = "Set export canvas size in pixels"
                    setTextColor(Color.rgb(20, 54, 84))
                    textSize = 13f
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34)),
            )
            addView(widthInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)))
            addView(heightInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)))
        }
        AlertDialog.Builder(this)
            .setTitle("Custom Size")
            .setView(content)
            .setPositiveButton("Apply") { _, _ ->
                val width = widthInput.text.toString().toIntOrNull()
                val height = heightInput.text.toString().toIntOrNull()
                if (
                    width == null ||
                    height == null ||
                    width !in MIN_CUSTOM_CANVAS_SIDE..MAX_CUSTOM_CANVAS_SIDE ||
                    height !in MIN_CUSTOM_CANVAS_SIDE..MAX_CUSTOM_CANVAS_SIDE
                ) {
                    Toast.makeText(this, "Use ${MIN_CUSTOM_CANVAS_SIDE}-${MAX_CUSTOM_CANVAS_SIDE} px", Toast.LENGTH_SHORT).show()
                } else {
                    applyCanvasSizePreset("Custom", width, height)
                    showSizePanel()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showImageAdjustPanel() {
        showPanel(
            "Image Adjust",
            emptyList(),
            toolbarActions = listOf(
                PhotoToolAction("Image") { pickImage() },
                PhotoToolAction("Filter") { showImageFilterPanel() },
                PhotoToolAction("Curves") { showImageCurvePanel() },
                PhotoToolAction("Crop") { showImageCropPanel() },
                PhotoToolAction("Tint") { showImageTintMixer() },
                PhotoToolAction("AI Cut") { showImageAiPanel() },
            ),
            backAction = PhotoToolAction("Back") { showImagePanel() },
        )
        addAdjustMetricStrip()
        setToolPanelHeight(TOOL_PANEL_CONTENT_HEIGHT_DP)
    }

    private fun showImageFilterPanel() {
        showPanel(
            "Image Filter",
            emptyList(),
            toolbarActions = listOf(
                PhotoToolAction("Image") { pickImage() },
                PhotoToolAction("Adjust") { showImageAdjustPanel() },
                PhotoToolAction("Intensity") { showFilterIntensityPanel() },
                PhotoToolAction("Curves") { showImageCurvePanel() },
                PhotoToolAction("Tint") { showImageTintMixer() },
            ),
            backAction = PhotoToolAction("Back") { showImagePanel() },
        )
        addFilterPresetStrip()
        setToolPanelHeight(TOOL_PANEL_CONTENT_HEIGHT_DP)
    }

    private fun addFilterPresetStrip() {
        val presets = listOf(
            PhotoFilterPreset("Original", Color.rgb(62, 112, 160), Color.rgb(245, 248, 255)) {
                applyImageLook(0f, 1f, 1f, Color.TRANSPARENT, 0f)
            },
            PhotoFilterPreset("Bright", Color.rgb(106, 184, 255), Color.rgb(255, 246, 196)) {
                applyImageLook(18f, 1.08f, 1.18f, Color.TRANSPARENT, 0f)
            },
            PhotoFilterPreset("Warm", Color.rgb(255, 178, 90), Color.rgb(134, 72, 34)) {
                applyGoldenHourLook()
            },
            PhotoFilterPreset("Cool", Color.rgb(38, 120, 185), Color.rgb(163, 218, 255)) {
                applyImageLook(0f, 1.08f, 1.05f, Color.rgb(60, 170, 255), 0.20f, -38f)
            },
            PhotoFilterPreset("Vivid", Color.rgb(28, 190, 130), Color.rgb(255, 214, 64)) {
                applyImageLook(10f, 1.24f, 1.42f, Color.TRANSPARENT, 0f)
            },
            PhotoFilterPreset("Moody", Color.rgb(24, 34, 50), Color.rgb(121, 76, 46)) {
                applyCinematicLook()
            },
            PhotoFilterPreset("B&W", Color.rgb(230, 230, 230), Color.rgb(50, 50, 50)) {
                applyNoirLook()
            },
        )
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }
        row.addView(addFilterCard())
        presets.forEach { preset -> row.addView(filterPresetCard(preset)) }
        activeToolPanel.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(row)
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(84)),
        )
    }

    private fun addFilterCard(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = filterCardBackground(Color.rgb(32, 38, 48), Color.rgb(8, 11, 16), active = false)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                showImageTintMixer()
            }
            addView(
                TextView(this@PhotoEditorActivity).apply {
                    text = "+"
                    setTextColor(Color.WHITE)
                    textSize = 25f
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
            )
            addView(
                TextView(this@PhotoEditorActivity).apply {
                    text = "Add Filter"
                    setTextColor(Color.WHITE)
                    textSize = 10f
                    gravity = Gravity.CENTER
                    setSingleLine(true)
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(20)),
            )
            layoutParams = LinearLayout.LayoutParams(dp(82), dp(76)).apply {
                marginEnd = dp(7)
            }
        }

    private fun filterPresetCard(preset: PhotoFilterPreset): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = filterCardBackground(preset.startColor, preset.endColor, active = preset.label == "Original")
            isClickable = true
            isFocusable = true
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                preset.onApply()
                refreshStatus()
            }
            addView(
                TextView(this@PhotoEditorActivity).apply {
                    text = preset.label.take(3).uppercase(Locale.US)
                    setTextColor(Color.WHITE)
                    textSize = 11.5f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setShadowLayer(5f, 0f, 2f, Color.BLACK)
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
            )
            addView(
                TextView(this@PhotoEditorActivity).apply {
                    text = preset.label
                    setTextColor(Color.WHITE)
                    textSize = 10f
                    gravity = Gravity.CENTER
                    setSingleLine(true)
                    setShadowLayer(4f, 0f, 1f, Color.BLACK)
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(20)),
            )
            layoutParams = LinearLayout.LayoutParams(dp(82), dp(76)).apply {
                marginEnd = dp(7)
            }
        }

    private fun addAdjustMetricStrip() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }
        row.addView(
            metricSliderCard(
                "Brightness",
                200,
                (canvasView.imageBrightness + 100f).toInt().coerceIn(0, 200),
                { value -> signedValue(value - 100) },
            ) { value ->
                canvasView.imageBrightness = value - 100f
            },
        )
        row.addView(
            metricSliderCard(
                "Contrast",
                200,
                ((canvasView.imageContrast - 0.5f) * 100f).toInt().coerceIn(0, 200),
                { value -> signedValue(value - 50) },
            ) { value ->
                canvasView.imageContrast = 0.5f + value / 100f
            },
        )
        row.addView(
            metricSliderCard(
                "Saturation",
                200,
                (canvasView.imageSaturation * 100f).toInt().coerceIn(0, 200),
                { value -> signedValue(value - 100) },
            ) { value ->
                canvasView.imageSaturation = value / 100f
            },
        )
        row.addView(
            metricSliderCard(
                "Blur",
                32,
                canvasView.imageBlur.toInt().coerceIn(0, 32),
                { value -> signedValue(value) },
            ) { value ->
                canvasView.imageBlur = value.toFloat()
            },
        )
        row.addView(
            metricSliderCard(
                "Highlights",
                200,
                (canvasView.imageCurveHighlights + 100f).toInt().coerceIn(0, 200),
                { value -> signedValue(value - 100) },
            ) { value ->
                canvasView.imageCurveHighlights = value - 100f
            },
        )
        row.addView(
            metricSliderCard(
                "Shadows",
                200,
                (canvasView.imageCurveShadows + 100f).toInt().coerceIn(0, 200),
                { value -> signedValue(value - 100) },
            ) { value ->
                canvasView.imageCurveShadows = value - 100f
            },
        )
        activeToolPanel.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(row)
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(94)),
        )
    }

    private fun metricSliderCard(
        label: String,
        maxValue: Int,
        progressValue: Int,
        valueTextFor: (Int) -> String,
        onChanged: (Int) -> Unit,
    ): View {
        val valueText = TextView(this).apply {
            text = valueTextFor(progressValue)
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundSolid(Color.rgb(10, 14, 20), dp(13).toFloat(), Color.rgb(32, 40, 52))
            addView(
                TextView(this@PhotoEditorActivity).apply {
                    text = label
                    setTextColor(Color.WHITE)
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setSingleLine(true)
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(20)),
            )
            addView(valueText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(18)))
            addView(
                SeekBar(this@PhotoEditorActivity).apply {
                    max = maxValue
                    progress = progressValue.coerceIn(0, maxValue)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                            valueText.text = valueTextFor(progress)
                            onChanged(progress)
                        }

                        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                    })
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34)),
            )
            layoutParams = LinearLayout.LayoutParams(dp(124), dp(84)).apply {
                marginEnd = dp(7)
            }
        }
    }

    private fun showImageCurvePanel() {
        showPanel(
            "Image Curves",
            listOf(
                listOf(
                    PhotoToolAction("Punch") { applyCurvePreset(-8f, 10f, 16f) },
                    PhotoToolAction("Film") { applyCurvePreset(16f, -4f, -10f) },
                    PhotoToolAction("Bright") { applyCurvePreset(6f, 12f, 22f) },
                ),
                listOf(
                    PhotoToolAction("Moody") { applyCurvePreset(-22f, -6f, 8f) },
                    PhotoToolAction("Flat") { applyCurvePreset(0f, 0f, 0f) },
                    PhotoToolAction("AI Cut") { showImageAiPanel() },
                ),
            ),
            toolbarActions = listOf(
                PhotoToolAction("Adjust") { showImageAdjustPanel() },
                PhotoToolAction("Filter") { showImageFilterPanel() },
                PhotoToolAction("Tint") { showImageTintMixer() },
            ),
            backAction = PhotoToolAction("Back") { showImagePanel() },
        )
        activeToolPanel.addView(
            sliderRow("Shadows", 200, (canvasView.imageCurveShadows + 100f).toInt().coerceIn(0, 200)) { value ->
                canvasView.imageCurveShadows = value - 100f
            },
        )
        activeToolPanel.addView(
            sliderRow("Midtone", 200, (canvasView.imageCurveMidtones + 100f).toInt().coerceIn(0, 200)) { value ->
                canvasView.imageCurveMidtones = value - 100f
            },
        )
        activeToolPanel.addView(
            sliderRow("High", 200, (canvasView.imageCurveHighlights + 100f).toInt().coerceIn(0, 200)) { value ->
                canvasView.imageCurveHighlights = value - 100f
            },
        )
    }

    private fun showFilterIntensityPanel() {
        showPanel(
            "Filter Intensity",
            emptyList(),
            backAction = PhotoToolAction("Back") { showImageFilterPanel() },
        )
        activeToolPanel.addView(
            sliderRow("Intensity", 100, (canvasView.imageFilterIntensity * 100f).toInt().coerceIn(0, 100)) { value ->
                canvasView.imageFilterIntensity = value / 100f
            },
        )
    }

    private fun showImageCropPanel() {
        showPanel(
            "Crop and Ratio",
            listOf(
                listOf(
                    PhotoToolAction("Fit") { canvasView.imageMaskMode = PhotoCanvasView.IMAGE_MASK_NONE },
                    PhotoToolAction("Fill") { canvasView.imageMaskMode = PhotoCanvasView.IMAGE_MASK_WIDE },
                    PhotoToolAction("Round") { canvasView.imageMaskMode = PhotoCanvasView.IMAGE_MASK_ROUND },
                ),
                listOf(
                    PhotoToolAction("Circle") { canvasView.imageMaskMode = PhotoCanvasView.IMAGE_MASK_CIRCLE },
                    PhotoToolAction("1:1") { applyCanvasCropPresetFromPanel("Square Crop", 1080, 1080) },
                    PhotoToolAction("4:5") { applyCanvasCropPresetFromPanel("Portrait Crop 4:5", 1080, 1350) },
                ),
                listOf(
                    PhotoToolAction("YT 16:9") { applyCanvasCropPresetFromPanel("YouTube Crop 16:9", 1280, 720) },
                    PhotoToolAction("9:16") { applyCanvasCropPresetFromPanel("Vertical Crop 9:16", 1080, 1920) },
                    PhotoToolAction("Custom") { showCustomSizeDialog() },
                ),
            ),
            backAction = PhotoToolAction("Back") { showImagePanel() },
        )
    }

    private fun showImageMaskPanel() {
        showPanel(
            "Image Mask",
            listOf(
                listOf(
                    PhotoToolAction("None") { canvasView.imageMaskMode = PhotoCanvasView.IMAGE_MASK_NONE },
                    PhotoToolAction("Round") { canvasView.imageMaskMode = PhotoCanvasView.IMAGE_MASK_ROUND },
                    PhotoToolAction("Circle") { canvasView.imageMaskMode = PhotoCanvasView.IMAGE_MASK_CIRCLE },
                ),
                listOf(
                    PhotoToolAction("Wide") { canvasView.imageMaskMode = PhotoCanvasView.IMAGE_MASK_WIDE },
                    PhotoToolAction("Soft") { applyImageLook(10f, 0.94f, 0.82f, Color.rgb(255, 245, 220), 0.12f) },
                    PhotoToolAction("Reset") { clearImageLook() },
                ),
            ),
            toolbarActions = listOf(
                PhotoToolAction("Adjust") { showImageAdjustPanel() },
                PhotoToolAction("Filter") { showImageFilterPanel() },
                PhotoToolAction("Curves") { showImageCurvePanel() },
                PhotoToolAction("Tint") { showImageTintMixer() },
            ),
            backAction = PhotoToolAction("Back") { showImagePanel() },
        )
    }

    private fun showImageAiPanel() {
        showPanel(
            "AI Image",
            listOf(
                listOf(
                    PhotoToolAction("BG Remove") { applyAiBackgroundRemove() },
                    PhotoToolAction("Green Cut") { applyGreenScreenCutout() },
                    PhotoToolAction("White Cut") { applyLightCutout() },
                ),
                listOf(
                    PhotoToolAction("Clean") { applyImageLook(8f, 1.08f, 1.08f, Color.TRANSPARENT, 0f) },
                    PhotoToolAction("Portrait") { applyImageLook(10f, 0.98f, 0.95f, Color.rgb(255, 221, 190), 0.18f, 18f) },
                    PhotoToolAction("Sharpen") { applyImageLook(4f, 1.28f, 1.14f, Color.TRANSPARENT, 0f) },
                ),
                listOf(
                    PhotoToolAction("Vignette") { canvasView.imageVignette = 0.42f },
                    PhotoToolAction("Curves") { showImageCurvePanel() },
                    PhotoToolAction("Reset Cut") { resetCutout() },
                ),
            ),
            toolbarActions = listOf(
                PhotoToolAction("Adjust") { showImageAdjustPanel() },
                PhotoToolAction("Filter") { showImageFilterPanel() },
                PhotoToolAction("Mask") { showImageMaskPanel() },
            ),
            backAction = PhotoToolAction("Back") { showImagePanel() },
        )
        activeToolPanel.addView(
            sliderRow("Edge", 180, canvasView.imageCutoutThreshold.toInt().coerceIn(8, 180)) { value ->
                canvasView.imageCutoutThreshold = value.toFloat()
            },
        )
        activeToolPanel.addView(
            sliderRow("Feather", 120, canvasView.imageCutoutFeather.toInt().coerceIn(0, 120)) { value ->
                canvasView.imageCutoutFeather = value.toFloat()
            },
        )
    }

    private fun showShapesPanel() {
        showPanel(
            "Shapes",
            listOf(
                listOf(
                    PhotoToolAction("Delete", danger = true) { deleteSelectedOrTopLayer() },
                    PhotoToolAction("Rect") { addShape(SHAPE_RECTANGLE) },
                    PhotoToolAction("Circle") { addShape(SHAPE_CIRCLE) },
                ),
                listOf(
                    PhotoToolAction("Triangle") { addShape(SHAPE_TRIANGLE) },
                    PhotoToolAction("Line") { addShape(SHAPE_LINE) },
                    PhotoToolAction("Star") { addShape(SHAPE_STAR) },
                ),
                listOf(
                    PhotoToolAction("Arrow") { addShape(SHAPE_ARROW) },
                    PhotoToolAction("Fill") { showShapeFillMixer() },
                    PhotoToolAction("Stroke") { showShapeStrokePanel() },
                ),
                listOf(
                    PhotoToolAction("Transform") { showLayerTransformPanel() },
                    PhotoToolAction("Blend") { showBlendModePanel() },
                    PhotoToolAction("More") { showShapeAdjustPanel() },
                ),
            ),
        )
    }

    private fun showShapeStrokePanel() {
        val layer = ensureShapeLayer()
        showPanel(
            "Shape Stroke",
            listOf(
                listOf(
                    PhotoToolAction("Color") { showShapeStrokeMixer() },
                    PhotoToolAction("Off") { canvasView.updateSelectedShapeLayer { it.strokeWidth = 0f } },
                    PhotoToolAction("Glow") { canvasView.updateSelectedShapeLayer { it.glowEnabled = !it.glowEnabled } },
                ),
            ),
            backAction = PhotoToolAction("Back") { showShapesPanel() },
        )
        activeToolPanel.addView(
            sliderRow("Width", 36, layer.strokeWidth.toInt().coerceIn(0, 36)) { value ->
                canvasView.updateSelectedShapeLayer {
                    it.strokeWidth = value.toFloat()
                    if (value > 0 && it.strokeColor == Color.TRANSPARENT) it.strokeColor = Color.WHITE
                }
            },
        )
    }

    private fun showShapeAdjustPanel() {
        val layer = ensureShapeLayer()
        showPanel(
            "Shape Adjust",
            listOf(
                listOf(
                    PhotoToolAction("Transform") { showLayerTransformPanel() },
                    PhotoToolAction("Blend") { showBlendModePanel() },
                    PhotoToolAction("Gradient") { canvasView.updateSelectedShapeLayer { it.gradientEnabled = !it.gradientEnabled } },
                ),
                listOf(
                    PhotoToolAction("Shadow") { canvasView.updateSelectedShapeLayer { it.shadowEnabled = !it.shadowEnabled } },
                    PhotoToolAction("Front") { moveSelectedToFront() },
                ),
            ),
            backAction = PhotoToolAction("Back") { showShapesPanel() },
        )
        activeToolPanel.addView(
            sliderRow("Corner", 120, layer.cornerRadius.toInt().coerceIn(0, 120)) { value ->
                canvasView.updateSelectedShapeLayer { it.cornerRadius = value.toFloat() }
            },
        )
        activeToolPanel.addView(
            sliderRow("Opacity", 255, layer.opacity.coerceIn(0, 255)) { value ->
                canvasView.updateSelectedShapeLayer { it.opacity = value }
            },
        )
        activeToolPanel.addView(
            sliderRow("Scale", 300, (layer.scale * 100f).toInt().coerceIn(18, 300)) { value ->
                canvasView.updateSelectedShapeLayer { it.scale = value / 100f }
            },
        )
    }

    private fun showStickersPanel() {
        showPanel(
            "Stickers",
            listOf(
                listOf(
                    PhotoToolAction("SALE") { canvasView.addBrushStamp("SALE") },
                    PhotoToolAction("NEW") { canvasView.addBrushStamp("NEW") },
                    PhotoToolAction("LIKE") { canvasView.addBrushStamp("LIKE") },
                ),
                listOf(
                    PhotoToolAction("SUBSCRIBE") { canvasView.addBrushStamp("SUBSCRIBE") },
                    PhotoToolAction("TREND") { canvasView.addBrushStamp("TREND") },
                    PhotoToolAction("BADGE") { addShape(SHAPE_STAR) },
                ),
            ),
        )
    }

    private fun showDrawPanel() {
        canvasView.drawModeEnabled = true
        showPanel(
            "Draw Brush",
            listOf(
                listOf(
                    PhotoToolAction("Delete", danger = true) { deleteSelectedOrTopLayer() },
                    PhotoToolAction("Pen") { setBrush(BRUSH_PEN, eraser = false) },
                    PhotoToolAction("Marker") { setBrush(BRUSH_MARKER, eraser = false) },
                ),
                listOf(
                    PhotoToolAction("Neon") { setBrush(BRUSH_NEON, eraser = false) },
                    PhotoToolAction("Color") { showBrushColorMixer() },
                    PhotoToolAction("Eraser") { setBrush(BRUSH_PEN, eraser = true) },
                ),
                listOf(
                    PhotoToolAction("Undo Stroke") { canvasView.clearLastBrushStroke() },
                    PhotoToolAction("Stop Draw") { canvasView.drawModeEnabled = false },
                    PhotoToolAction("Layers") { canvasView.drawModeEnabled = false; showLayerPanel() },
                    PhotoToolAction("Export") { showExportPanel() },
                ),
            ),
        )
        activeToolPanel.addView(
            sliderRow("Size", 80, canvasView.brushSize.toInt().coerceIn(1, 80)) { value ->
                canvasView.brushSize = value.toFloat().coerceAtLeast(1f)
            },
        )
        activeToolPanel.addView(
            sliderRow("Opacity", 255, canvasView.brushOpacity.coerceIn(0, 255)) { value ->
                canvasView.brushOpacity = value
            },
        )
    }

    private fun showBackgroundPanel() {
        showPanel(
            "Background",
            listOf(
                listOf(
                    PhotoToolAction("Image") { pickImage() },
                    PhotoToolAction("Import BG") { pickBackgroundImage() },
                    PhotoToolAction("Rotate BG") { canvasView.rotateBackgroundBy(90f) },
                ),
                listOf(
                    PhotoToolAction("Delete", danger = true) { deleteSelectedOrTopLayer() },
                    PhotoToolAction("Color") { showCanvasBackgroundMixer() },
                    PhotoToolAction("Rot Left") { canvasView.rotateBackgroundBy(-90f) },
                ),
                listOf(
                    PhotoToolAction("Rot Right") { canvasView.rotateBackgroundBy(90f) },
                    PhotoToolAction("Transparent") { canvasView.transparentBackground = true },
                    PhotoToolAction("Solid") { canvasView.transparentBackground = false },
                ),
                listOf(
                    PhotoToolAction("Grid") { canvasView.gridVisible = !canvasView.gridVisible },
                    PhotoToolAction("Blue") { canvasView.canvasBackgroundColor = Color.rgb(232, 246, 255) },
                    PhotoToolAction("White") { canvasView.canvasBackgroundColor = Color.WHITE },
                ),
                listOf(
                    PhotoToolAction("Size") { showSizePanel() },
                    PhotoToolAction("Square") { applySquareTemplate() },
                    PhotoToolAction("Story") { applyStoryTemplate() },
                ),
                listOf(
                    PhotoToolAction("Export") { showExportPanel() },
                    PhotoToolAction("Reset", danger = true) { confirmReset() },
                ),
            ),
        )
        activeToolPanel.addView(
            sliderRow("BG Rotate", 360, (canvasView.backgroundRotationDeg + 180f).toInt().coerceIn(0, 360)) { value ->
                canvasView.backgroundRotationDeg = value - 180f
            },
        )
    }

    private fun showLayerPanel() {
        showPanel(
            "Layers",
            listOf(
                listOf(
                    PhotoToolAction("Delete", danger = true) { deleteSelectedOrTopLayer() },
                    PhotoToolAction("Hide") { canvasView.toggleSelectedVisibility() },
                    PhotoToolAction("Lock") { canvasView.toggleSelectedLock() },
                ),
                listOf(
                    PhotoToolAction("Copy") { canvasView.duplicateSelectedLayer() },
                    PhotoToolAction("Front") { moveSelectedToFront() },
                    PhotoToolAction("Back") { canvasView.moveSelectedBackward() },
                ),
                listOf(
                    PhotoToolAction("Transform") { showLayerTransformPanel() },
                    PhotoToolAction("Blend") { showBlendModePanel() },
                ),
            ),
            toolbarActions = listOf(
                PhotoToolAction("Delete", danger = true) { deleteSelectedOrTopLayer() },
                PhotoToolAction("Transform") { showLayerTransformPanel() },
                PhotoToolAction("Blend") { showBlendModePanel() },
                PhotoToolAction("Add Text") { addText() },
                PhotoToolAction("Add Image") { pickImage() },
                PhotoToolAction("BG Image") { pickBackgroundImage() },
                PhotoToolAction("Add Shape") { addShape(SHAPE_RECTANGLE) },
                PhotoToolAction("Draw") { showDrawPanel() },
            ),
        )
        setToolPanelHeight(TOOL_PANEL_TALL_HEIGHT_DP)
        canvasView.layerItems().forEach { item ->
            activeToolPanel.addView(
                layerPanelRow(item),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply {
                    marginStart = dp(4)
                    marginEnd = dp(4)
                    bottomMargin = dp(6)
                },
            )
        }
    }

    private fun showAiPanel() {
        showPanel(
            "Templates and AI",
            listOf(
                listOf(
                    PhotoToolAction("Poster") { applySmartPosterStyle() },
                    PhotoToolAction("Clean") { applyCleanTitleStyle() },
                    PhotoToolAction("Thumb Pop") { applyThumbnailPopStyle() },
                ),
                listOf(
                    PhotoToolAction("Birthday") { applyBirthdayStoryStyle() },
                    PhotoToolAction("Story") { applyStoryTemplate() },
                    PhotoToolAction("BG Remove") { applyAiBackgroundRemove() },
                ),
            ),
        )
    }

    private fun showCanvasPanel() {
        showBackgroundPanel()
    }

    private fun pickImage() {
        saveBeforePhotoPicker()
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, PICK_IMAGE_REQUEST)
    }

    private fun pickBackgroundImage() {
        saveBeforePhotoPicker()
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, PICK_BACKGROUND_IMAGE_REQUEST)
    }

    private fun addText() {
        val layer = canvasView.addTextLayer("New Text")
        showTextDialog(layer)
    }

    private fun editText() {
        val layer = ensureTextLayer()
        showTextDialog(layer)
    }

    private fun showTextDialog(layer: PhotoTextLayer) {
        val input = EditText(this).apply {
            setText(layer.text)
            selectAll()
            setSingleLine(false)
            minLines = 1
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        AlertDialog.Builder(this)
            .setTitle("Edit Text")
            .setView(input)
            .setPositiveButton("Apply") { _, _ ->
                canvasView.updateSelectedTextLayer { it.text = input.text.toString().ifBlank { "Text" } }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseTextColor() {
        ensureTextLayer()
        chooseColor("Text Color") { color ->
            canvasView.updateSelectedTextLayer { it.textColor = color }
        }
    }

    private fun chooseTextBackground() {
        ensureTextLayer()
        val labels = Array(colorOptions.size + 1) { index ->
            if (index == 0) "Transparent" else colorOptions[index - 1].first
        }
        val colors = IntArray(colorOptions.size + 1) { index ->
            if (index == 0) Color.TRANSPARENT else colorOptions[index - 1].second
        }
        AlertDialog.Builder(this)
            .setTitle("Text Background")
            .setItems(labels) { _, index ->
                canvasView.updateSelectedTextLayer { it.backgroundColor = colors[index] }
            }
            .show()
    }

    private fun chooseCanvasBackground() {
        chooseColor("Canvas Background") { color ->
            canvasView.canvasBackgroundColor = color
        }
    }

    private fun chooseStroke() {
        ensureTextLayer()
        val labels = Array(colorOptions.size + 1) { index ->
            if (index == 0) "Off" else colorOptions[index - 1].first
        }
        val colors = IntArray(colorOptions.size + 1) { index ->
            if (index == 0) Color.TRANSPARENT else colorOptions[index - 1].second
        }
        AlertDialog.Builder(this)
            .setTitle("Stroke")
            .setItems(labels) { _, index ->
                canvasView.updateSelectedTextLayer {
                    it.strokeColor = colors[index]
                    it.strokeWidth = if (index == 0) 0f else 7f
                }
            }
            .show()
    }

    private fun showTextColorMixer() {
        val layer = ensureTextLayer()
        showColorMixer(
            title = "Text Color",
            initialColor = layer.textColor,
            allowAlpha = false,
            onColorChanged = { color ->
                canvasView.updateSelectedTextLayer { it.textColor = color }
            },
            onBack = { showTextTools() },
            extraActions = listOf(
                PhotoToolAction("Text BG") { showTextBackgroundMixer() },
                PhotoToolAction("Stroke") { showStrokePanel() },
                PhotoToolAction("Gradient") { showTextGradientPanel() },
            ),
        )
    }

    private fun showTextBackgroundMixer() {
        val layer = ensureTextLayer()
        showColorMixer(
            title = "Text BG",
            initialColor = layer.backgroundColor,
            allowAlpha = true,
            onColorChanged = { color ->
                canvasView.updateSelectedTextLayer { it.backgroundColor = color }
            },
            onBack = { showTextTools() },
            extraActions = listOf(
                PhotoToolAction("Clear") {
                    canvasView.updateSelectedTextLayer { it.backgroundColor = Color.TRANSPARENT }
                },
                PhotoToolAction("Black") {
                    canvasView.updateSelectedTextLayer { it.backgroundColor = Color.argb(220, 0, 0, 0) }
                },
                PhotoToolAction("Red") {
                    canvasView.updateSelectedTextLayer { it.backgroundColor = Color.rgb(230, 76, 62) }
                },
                PhotoToolAction("Text Color") { showTextColorMixer() },
                PhotoToolAction("Stroke") { showStrokePanel() },
            ),
        )
    }

    private fun showCanvasBackgroundMixer() {
        showColorMixer(
            title = "Canvas BG",
            initialColor = canvasView.canvasBackgroundColor,
            allowAlpha = false,
            onColorChanged = { color ->
                canvasView.canvasBackgroundColor = color
            },
            onBack = { showCanvasPanel() },
            extraActions = listOf(
                PhotoToolAction("Blue") { canvasView.canvasBackgroundColor = Color.rgb(232, 246, 255) },
                PhotoToolAction("White") { canvasView.canvasBackgroundColor = Color.WHITE },
                PhotoToolAction("Ice") { canvasView.canvasBackgroundColor = Color.rgb(248, 252, 255) },
            ),
        )
    }

    private fun showImageTintMixer() {
        showColorMixer(
            title = "Image Tint",
            initialColor = Color.argb(
                (canvasView.imageTintStrength * 255f).toInt().coerceIn(0, 255),
                Color.red(canvasView.imageTintColor),
                Color.green(canvasView.imageTintColor),
                Color.blue(canvasView.imageTintColor),
            ),
            allowAlpha = true,
            onColorChanged = { color ->
                canvasView.setImageTint(
                    Color.rgb(Color.red(color), Color.green(color), Color.blue(color)),
                    Color.alpha(color) / 255f,
                )
            },
            onBack = { showImagePanel() },
            extraActions = listOf(
                PhotoToolAction("Clear") { canvasView.setImageTint(Color.TRANSPARENT, 0f) },
                PhotoToolAction("Warm") { canvasView.setImageTint(Color.rgb(255, 147, 72), 0.22f) },
                PhotoToolAction("Cool") { canvasView.setImageTint(Color.rgb(60, 170, 255), 0.20f) },
            ),
        )
    }

    private fun showStrokePanel() {
        val layer = ensureTextLayer()
        showPanel(
            "Stroke",
            listOf(
                listOf(
                    PhotoToolAction("Off") {
                        canvasView.updateSelectedTextLayer {
                            it.strokeColor = Color.TRANSPARENT
                            it.strokeWidth = 0f
                        }
                    },
                    PhotoToolAction("White") {
                        canvasView.updateSelectedTextLayer {
                            it.strokeColor = Color.WHITE
                            it.strokeWidth = it.strokeWidth.coerceAtLeast(5f)
                        }
                    },
                    PhotoToolAction("Black") {
                        canvasView.updateSelectedTextLayer {
                            it.strokeColor = Color.BLACK
                            it.strokeWidth = it.strokeWidth.coerceAtLeast(5f)
                        }
                    },
                ),
                listOf(
                    PhotoToolAction("Color") { showStrokeColorMixer() },
                    PhotoToolAction("Thin") { setStrokeWidth(3f) },
                    PhotoToolAction("Heavy") { setStrokeWidth(10f) },
                ),
            ),
            toolbarActions = listOf(
                PhotoToolAction("Color") { showStrokeColorMixer() },
                PhotoToolAction("Text Color") { showTextColorMixer() },
                PhotoToolAction("Text BG") { showTextBackgroundMixer() },
            ),
            backAction = PhotoToolAction("Back") { showTextTools() },
        )
        activeToolPanel.addView(
            sliderRow("Width", 24, layer.strokeWidth.toInt().coerceIn(0, 24)) { value ->
                canvasView.updateSelectedTextLayer {
                    it.strokeWidth = value.toFloat()
                    if (value > 0 && it.strokeColor == Color.TRANSPARENT) {
                        it.strokeColor = Color.WHITE
                    }
                }
            },
        )
    }

    private fun showStrokeColorMixer() {
        val layer = ensureTextLayer()
        showColorMixer(
            title = "Stroke Color",
            initialColor = if (layer.strokeColor == Color.TRANSPARENT) Color.WHITE else layer.strokeColor,
            allowAlpha = false,
            onColorChanged = { color ->
                canvasView.updateSelectedTextLayer {
                    it.strokeColor = color
                    it.strokeWidth = it.strokeWidth.coerceAtLeast(5f)
                }
            },
            onBack = { showStrokePanel() },
        )
    }

    private fun showTextGradientStartMixer() {
        val layer = ensureTextLayer()
        showColorMixer(
            title = "Gradient Start",
            initialColor = layer.gradientStartColor,
            allowAlpha = false,
            onColorChanged = { color ->
                canvasView.updateSelectedTextLayer {
                    it.gradientEnabled = true
                    it.gradientStartColor = color
                }
            },
            onBack = { showTextGradientPanel() },
        )
    }

    private fun showTextGradientEndMixer() {
        val layer = ensureTextLayer()
        showColorMixer(
            title = "Gradient End",
            initialColor = layer.gradientEndColor,
            allowAlpha = false,
            onColorChanged = { color ->
                canvasView.updateSelectedTextLayer {
                    it.gradientEnabled = true
                    it.gradientEndColor = color
                }
            },
            onBack = { showTextGradientPanel() },
        )
    }

    private fun showShapeFillMixer() {
        val layer = ensureShapeLayer()
        showColorMixer(
            title = "Shape Fill",
            initialColor = layer.fillColor,
            allowAlpha = true,
            onColorChanged = { color ->
                canvasView.updateSelectedShapeLayer { it.fillColor = color }
            },
            onBack = { showShapesPanel() },
            extraActions = listOf(
                PhotoToolAction("Gradient") { canvasView.updateSelectedShapeLayer { it.gradientEnabled = true } },
                PhotoToolAction("Flat") { canvasView.updateSelectedShapeLayer { it.gradientEnabled = false } },
                PhotoToolAction("Stroke") { showShapeStrokePanel() },
            ),
        )
    }

    private fun showShapeStrokeMixer() {
        val layer = ensureShapeLayer()
        showColorMixer(
            title = "Shape Stroke",
            initialColor = if (layer.strokeColor == Color.TRANSPARENT) Color.WHITE else layer.strokeColor,
            allowAlpha = false,
            onColorChanged = { color ->
                canvasView.updateSelectedShapeLayer {
                    it.strokeColor = color
                    it.strokeWidth = it.strokeWidth.coerceAtLeast(5f)
                }
            },
            onBack = { showShapeStrokePanel() },
        )
    }

    private fun showBrushColorMixer() {
        showColorMixer(
            title = "Brush Color",
            initialColor = canvasView.brushColor,
            allowAlpha = false,
            onColorChanged = { color ->
                canvasView.brushColor = color
                canvasView.brushEraser = false
            },
            onBack = { showDrawPanel() },
        )
    }

    private fun showExportPanel() {
        savePhotoProjectIfDirty()
        showPanel(
            "Export - ${canvasView.canvasSizeSummary()}",
            listOf(
                listOf(
                    PhotoToolAction("PNG") { exportCurrentImage("png", transparent = false) },
                    PhotoToolAction("JPG") { exportCurrentImage("jpg", transparent = false) },
                    PhotoToolAction("Trans PNG") { exportCurrentImage("png", transparent = true) },
                ),
                listOf(
                    PhotoToolAction("PNG 2K") { exportLongEdgeImage("png", 2048, transparent = false) },
                    PhotoToolAction("JPG 2K") { exportLongEdgeImage("jpg", 2048, transparent = false) },
                    PhotoToolAction("Trans 2K") { exportLongEdgeImage("png", 2048, transparent = true) },
                ),
                listOf(
                    PhotoToolAction("Size") { showSizePanel() },
                    PhotoToolAction("BG Clear") { canvasView.transparentBackground = true },
                    PhotoToolAction("Back") { collapseToolPanel() },
                ),
                listOf(
                    PhotoToolAction("Save") { forceSavePhotoProject() },
                    PhotoToolAction("Gallery") { exportCurrentImage("png", transparent = false) },
                ),
            ),
        )
    }

    private fun showSettingsPanel() {
        showPanel(
            "Settings",
            listOf(
                listOf(
                    PhotoToolAction("Grid") { canvasView.gridVisible = !canvasView.gridVisible },
                    PhotoToolAction("Reset Zoom") { canvasView.resetViewport() },
                    PhotoToolAction("Auto Save") { forceSavePhotoProject() },
                ),
                listOf(
                    PhotoToolAction("Blue BG") { canvasView.canvasBackgroundColor = Color.rgb(232, 246, 255) },
                    PhotoToolAction("Light BG") { canvasView.canvasBackgroundColor = Color.WHITE },
                    PhotoToolAction("Transparent") { canvasView.transparentBackground = !canvasView.transparentBackground },
                ),
                listOf(
                    PhotoToolAction("Reset", danger = true) { confirmReset() },
                    PhotoToolAction("Layers") { showLayerPanel() },
                    PhotoToolAction("Export") { showExportPanel() },
                ),
            ),
        )
    }

    private fun showColorMixer(
        title: String,
        initialColor: Int,
        allowAlpha: Boolean,
        onColorChanged: (Int) -> Unit,
        onBack: () -> Unit,
        extraActions: List<PhotoToolAction> = emptyList(),
    ) {
        val startColor = when {
            initialColor == Color.TRANSPARENT && allowAlpha -> Color.argb(220, 20, 54, 84)
            initialColor == Color.TRANSPARENT -> Color.WHITE
            else -> initialColor
        }
        var red = Color.red(startColor)
        var green = Color.green(startColor)
        var blue = Color.blue(startColor)
        var alpha = if (allowAlpha) Color.alpha(startColor) else 255
        lateinit var preview: TextView

        fun currentColor(): Int = Color.argb(alpha, red, green, blue)

        fun updatePreview(color: Int) {
            preview.setBackgroundColor(color)
            preview.setTextColor(
                if (Color.alpha(color) < 80 || Color.red(color) + Color.green(color) + Color.blue(color) > 420) {
                    Color.BLACK
                } else {
                    Color.WHITE
                },
            )
        }

        fun applyColor() {
            val color = currentColor()
            updatePreview(color)
            onColorChanged(color)
            refreshStatus()
        }

        fun applyPresetColor(color: Int) {
            red = Color.red(color)
            green = Color.green(color)
            blue = Color.blue(color)
            alpha = if (allowAlpha) Color.alpha(color) else 255
            applyColor()
        }

        val presetActions = buildColorPresetActions(allowAlpha) { color -> applyPresetColor(color) }

        showPanel(
            title,
            emptyList(),
            toolbarActions = extraActions + presetActions,
            backAction = PhotoToolAction("Back") { onBack() },
        )
        preview = TextView(this).apply {
            text = "Live Color"
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = getDrawable(R.drawable.photo_toolbar_item_background)
        }
        activeToolPanel.addView(
            preview,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
                bottomMargin = dp(6)
            },
        )
        activeToolPanel.addView(sliderRow("Red", 255, red) { value ->
            red = value
            applyColor()
        })
        activeToolPanel.addView(sliderRow("Green", 255, green) { value ->
            green = value
            applyColor()
        })
        activeToolPanel.addView(sliderRow("Blue", 255, blue) { value ->
            blue = value
            applyColor()
        })
        if (allowAlpha) {
            activeToolPanel.addView(sliderRow("Alpha", 255, alpha) { value ->
                alpha = value
                applyColor()
            })
        }
        setToolPanelHeight(TOOL_PANEL_TALL_HEIGHT_DP)
        updatePreview(currentColor())
    }

    private fun buildColorPresetActions(
        allowAlpha: Boolean,
        onColorPicked: (Int) -> Unit,
    ): List<PhotoToolAction> {
        val presets = mutableListOf<PhotoToolAction>()
        if (allowAlpha) {
            presets += PhotoToolAction("Clear") { onColorPicked(Color.TRANSPARENT) }
        }
        presets += PhotoToolAction("White") { onColorPicked(Color.WHITE) }
        presets += PhotoToolAction("Black") { onColorPicked(Color.BLACK) }
        presets += PhotoToolAction("Gold") { onColorPicked(Color.rgb(255, 214, 64)) }
        presets += PhotoToolAction("Red") { onColorPicked(Color.rgb(239, 83, 80)) }
        presets += PhotoToolAction("Blue") { onColorPicked(Color.rgb(66, 165, 245)) }
        presets += PhotoToolAction("Green") { onColorPicked(Color.rgb(102, 187, 106)) }
        return presets
    }

    private fun sliderRow(
        label: String,
        maxValue: Int,
        progressValue: Int,
        onChanged: (Int) -> Unit,
    ): View {
        val valueText = TextView(this).apply {
            text = progressValue.toString()
            setTextColor(Color.rgb(45, 76, 102))
            textSize = 11f
            gravity = Gravity.CENTER
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(
                TextView(this@PhotoEditorActivity).apply {
                    text = label
                    setTextColor(Color.rgb(20, 54, 84))
                    textSize = 12f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                },
                LinearLayout.LayoutParams(dp(78), dp(46)),
            )
            addView(
                SeekBar(this@PhotoEditorActivity).apply {
                    max = maxValue
                    progress = progressValue.coerceIn(0, maxValue)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                            valueText.text = progress.toString()
                            onChanged(progress)
                        }

                        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                    })
                },
                LinearLayout.LayoutParams(0, dp(46), 1f),
            )
            addView(valueText, LinearLayout.LayoutParams(dp(44), dp(46)))
        }
    }

    private fun showAiTools() {
        val labels = arrayOf(
            "AI Smart Poster",
            "AI Clean Title",
            "AI Thumbnail Pop",
            "AI Birthday Story",
        )
        AlertDialog.Builder(this)
            .setTitle("AI Photo Tools")
            .setItems(labels) { _, index ->
                when (index) {
                    0 -> applySmartPosterStyle()
                    1 -> applyCleanTitleStyle()
                    2 -> applyThumbnailPopStyle()
                    3 -> applyBirthdayStoryStyle()
                }
            }
            .show()
    }

    private fun showTextPresets() {
        val labels = arrayOf(
            "Lower Third",
            "Breaking News",
            "Share Tag",
            "Crown Caption",
        )
        AlertDialog.Builder(this)
            .setTitle("Text Presets")
            .setItems(labels) { _, index ->
                when (index) {
                    0 -> applyPresetText("Storyline", Color.WHITE, Color.rgb(92, 32, 38), 470f, 850f, 1.08f)
                    1 -> applyPresetText("BREAKING NEWS", Color.WHITE, Color.rgb(229, 45, 62), 540f, 790f, 0.88f)
                    2 -> applyPresetText("SHARE", Color.WHITE, Color.rgb(30, 190, 120), 540f, 720f, 0.94f)
                    3 -> applyPresetText("HAPPY BIRTHDAY", Color.WHITE, Color.rgb(116, 38, 47), 540f, 820f, 0.92f)
                }
            }
            .show()
    }

    private fun applySmartPosterStyle() {
        val layer = ensureTextLayer("Storyline")
        canvasView.setCanvasSize(1080, 1080, "Square")
        canvasView.canvasBackgroundColor = Color.rgb(232, 246, 255)
        canvasView.updateSelectedTextLayer {
            it.text = layer.text.ifBlank { "Storyline" }
            it.textColor = Color.WHITE
            it.backgroundColor = Color.rgb(230, 76, 62)
            it.strokeColor = Color.TRANSPARENT
            it.strokeWidth = 0f
            it.shadowEnabled = true
            it.bold = true
            it.italic = false
            it.scale = it.scale.coerceAtLeast(1.05f)
            it.y = it.y.coerceAtMost(840f)
        }
    }

    private fun applyImageLook(
        brightness: Float,
        contrast: Float,
        saturation: Float,
        tintColor: Int,
        tintStrength: Float,
        temperature: Float = 0f,
    ): Boolean {
        if (canvasView.selectedImageLayer() != null) {
            canvasView.updateSelectedImageLayer {
                it.brightness = brightness
                it.contrast = contrast
                it.saturation = saturation
                it.temperature = temperature
                it.blur = 0f
            }
            return true
        }
        canvasView.imageBrightness = brightness
        canvasView.imageContrast = contrast
        canvasView.imageSaturation = saturation
        canvasView.imageTemperature = temperature
        canvasView.setImageTint(tintColor, tintStrength)
        return false
    }

    private fun applySoftLightLook() {
        if (!applyImageLook(18f, 1.06f, 1.18f, Color.rgb(255, 224, 164), 0.18f, 22f)) {
            canvasView.imageVignette = 0.18f
        }
    }

    private fun applyGoldenHourLook() {
        if (!applyImageLook(12f, 1.12f, 1.28f, Color.rgb(255, 184, 72), 0.24f, 34f)) {
            canvasView.imageVignette = 0.24f
        }
    }

    private fun applyCinematicLook() {
        if (!applyImageLook(2f, 1.24f, 0.92f, Color.rgb(34, 102, 126), 0.16f, -18f)) {
            canvasView.imageVignette = 0.32f
        }
    }

    private fun applyMatteLook() {
        if (!applyImageLook(20f, 0.82f, 0.78f, Color.rgb(255, 236, 205), 0.16f, 8f)) {
            canvasView.imageVignette = 0.12f
        }
    }

    private fun applyNoirLook() {
        if (!applyImageLook(0f, 1.34f, 0f, Color.rgb(30, 40, 55), 0.16f)) {
            canvasView.imageVignette = 0.38f
        }
    }

    private fun applyCurvePreset(shadows: Float, midtones: Float, highlights: Float) {
        canvasView.setImageCurve(shadows, midtones, highlights)
        showImageCurvePanel()
    }

    private fun clearImageLook() {
        canvasView.imageBrightness = 0f
        canvasView.imageContrast = 1f
        canvasView.imageSaturation = 1f
        canvasView.imageTemperature = 0f
        canvasView.imageBlur = 0f
        canvasView.imageVignette = 0f
        canvasView.imageFilterIntensity = 1f
        canvasView.setImageTint(Color.TRANSPARENT, 0f)
        canvasView.imageMaskMode = PhotoCanvasView.IMAGE_MASK_NONE
        canvasView.setImageCutout(PhotoCanvasView.IMAGE_CUTOUT_OFF)
        canvasView.setImageCurve(0f, 0f, 0f)
    }

    private fun setStrokeWidth(width: Float) {
        ensureTextLayer()
        canvasView.updateSelectedTextLayer {
            it.strokeWidth = width
            if (width > 0f && it.strokeColor == Color.TRANSPARENT) {
                it.strokeColor = Color.WHITE
            }
        }
    }

    private fun applyCleanTitleStyle() {
        ensureTextLayer("Storyline")
        canvasView.setCanvasSize(1080, 1080, "Square")
        canvasView.canvasBackgroundColor = Color.rgb(248, 252, 255)
        canvasView.updateSelectedTextLayer {
            it.textColor = Color.WHITE
            it.backgroundColor = Color.TRANSPARENT
            it.strokeColor = Color.TRANSPARENT
            it.strokeWidth = 0f
            it.shadowEnabled = true
            it.bold = true
            it.italic = false
            it.scale = it.scale.coerceAtLeast(1.18f)
        }
    }

    private fun applyThumbnailPopStyle() {
        ensureTextLayer("Storyline")
        canvasView.setCanvasSize(1280, 720, "YouTube Thumbnail")
        canvasView.updateSelectedTextLayer {
            it.textColor = Color.rgb(255, 245, 120)
            it.backgroundColor = Color.rgb(20, 20, 20)
            it.strokeColor = Color.WHITE
            it.strokeWidth = 5f
            it.shadowEnabled = true
            it.bold = true
            it.scale = it.scale.coerceAtLeast(1.25f)
        }
    }

    private fun applyBirthdayStoryStyle() {
        canvasView.setCanvasSize(1080, 1920, "Instagram Story")
        canvasView.canvasBackgroundColor = Color.rgb(232, 246, 255)
        applyPresetText("HAPPY BIRTHDAY", Color.WHITE, Color.rgb(116, 38, 47), 540f, 820f, 0.92f)
    }

    private fun applyPresetText(
        text: String,
        textColor: Int,
        backgroundColor: Int,
        x: Float,
        y: Float,
        scale: Float,
    ) {
        val layer = canvasView.selectedTextLayer() ?: canvasView.addTextLayer(text)
        canvasView.updateSelectedTextLayer {
            it.text = text
            it.textColor = textColor
            it.backgroundColor = backgroundColor
            it.strokeColor = Color.TRANSPARENT
            it.strokeWidth = 0f
            it.shadowEnabled = true
            it.bold = true
            it.italic = false
            it.x = x
            it.y = y
            it.scale = scale
            it.rotationDeg = layer.rotationDeg
        }
    }

    private fun setTextFont(fontFamily: String) {
        ensureTextLayer()
        canvasView.updateSelectedTextLayer { it.fontFamily = fontFamily }
    }

    private fun toggleUnderline() {
        ensureTextLayer()
        canvasView.updateSelectedTextLayer { it.underline = !it.underline }
    }

    private fun setTextAlign(alignment: Int) {
        ensureTextLayer()
        canvasView.updateSelectedTextLayer { it.alignment = alignment }
    }

    private fun setTextShadow(blur: Float, offsetX: Float, offsetY: Float, opacity: Int) {
        ensureTextLayer()
        canvasView.updateSelectedTextLayer {
            it.shadowEnabled = true
            it.shadowBlur = blur
            it.shadowOffsetX = offsetX
            it.shadowOffsetY = offsetY
            it.shadowOpacity = opacity
        }
    }

    private fun setTextGradient(startColor: Int, endColor: Int) {
        ensureTextLayer()
        canvasView.updateSelectedTextLayer {
            it.gradientEnabled = true
            it.gradientStartColor = startColor
            it.gradientEndColor = endColor
        }
    }

    private fun addShape(shapeType: Int) {
        canvasView.addShapeLayer(shapeType)
        showShapeAdjustPanel()
    }

    private fun ensureShapeLayer(): PhotoShapeLayer =
        canvasView.selectedShapeLayer() ?: canvasView.addShapeLayer(SHAPE_RECTANGLE)

    private fun setBrush(type: Int, eraser: Boolean) {
        canvasView.brushType = type
        canvasView.brushEraser = eraser
        canvasView.drawModeEnabled = true
    }

    private fun applySquareTemplate() {
        canvasView.setCanvasSize(1080, 1080, "Square")
        canvasView.transparentBackground = false
        canvasView.gridVisible = true
        canvasView.canvasBackgroundColor = Color.rgb(232, 246, 255)
        canvasView.imageMaskMode = PhotoCanvasView.IMAGE_MASK_NONE
        refreshStatus()
        Toast.makeText(this, "Square canvas ready", Toast.LENGTH_SHORT).show()
    }

    private fun applyStoryTemplate() {
        canvasView.setCanvasSize(1080, 1920, "Instagram Story")
        canvasView.transparentBackground = false
        canvasView.canvasBackgroundColor = Color.rgb(232, 246, 255)
        val text = ensureTextLayer("STORY")
        canvasView.updateSelectedTextLayer {
            it.text = text.text.ifBlank { "STORY" }
            it.x = 540f
            it.y = 820f
            it.scale = it.scale.coerceAtLeast(1.15f)
            it.backgroundColor = Color.rgb(116, 38, 47)
            it.textColor = Color.WHITE
            it.shadowEnabled = true
        }
    }

    private fun applyAiBackgroundRemove() {
        if (!canvasView.hasBaseImage()) {
            Toast.makeText(this, "Import image first", Toast.LENGTH_SHORT).show()
            return
        }
        canvasView.transparentBackground = true
        canvasView.imageMaskMode = PhotoCanvasView.IMAGE_MASK_NONE
        canvasView.setImageCutout(PhotoCanvasView.IMAGE_CUTOUT_AUTO, threshold = 46f, feather = 34f)
        canvasView.selectBaseImage()
        refreshStatus()
        Toast.makeText(this, "Auto cutout applied", Toast.LENGTH_SHORT).show()
    }

    private fun applyGreenScreenCutout() {
        if (!canvasView.hasBaseImage()) {
            Toast.makeText(this, "Import image first", Toast.LENGTH_SHORT).show()
            return
        }
        canvasView.transparentBackground = true
        canvasView.imageMaskMode = PhotoCanvasView.IMAGE_MASK_NONE
        canvasView.setImageCutout(PhotoCanvasView.IMAGE_CUTOUT_GREEN, threshold = 48f, feather = 28f)
        canvasView.selectBaseImage()
        refreshStatus()
        Toast.makeText(this, "Green cutout applied", Toast.LENGTH_SHORT).show()
    }

    private fun applyLightCutout() {
        if (!canvasView.hasBaseImage()) {
            Toast.makeText(this, "Import image first", Toast.LENGTH_SHORT).show()
            return
        }
        canvasView.transparentBackground = true
        canvasView.imageMaskMode = PhotoCanvasView.IMAGE_MASK_NONE
        canvasView.setImageCutout(PhotoCanvasView.IMAGE_CUTOUT_LIGHT, threshold = 36f, feather = 32f)
        canvasView.selectBaseImage()
        refreshStatus()
        Toast.makeText(this, "White cutout applied", Toast.LENGTH_SHORT).show()
    }

    private fun resetCutout() {
        canvasView.setImageCutout(PhotoCanvasView.IMAGE_CUTOUT_OFF)
        canvasView.transparentBackground = false
        refreshStatus()
        Toast.makeText(this, "Cutout reset", Toast.LENGTH_SHORT).show()
    }

    private fun handleUndo() {
        if (!canvasView.undo()) {
            Toast.makeText(this, "Nothing to undo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleRedo() {
        if (!canvasView.redo()) {
            Toast.makeText(this, "Nothing to redo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun forceSavePhotoProject() {
        saveProjectName()
        dirtySinceOpen = true
        savePhotoProjectIfDirty()
        Toast.makeText(this, "Photo project saved", Toast.LENGTH_SHORT).show()
    }

    private fun saveProjectName() {
        if (!::projectNameEdit.isInitialized) return
        getPreferences(MODE_PRIVATE)
            .edit()
            .putString(PROJECT_NAME_PREF, projectNameEdit.text.toString().ifBlank { "Untitled Photo" })
            .apply()
    }

    private fun layerItemLabel(item: PhotoLayerItem): String {
        val type = when (item.type) {
            PHOTO_LAYER_TEXT -> "Text"
            PHOTO_LAYER_SHAPE -> "Shape"
            PHOTO_LAYER_BRUSH -> "Brush"
            PHOTO_LAYER_IMAGE -> "Image"
            PHOTO_LAYER_BACKGROUND -> "BG"
            else -> "Layer"
        }
        val visible = if (item.visible) "On" else "Hidden"
        val locked = if (item.locked) "Locked" else "Free"
        return "$type  ${item.title}  $visible  $locked"
    }

    private fun layerPanelRow(item: PhotoLayerItem): View {
        val selected = canvasView.selectedLayerItem()?.let { it.type == item.type && it.id == item.id } == true
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = roundSolid(
                if (selected) Color.rgb(17, 42, 58) else Color.rgb(8, 13, 19),
                dp(14).toFloat(),
                if (selected) Color.rgb(63, 177, 232) else Color.rgb(34, 48, 62),
            )
            addView(
                panelButton(layerItemLabel(item)) {
                    canvasView.selectLayerFromPanel(item.type, item.id)
                    refreshStatus()
                    showLayerPanel()
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    marginEnd = dp(4)
                },
            )
            if (item.type != PHOTO_LAYER_BACKGROUND) {
                addLayerRowButton(if (item.visible) "Hide" else "Show") {
                    canvasView.toggleLayerVisibility(item.type, item.id)
                    showLayerPanel()
                }
            }
            if (item.type != PHOTO_LAYER_BACKGROUND && item.type != PHOTO_LAYER_BRUSH) {
                addLayerRowButton(if (item.locked) "Unlock" else "Lock") {
                    canvasView.toggleLayerLock(item.type, item.id)
                    showLayerPanel()
                }
            }
            if (item.type != PHOTO_LAYER_BACKGROUND) {
                addLayerRowButton("Up") {
                    if (!canvasView.moveLayerForward(item.type, item.id)) {
                        Toast.makeText(this@PhotoEditorActivity, "Layer already above", Toast.LENGTH_SHORT).show()
                    }
                    showLayerPanel()
                }
                addLayerRowButton("Down") {
                    if (!canvasView.moveLayerBackward(item.type, item.id)) {
                        Toast.makeText(this@PhotoEditorActivity, "Layer already below", Toast.LENGTH_SHORT).show()
                    }
                    showLayerPanel()
                }
            }
            addLayerRowButton("Del", danger = true) {
                canvasView.deleteLayer(item.type, item.id)
                refreshStatus()
                showLayerPanel()
            }
        }
    }

    private fun LinearLayout.addLayerRowButton(
        label: String,
        danger: Boolean = false,
        onClick: () -> Unit,
    ) {
        addView(
            panelButton(label, danger, onClick),
            LinearLayout.LayoutParams(dp(54), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                marginStart = dp(2)
            },
        )
    }

    private fun signedValue(value: Int): String =
        if (value > 0) "+$value" else value.toString()

    private fun chooseColor(title: String, onPicked: (Int) -> Unit) {
        val labels = Array(colorOptions.size) { index -> colorOptions[index].first }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels) { _, index ->
                onPicked(colorOptions[index].second)
            }
            .show()
    }

    private fun toggleShadow() {
        ensureTextLayer()
        canvasView.updateSelectedTextLayer { it.shadowEnabled = !it.shadowEnabled }
    }

    private fun toggleBold() {
        ensureTextLayer()
        canvasView.updateSelectedTextLayer { it.bold = !it.bold }
    }

    private fun toggleItalic() {
        ensureTextLayer()
        canvasView.updateSelectedTextLayer { it.italic = !it.italic }
    }

    private fun scaleSelected(multiplier: Float) {
        val textUpdated = canvasView.updateSelectedTextLayer {
            it.scale = (it.scale * multiplier).coerceIn(0.18f, 8f)
        }
        if (!textUpdated) {
            val shapeUpdated = canvasView.updateSelectedShapeLayer {
                it.scale = (it.scale * multiplier).coerceIn(0.18f, 8f)
            }
            if (!shapeUpdated) {
                canvasView.updateSelectedImageLayer {
                    it.scale = (it.scale * multiplier).coerceIn(0.18f, 8f)
                }
            }
        }
    }

    private fun moveSelectedToFront() {
        if (!canvasView.moveSelectedToFront()) {
            Toast.makeText(this, "Layer is already front", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteSelectedOrTopLayer() {
        when (val selectedType = canvasView.selectedLayerItem()?.type) {
            PHOTO_LAYER_BACKGROUND -> {
                deleteBackgroundImage()
                return
            }
            PHOTO_LAYER_IMAGE,
            PHOTO_LAYER_TEXT,
            PHOTO_LAYER_SHAPE,
            PHOTO_LAYER_BRUSH -> {
                deleteSelectedLayerInternal(selectedType)
                return
            }
        }
        when {
            canvasView.selectTopEditableLayer() -> deleteSelectedLayerInternal(canvasView.selectedLayerItem()?.type)
            canvasView.hasBaseImage() -> deleteBackgroundImage()
            else -> Toast.makeText(this, "Select a layer first", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteSelectedLayerInternal(selectedType: Int?) {
        if (!canvasView.deleteSelectedLayer()) {
            Toast.makeText(this, "Select a layer first", Toast.LENGTH_SHORT).show()
            return
        }
        refreshStatus()
        val nextItem = canvasView.selectedLayerItem()
        switchStudioTabForSelection(nextItem)
        showToolsForSelection(nextItem)
        val message = when (selectedType) {
            PHOTO_LAYER_IMAGE -> "Image layer deleted"
            PHOTO_LAYER_TEXT -> "Text layer deleted"
            PHOTO_LAYER_SHAPE -> "Shape layer deleted"
            PHOTO_LAYER_BRUSH -> "Brush layer deleted"
            else -> "Layer deleted"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun deleteBackgroundImage() {
        if (!canvasView.hasBaseImage()) {
            Toast.makeText(this, "No background image", Toast.LENGTH_SHORT).show()
            return
        }
        sourceImageUri = null
        canvasView.setBaseImage(null)
        refreshStatus()
        showBackgroundPanel()
        Toast.makeText(this, "Background image cleared", Toast.LENGTH_SHORT).show()
    }

    private fun ensureTextLayer(defaultText: String = "New Text"): PhotoTextLayer =
        canvasView.selectedTextLayer() ?: canvasView.addTextLayer(defaultText)

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("Reset Photo Design")
            .setMessage("Clear image and text layers?")
            .setPositiveButton("Reset") { _, _ -> canvasView.resetDesign() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportImage() {
        exportCurrentImage("png", transparent = false)
    }

    private fun exportCurrentImage(format: String, transparent: Boolean) {
        exportImage(
            format = format,
            outputWidth = canvasView.canvasOutputWidth,
            outputHeight = canvasView.canvasOutputHeight,
            transparent = transparent,
        )
    }

    private fun exportLongEdgeImage(format: String, longEdge: Int, transparent: Boolean) {
        val currentWidth = canvasView.canvasOutputWidth
        val currentHeight = canvasView.canvasOutputHeight
        val scale = longEdge.toFloat() / max(currentWidth, currentHeight).toFloat()
        val outputWidth = (currentWidth * scale).toInt().coerceAtLeast(MIN_CUSTOM_CANVAS_SIDE)
        val outputHeight = (currentHeight * scale).toInt().coerceAtLeast(MIN_CUSTOM_CANVAS_SIDE)
        exportImage(format, outputWidth, outputHeight, transparent)
    }

    private fun exportImage(format: String, outputWidth: Int, outputHeight: Int, transparent: Boolean) {
        savePhotoProjectIfDirty()
        val normalizedFormat = format.lowercase(Locale.US)
        val width = outputWidth.coerceIn(MIN_CUSTOM_CANVAS_SIDE, MAX_CUSTOM_CANVAS_SIDE)
        val height = outputHeight.coerceIn(MIN_CUSTOM_CANVAS_SIDE, MAX_CUSTOM_CANVAS_SIDE)
        val bitmap = canvasView.renderToBitmap(width, height, forceTransparentBackground = transparent)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val extension = if (normalizedFormat == "jpg") "jpg" else "png"
        val name = "storyline_photo_${width}x${height}_${stamp}.$extension"
        val uri = saveBitmapToGallery(
            bitmap = bitmap,
            displayName = name,
            mimeType = if (normalizedFormat == "jpg") "image/jpeg" else "image/png",
            compressFormat = if (normalizedFormat == "jpg") Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG,
            quality = if (normalizedFormat == "jpg") 94 else 100,
        )
        bitmap.recycle()
        if (uri == null) {
            Toast.makeText(this, "Export failed", Toast.LENGTH_SHORT).show()
        } else {
            val label = if (transparent) "transparent PNG" else extension.uppercase(Locale.US)
            Toast.makeText(this, "Saved $label ${width}x${height}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveBitmapToGallery(
        bitmap: Bitmap,
        displayName: String,
        mimeType: String,
        compressFormat: Bitmap.CompressFormat,
        quality: Int,
    ): Uri? {
        val resolver = contentResolver
        val collection =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Storyline Photo")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { stream ->
                if (!bitmap.compress(compressFormat, quality, stream)) {
                    return null
                }
            } ?: return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            uri
        } catch (_: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    private fun saveBeforePhotoPicker() {
        setPendingPhotoPicker(true)
        dirtySinceOpen = true
        savePhotoProjectIfDirty()
    }

    private fun setPendingPhotoPicker(pending: Boolean) {
        getPreferences(MODE_PRIVATE)
            .edit()
            .putBoolean(PENDING_PHOTO_PICK_PREF, pending)
            .apply()
    }

    private fun restoreRecentPhoto(showToast: Boolean = true, projectFile: File? = null): Boolean {
        val state = PhotoProjectStore.load(this, projectFile) ?: return false
        sourceImageUri = state.imageUri
        var bitmap: Bitmap? = null
        val imageUri = state.imageUri
        if (!imageUri.isNullOrBlank()) {
            val parsedUri = runCatching { Uri.parse(imageUri) }.getOrNull()
            if (parsedUri != null) {
                bitmap = decodeBitmap(parsedUri)
            }
        }
        val imageLayers = state.imageLayers.map { layer ->
            val layerBitmap = layer.uri
                ?.let { runCatching { Uri.parse(it) }.getOrNull() }
                ?.let { decodeBitmap(it) }
            layer.copy(bitmap = layerBitmap)
        }
        canvasView.restoreDesign(
            backgroundColor = state.canvasBackgroundColor,
            backgroundRotationDeg = state.backgroundRotationDeg,
            bitmap = bitmap,
            layers = state.layers,
            imageBrightness = state.imageBrightness,
            imageContrast = state.imageContrast,
            imageSaturation = state.imageSaturation,
            imageTintColor = state.imageTintColor,
            imageTintStrength = state.imageTintStrength,
            imageMaskMode = state.imageMaskMode,
            imageTemperature = state.imageTemperature,
            imageBlur = state.imageBlur,
            imageVignette = state.imageVignette,
            imageFilterIntensity = state.imageFilterIntensity,
            imageCutoutMode = state.imageCutoutMode,
            imageCutoutThreshold = state.imageCutoutThreshold,
            imageCutoutFeather = state.imageCutoutFeather,
            imageCurveShadows = state.imageCurveShadows,
            imageCurveMidtones = state.imageCurveMidtones,
            imageCurveHighlights = state.imageCurveHighlights,
            transparentBackground = state.transparentBackground,
            imageLayers = imageLayers,
            shapeLayers = state.shapeLayers,
            brushStrokes = state.brushStrokes,
            canvasOutputWidth = state.canvasOutputWidth,
            canvasOutputHeight = state.canvasOutputHeight,
            canvasSizeLabel = state.canvasSizeLabel,
        )
        if (showToast) {
            Toast.makeText(this, "Recent photo work restored", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private fun schedulePhotoAutosave() {
        autosaveHandler.removeCallbacks(autosaveRunnable)
        autosaveHandler.postDelayed(autosaveRunnable, AUTOSAVE_DELAY_MS)
    }

    private fun savePhotoProjectIfDirty() {
        autosaveHandler.removeCallbacks(autosaveRunnable)
        if (!dirtySinceOpen || !::canvasView.isInitialized) return
        runCatching {
            PhotoProjectStore.save(this, sourceImageUri, canvasView)
            dirtySinceOpen = false
        }
    }

    private fun decodeBitmap(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (max(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > MAX_IMPORT_SIDE) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
    }

    private fun refreshStatus() {
        if (!::statusText.isInitialized) return
        val item = canvasView.selectedLayerItem()
        val size = canvasView.canvasSizeSummary()
        statusText.text =
            if (item == null) {
                "$size  - canvas locked, select object to move"
            } else {
                "Selected: ${item.title}  $size  ${if (item.visible) "visible" else "hidden"}  ${if (item.locked) "locked" else "editable"}"
            }
        refreshFloatingQuickBar()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class PhotoToolAction(
        val label: String,
        val danger: Boolean = false,
        val onClick: () -> Unit,
    )

    private data class PhotoFilterPreset(
        val label: String,
        val startColor: Int,
        val endColor: Int,
        val onApply: () -> Unit,
    )

    private fun android.content.ContentResolver.takePersistableUriPermissionSafe(data: Intent, uri: Uri) {
        val flags = data.flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (flags == 0) return
        runCatching { takePersistableUriPermission(uri, flags) }
    }
}
