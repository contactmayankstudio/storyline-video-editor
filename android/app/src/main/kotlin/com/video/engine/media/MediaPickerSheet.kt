package com.video.engine.media

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.video.engine.pro.model.TrackType
import java.util.concurrent.Executors

enum class MediaType {
    VIDEO,
    PHOTO,
    AUDIO
}

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0L,
    val mediaType: MediaType,
    val artist: String = ""
)

private fun dp(context: Context, v: Float): Float = v * context.resources.displayMetrics.density
private fun dpInt(context: Context, v: Float): Int = (v * context.resources.displayMetrics.density).toInt()

object MediaPickerSheet {

    private val thumbnailExecutor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "MediaPickerThumbnail").apply {
            priority = Thread.MIN_PRIORITY
        }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val thumbnailCache = object : LruCache<Long, Bitmap>(20 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
    }

    fun show(
        activity: Activity,
        initialTrackType: TrackType = TrackType.VIDEO,
        onMediaSelected: (uris: List<Uri>, trackType: TrackType) -> Unit,
        onBrowseSystemPicker: (trackType: TrackType) -> Unit
    ) {
        val dialog = BottomSheetDialog(activity)
        var currentTab = when (initialTrackType) {
            TrackType.AUDIO -> MediaType.AUDIO
            TrackType.OVERLAY, TrackType.LAYER -> MediaType.VIDEO
            else -> MediaType.VIDEO
        }
        var targetTrackType = initialTrackType

        val selectedItems = mutableSetOf<MediaItem>()
        val mediaItems = mutableListOf<MediaItem>()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#11151B"))
                cornerRadii = floatArrayOf(
                    dp(activity, 24f), dp(activity, 24f),
                    dp(activity, 24f), dp(activity, 24f),
                    0f, 0f, 0f, 0f
                )
                setStroke(dpInt(activity, 1f), Color.parseColor("#1B222C"))
            }
            setPadding(0, dpInt(activity, 12f), 0, dpInt(activity, 16f))
        }

        // Drag handle
        val handle = LinearLayout(activity).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dpInt(activity, 8f))
        }
        handle.addView(TextView(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(activity, 999f)
                setColor(Color.parseColor("#3A4452"))
            }
            layoutParams = LinearLayout.LayoutParams(dpInt(activity, 36f), dpInt(activity, 4f)).also {
                it.gravity = Gravity.CENTER
            }
        })
        root.addView(handle)

        // Header
        val header = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpInt(activity, 44f)
            ).also {
                it.marginStart = dpInt(activity, 16f)
                it.marginEnd = dpInt(activity, 16f)
            }
        }

        val titleView = TextView(activity).apply {
            text = "Media"
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.START
            )
        }
        header.addView(titleView)

        val closeBtn = ImageView(activity).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.parseColor("#8A99AD"))
            setPadding(dpInt(activity, 8f), dpInt(activity, 8f), dpInt(activity, 8f), dpInt(activity, 8f))
            layoutParams = FrameLayout.LayoutParams(
                dpInt(activity, 36f),
                dpInt(activity, 36f),
                Gravity.END or Gravity.CENTER_VERTICAL
            )
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(closeBtn)
        root.addView(header)

        // Tab Row: [ Video ] [ Photo ] [ Audio ]
        val tabRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpInt(activity, 36f)
            ).also {
                it.marginStart = dpInt(activity, 16f)
                it.marginEnd = dpInt(activity, 16f)
                it.topMargin = dpInt(activity, 4f)
                it.bottomMargin = dpInt(activity, 8f)
            }
        }

        val videoTab = createTabButton(activity, "Video", currentTab == MediaType.VIDEO)
        val photoTab = createTabButton(activity, "Photo", currentTab == MediaType.PHOTO)
        val audioTab = createTabButton(activity, "Audio", currentTab == MediaType.AUDIO)

        tabRow.addView(videoTab)
        tabRow.addView(photoTab)
        tabRow.addView(audioTab)
        root.addView(tabRow)

        // Metadata / Info Strip
        val infoStrip = TextView(activity).apply {
            setTextColor(Color.parseColor("#8A99AD"))
            textSize = 11.5f
            typeface = Typeface.SANS_SERIF
            setPadding(dpInt(activity, 16f), 0, dpInt(activity, 16f), dpInt(activity, 6f))
            visibility = View.GONE
        }
        root.addView(infoStrip)

        // Recycler View for Grid / List
        val recyclerView = RecyclerView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpInt(activity, 320f)
            )
            setPadding(dpInt(activity, 12f), 0, dpInt(activity, 12f), 0)
            clipToPadding = false
        }
        root.addView(recyclerView)

        // Empty state view
        val emptyStateView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpInt(activity, 200f)
            )
            visibility = View.GONE

            val emptyText = TextView(activity).apply {
                text = "No media found"
                setTextColor(Color.parseColor("#6B7888"))
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
            addView(emptyText)

            val browseBtn = Button(activity).apply {
                text = "Browse from Device"
                setTextColor(Color.parseColor("#388BFD"))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#151A24"))
                    setStroke(dpInt(activity, 1f), Color.parseColor("#263244"))
                    cornerRadius = dp(activity, 8f)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dpInt(activity, 34f)
                ).also { it.topMargin = dpInt(activity, 10f) }
                setOnClickListener {
                    dialog.dismiss()
                    onBrowseSystemPicker(targetTrackType)
                }
            }
            addView(browseBtn)
        }
        root.addView(emptyStateView)

        // Bottom Action Bar: [ Browse Files ] | [ Add to Timeline (N) ]
        val bottomBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpInt(activity, 48f)
            ).also {
                it.marginStart = dpInt(activity, 16f)
                it.marginEnd = dpInt(activity, 16f)
                it.topMargin = dpInt(activity, 10f)
            }
        }

        val browseDeviceBtn = TextView(activity).apply {
            text = "Browse Files"
            setTextColor(Color.parseColor("#8A99AD"))
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#141922"))
                setStroke(dpInt(activity, 1f), Color.parseColor("#222A36"))
                cornerRadius = dp(activity, 10f)
            }
            layoutParams = LinearLayout.LayoutParams(
                0,
                dpInt(activity, 40f),
                1f
            ).also { it.marginEnd = dpInt(activity, 8f) }
            setOnClickListener {
                dialog.dismiss()
                onBrowseSystemPicker(targetTrackType)
            }
        }
        bottomBar.addView(browseDeviceBtn)

        val addBtn = TextView(activity).apply {
            text = "Add to Timeline"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#388BFD"))
                cornerRadius = dp(activity, 10f)
            }
            layoutParams = LinearLayout.LayoutParams(
                0,
                dpInt(activity, 40f),
                1.3f
            )
            isEnabled = false
            alpha = 0.5f

            setOnClickListener {
                if (selectedItems.isNotEmpty()) {
                    val uris = selectedItems.map { it.uri }
                    dialog.dismiss()
                    onMediaSelected(uris, targetTrackType)
                }
            }
        }
        bottomBar.addView(addBtn)
        root.addView(bottomBar)

        fun updateAddButtonState() {
            val count = selectedItems.size
            if (count > 0) {
                addBtn.isEnabled = true
                addBtn.alpha = 1.0f
                addBtn.text = if (count > 1) "Add ($count)" else "Add to Timeline"

                val first = selectedItems.first()
                infoStrip.visibility = View.VISIBLE
                infoStrip.text = when (first.mediaType) {
                    MediaType.VIDEO -> {
                        val res = if (first.width > 0 && first.height > 0) "${first.width}x${first.height}" else "Video"
                        val dur = formatDuration(first.durationMs)
                        "$res • $dur • ${first.displayName}"
                    }
                    MediaType.PHOTO -> {
                        val res = if (first.width > 0 && first.height > 0) "${first.width}x${first.height}" else "Photo"
                        "$res • ${first.displayName}"
                    }
                    MediaType.AUDIO -> {
                        val dur = formatDuration(first.durationMs)
                        "Audio • $dur • ${first.displayName}"
                    }
                }
            } else {
                addBtn.isEnabled = false
                addBtn.alpha = 0.5f
                addBtn.text = "Add to Timeline"
                infoStrip.visibility = View.GONE
            }
        }

        lateinit var adapter: MediaAdapter

        fun loadMediaForTab(tab: MediaType) {
            currentTab = tab
            selectedItems.clear()
            updateAddButtonState()

            updateTabButton(videoTab, tab == MediaType.VIDEO)
            updateTabButton(photoTab, tab == MediaType.PHOTO)
            updateTabButton(audioTab, tab == MediaType.AUDIO)

            if (tab == MediaType.AUDIO) {
                recyclerView.layoutManager = LinearLayoutManager(activity)
            } else {
                recyclerView.layoutManager = GridLayoutManager(activity, 3)
            }

            mediaItems.clear()
            adapter.notifyDataSetChanged()
            emptyStateView.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE

            thumbnailExecutor.execute {
                val loaded = queryMediaStore(activity, tab)
                mainHandler.post {
                    mediaItems.clear()
                    mediaItems.addAll(loaded)
                    adapter.notifyDataSetChanged()
                    if (mediaItems.isEmpty()) {
                        emptyStateView.visibility = View.VISIBLE
                        recyclerView.visibility = View.GONE
                    } else {
                        emptyStateView.visibility = View.GONE
                        recyclerView.visibility = View.VISIBLE
                    }
                }
            }
        }

        adapter = MediaAdapter(activity, mediaItems, selectedItems) { item, isSelected ->
            if (currentTab == MediaType.AUDIO) {
                dialog.dismiss()
                onMediaSelected(listOf(item.uri), TrackType.AUDIO)
            } else {
                if (isSelected) {
                    selectedItems.add(item)
                } else {
                    selectedItems.remove(item)
                }
                adapter.notifyDataSetChanged()
                updateAddButtonState()
            }
        }
        recyclerView.adapter = adapter

        videoTab.setOnClickListener {
            targetTrackType = TrackType.VIDEO
            loadMediaForTab(MediaType.VIDEO)
        }
        photoTab.setOnClickListener {
            targetTrackType = TrackType.OVERLAY
            loadMediaForTab(MediaType.PHOTO)
        }
        audioTab.setOnClickListener {
            targetTrackType = TrackType.AUDIO
            loadMediaForTab(MediaType.AUDIO)
        }

        dialog.setContentView(root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.peekHeight = dpInt(activity, 480f)
        dialog.behavior.skipCollapsed = true
        dialog.show()

        loadMediaForTab(currentTab)
    }

    private fun createTabButton(context: Context, label: String, isSelected: Boolean): TextView {
        return TextView(context).apply {
            text = label
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            updateTabButton(this, isSelected)
            layoutParams = LinearLayout.LayoutParams(
                0,
                dpInt(context, 32f),
                1f
            ).also { it.marginEnd = dpInt(context, 6f) }
        }
    }

    private fun updateTabButton(view: TextView, isSelected: Boolean) {
        val context = view.context
        if (isSelected) {
            view.setTextColor(Color.WHITE)
            view.background = GradientDrawable().apply {
                setColor(Color.parseColor("#141E2E"))
                setStroke(dpInt(context, 1f), Color.parseColor("#388BFD"))
                cornerRadius = dp(context, 8f)
            }
        } else {
            view.setTextColor(Color.parseColor("#8A99AD"))
            view.background = GradientDrawable().apply {
                setColor(Color.parseColor("#171C23"))
                setStroke(dpInt(context, 1f), Color.parseColor("#222A36"))
                cornerRadius = dp(context, 8f)
            }
        }
    }

    private fun queryMediaStore(context: Context, type: MediaType): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        val cr = context.contentResolver

        try {
            when (type) {
                MediaType.VIDEO -> {
                    val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    val projection = arrayOf(
                        MediaStore.Video.Media._ID,
                        MediaStore.Video.Media.DISPLAY_NAME,
                        MediaStore.Video.Media.DURATION,
                        MediaStore.Video.Media.WIDTH,
                        MediaStore.Video.Media.HEIGHT,
                        MediaStore.Video.Media.SIZE
                    )
                    cr.query(uri, projection, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                        val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                        val wCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                        val hCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)

                        var count = 0
                        while (cursor.moveToNext() && count < 80) {
                            val id = cursor.getLong(idCol)
                            val name = cursor.getString(nameCol) ?: "Video ${count + 1}"
                            val dur = cursor.getLong(durCol).coerceAtLeast(0L)
                            val w = cursor.getInt(wCol)
                            val h = cursor.getInt(hCol)
                            val size = cursor.getLong(sizeCol)
                            val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                            items.add(
                                MediaItem(
                                    id = id,
                                    uri = contentUri,
                                    displayName = name,
                                    durationMs = dur,
                                    width = w,
                                    height = h,
                                    sizeBytes = size,
                                    mediaType = MediaType.VIDEO
                                )
                            )
                            count++
                        }
                    }
                }
                MediaType.PHOTO -> {
                    val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    val projection = arrayOf(
                        MediaStore.Images.Media._ID,
                        MediaStore.Images.Media.DISPLAY_NAME,
                        MediaStore.Images.Media.WIDTH,
                        MediaStore.Images.Media.HEIGHT,
                        MediaStore.Images.Media.SIZE
                    )
                    cr.query(uri, projection, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                        val wCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                        val hCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

                        var count = 0
                        while (cursor.moveToNext() && count < 80) {
                            val id = cursor.getLong(idCol)
                            val name = cursor.getString(nameCol) ?: "Photo ${count + 1}"
                            val w = cursor.getInt(wCol)
                            val h = cursor.getInt(hCol)
                            val size = cursor.getLong(sizeCol)
                            val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                            items.add(
                                MediaItem(
                                    id = id,
                                    uri = contentUri,
                                    displayName = name,
                                    durationMs = 3000L,
                                    width = w,
                                    height = h,
                                    sizeBytes = size,
                                    mediaType = MediaType.PHOTO
                                )
                            )
                            count++
                        }
                    }
                }
                MediaType.AUDIO -> {
                    val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    val projection = arrayOf(
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.TITLE,
                        MediaStore.Audio.Media.ARTIST,
                        MediaStore.Audio.Media.DURATION,
                        MediaStore.Audio.Media.SIZE
                    )
                    cr.query(uri, projection, null, null, "${MediaStore.Audio.Media.DATE_ADDED} DESC")?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                        val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                        val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                        val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)

                        var count = 0
                        while (cursor.moveToNext() && count < 80) {
                            val id = cursor.getLong(idCol)
                            val title = cursor.getString(titleCol) ?: "Audio ${count + 1}"
                            val artist = cursor.getString(artistCol) ?: "Unknown"
                            val dur = cursor.getLong(durCol).coerceAtLeast(0L)
                            val size = cursor.getLong(sizeCol)
                            val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                            items.add(
                                MediaItem(
                                    id = id,
                                    uri = contentUri,
                                    displayName = title,
                                    durationMs = dur,
                                    sizeBytes = size,
                                    mediaType = MediaType.AUDIO,
                                    artist = artist
                                )
                            )
                            count++
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return items
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSec = (durationMs / 1000L).coerceAtLeast(0L)
        val min = totalSec / 60L
        val sec = totalSec % 60L
        return "%02d:%02d".format(min, sec)
    }

    private class MediaAdapter(
        private val context: Context,
        private val items: List<MediaItem>,
        private val selectedItems: Set<MediaItem>,
        private val onItemClick: (item: MediaItem, isSelected: Boolean) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int): Int {
            return if (items[position].mediaType == MediaType.AUDIO) 1 else 0
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 1) {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        dpInt(context, 56f)
                    ).also {
                        it.bottomMargin = dpInt(context, 6f)
                    }
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#131720"))
                        cornerRadius = dp(context, 8f)
                        setStroke(dpInt(context, 1f), Color.parseColor("#1A212D"))
                    }
                    setPadding(dpInt(context, 12f), dpInt(context, 6f), dpInt(context, 12f), dpInt(context, 6f))
                }
                AudioViewHolder(row)
            } else {
                val frame = FrameLayout(context).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        dpInt(context, 104f)
                    ).also {
                        it.marginEnd = dpInt(context, 6f)
                        it.bottomMargin = dpInt(context, 6f)
                    }
                }
                GridViewHolder(frame)
            }
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            val isSelected = selectedItems.contains(item)

            if (holder is GridViewHolder) {
                holder.bind(item, isSelected)
            } else if (holder is AudioViewHolder) {
                holder.bind(item)
            }
        }

        inner class GridViewHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container) {
            private val imageView = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1A202C"))
                    cornerRadius = dp(context, 8f)
                }
                clipToOutline = true
            }

            private val durationBadge = TextView(context).apply {
                textSize = 9.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#CC070A0F"))
                    cornerRadius = dp(context, 4f)
                }
                setPadding(dpInt(context, 5f), dpInt(context, 2f), dpInt(context, 5f), dpInt(context, 2f))
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.END
                ).also {
                    it.marginEnd = dpInt(context, 4f)
                    it.bottomMargin = dpInt(context, 4f)
                }
            }

            private val checkBadge = TextView(context).apply {
                text = "✓"
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#388BFD"))
                    shape = GradientDrawable.OVAL
                }
                layoutParams = FrameLayout.LayoutParams(
                    dpInt(context, 20f),
                    dpInt(context, 20f),
                    Gravity.TOP or Gravity.END
                ).also {
                    it.marginEnd = dpInt(context, 4f)
                    it.topMargin = dpInt(context, 4f)
                }
            }

            private val selectionBorder = View(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    setStroke(dpInt(context, 2.5f), Color.parseColor("#388BFD"))
                    cornerRadius = dp(context, 8f)
                }
            }

            init {
                container.addView(imageView)
                container.addView(durationBadge)
                container.addView(selectionBorder)
                container.addView(checkBadge)

                container.setOnClickListener {
                    val pos = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        val item = items[pos]
                        val currentlySelected = selectedItems.contains(item)
                        onItemClick(item, !currentlySelected)
                    }
                }
            }

            fun bind(item: MediaItem, isSelected: Boolean) {
                selectionBorder.visibility = if (isSelected) View.VISIBLE else View.GONE
                checkBadge.visibility = if (isSelected) View.VISIBLE else View.GONE

                if (item.mediaType == MediaType.VIDEO) {
                    durationBadge.visibility = View.VISIBLE
                    durationBadge.text = formatDuration(item.durationMs)
                } else {
                    durationBadge.visibility = View.GONE
                }

                imageView.setImageBitmap(null)
                loadThumbnailAsync(item, imageView)
            }
        }

        inner class AudioViewHolder(val row: LinearLayout) : RecyclerView.ViewHolder(row) {
            private val titleText = TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
            }

            private val artistText = TextView(context).apply {
                setTextColor(Color.parseColor("#8A99AD"))
                textSize = 11f
                maxLines = 1
            }

            private val addBtn = TextView(context).apply {
                text = "+ Add"
                setTextColor(Color.WHITE)
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#388BFD"))
                    cornerRadius = dp(context, 6f)
                }
                setPadding(dpInt(context, 12f), dpInt(context, 6f), dpInt(context, 12f), dpInt(context, 6f))
            }

            init {
                val icon = TextView(context).apply {
                    text = "♪"
                    textSize = 16f
                    setTextColor(Color.parseColor("#388BFD"))
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#1C2433"))
                        shape = GradientDrawable.OVAL
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        dpInt(context, 32f),
                        dpInt(context, 32f)
                    ).also { it.marginEnd = dpInt(context, 10f) }
                }
                row.addView(icon)

                val textCol = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                    addView(titleText)
                    addView(artistText)
                }
                row.addView(textCol)
                row.addView(addBtn)

                addBtn.setOnClickListener {
                    val pos = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        onItemClick(items[pos], true)
                    }
                }
                row.setOnClickListener {
                    val pos = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        onItemClick(items[pos], true)
                    }
                }
            }

            fun bind(item: MediaItem) {
                titleText.text = item.displayName.replace('_', ' ')
                val durStr = formatDuration(item.durationMs)
                artistText.text = if (item.artist.isNotBlank() && item.artist != "<unknown>") "${item.artist} • $durStr" else durStr
            }
        }

        private fun loadThumbnailAsync(item: MediaItem, target: ImageView) {
            val cached = thumbnailCache.get(item.id)
            if (cached != null) {
                target.setImageBitmap(cached)
                return
            }

            target.tag = item.id
            thumbnailExecutor.execute {
                val bmp = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        context.contentResolver.loadThumbnail(item.uri, Size(180, 180), null)
                    } else {
                        if (item.mediaType == MediaType.VIDEO) {
                            MediaStore.Video.Thumbnails.getThumbnail(
                                context.contentResolver,
                                item.id,
                                MediaStore.Video.Thumbnails.MINI_KIND,
                                null
                            )
                        } else {
                            MediaStore.Images.Thumbnails.getThumbnail(
                                context.contentResolver,
                                item.id,
                                MediaStore.Images.Thumbnails.MINI_KIND,
                                null
                            )
                        }
                    }
                } catch (e: Exception) {
                    null
                }

                if (bmp != null) {
                    thumbnailCache.put(item.id, bmp)
                    mainHandler.post {
                        if (target.tag == item.id) {
                            target.setImageBitmap(bmp)
                        }
                    }
                }
            }
        }
    }
}
