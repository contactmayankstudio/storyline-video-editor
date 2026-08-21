package com.video.engine

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Storyline Design System — Phase 1 Consolidation
 * Central source of truth for colors, typography, spacing, corner radii, and standard controls.
 */
object DesignSystem {

    // ── 1. COLOR TOKENS ──
    object Colors {
        // Surfaces & Backgrounds
        val bgRoot = Color.parseColor("#090B0F")
        val bgPreview = Color.parseColor("#000000")
        val bgTimeline = Color.parseColor("#0D1015")
        val bgSheet = Color.parseColor("#11151B")
        val bgCard = Color.parseColor("#171C23")
        val bgCardSelected = Color.parseColor("#162235")
        val bgInput = Color.parseColor("#141920")

        // Borders & Dividers
        val borderSubtle = Color.parseColor("#1B222C")
        val borderCard = Color.parseColor("#222A36")
        val borderFocus = Color.parseColor("#388BFD")
        val borderDestructive = Color.parseColor("#542226")
        val handleColor = Color.parseColor("#3A4452")
        val dividerColor = Color.parseColor("#161B23")

        // Primary Accents
        val accentPrimary = Color.parseColor("#388BFD")
        val accentHover = Color.parseColor("#58A6FF")
        val accentPressed = Color.parseColor("#1F6FEB")
        val accentMutedBg = Color.parseColor("#17263C")
        val accentMutedBorder = Color.parseColor("#2B5282")

        // Semantic Colors
        val semanticSuccess = Color.parseColor("#3FB950")
        val semanticWarning = Color.parseColor("#D29922")
        val semanticDestructive = Color.parseColor("#F85149")
        val surfaceDestructive = Color.parseColor("#261618")
        val semanticInfo = Color.parseColor("#388BFD")

        // Typography Colors
        val textPrimary = Color.parseColor("#FFFFFF")
        val textSecondary = Color.parseColor("#8A99AD")
        val textDisabled = Color.parseColor("#485361")
        val textAccent = Color.parseColor("#388BFD")
        val textDestructive = Color.parseColor("#F85149")

        // Interactive States
        val buttonEnabled = Color.parseColor("#141922")
        val buttonPressed = Color.parseColor("#0F131A")
        val buttonDisabled = Color.parseColor("#1A202A")
    }

    // ── 2. SPACING SCALE (dp) ──
    object Spacing {
        const val space2xs = 2
        const val spaceXs = 4
        const val spaceSm = 8
        const val spaceMd = 12
        const val spaceLg = 16
        const val spaceXl = 20
        const val space2xl = 24
        const val space3xl = 32

        // Standard Screen Paddings
        const val screenHorizontal = 16
        const val sheetTop = 12
        const val sheetBottom = 24
        const val cardPaddingHorizontal = 16
        const val cardPaddingVertical = 12
        const val rowItemSpacing = 8
    }

    // ── 3. CORNER RADII (dp) ──
    object Radius {
        const val small = 8      // Small controls, badges, chips
        const val medium = 12    // Buttons, cards, inputs
        const val large = 16     // Prominent cards, summary blocks
        const val sheet = 24     // Top corners of modal bottom sheets
        const val pill = 999     // Rounded pills, scrubber thumbs
    }

    // ── 4. TYPOGRAPHY SIZES (sp) ──
    object Typography {
        const val sizeScreenTitle = 24f
        const val sizeSectionTitle = 15f
        const val sizeBody = 14f
        const val sizeSecondary = 13f
        const val sizeButton = 14f
        const val sizeMeta = 11.5f
        const val sizeTime = 11.5f
        const val sizeToolbar = 9f
    }

    // ── 5. TOUCH TARGETS & ICON SIZES (dp) ──
    object Dimensions {
        const val minTouchTarget = 48
        const val iconToolbar = 22
        const val iconAction = 20
        const val iconSmall = 16
        const val buttonHeightSm = 32
        const val buttonHeightMd = 40
        const val buttonHeightLg = 48
        const val dragHandleWidth = 36
        const val dragHandleHeight = 4
    }

    // ── 6. DRAWABLE BUILDERS ──

    fun dp(context: Context, dpValue: Int): Int =
        (dpValue * context.resources.displayMetrics.density).roundToInt()

    fun dpF(context: Context, dpValue: Int): Float =
        dpValue * context.resources.displayMetrics.density

    fun sheetBackground(context: Context): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            val r = dpF(context, Radius.sheet)
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setColor(Colors.bgSheet)
            setStroke(dp(context, 1), Colors.borderSubtle)
        }

    fun cardBackground(
        context: Context,
        selected: Boolean = false,
        cornerRadiusDp: Int = Radius.medium,
    ): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(context, cornerRadiusDp)
            if (selected) {
                setColor(Colors.bgCardSelected)
                setStroke(dp(context, 1), Colors.borderFocus)
            } else {
                setColor(Colors.bgCard)
                setStroke(dp(context, 1), Colors.borderCard)
            }
        }

    fun primaryButtonBackground(context: Context, cornerRadiusDp: Int = Radius.medium): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(context, cornerRadiusDp)
            setColor(Colors.accentMutedBg)
            setStroke(dp(context, 1), Colors.accentMutedBorder)
        }

    fun secondaryButtonBackground(context: Context, cornerRadiusDp: Int = Radius.medium): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(context, cornerRadiusDp)
            setColor(Colors.bgCard)
            setStroke(dp(context, 1), Colors.borderCard)
        }

    fun destructiveButtonBackground(context: Context, cornerRadiusDp: Int = Radius.medium): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(context, cornerRadiusDp)
            setColor(Colors.surfaceDestructive)
            setStroke(dp(context, 1), Colors.borderDestructive)
        }

    fun chipBackground(context: Context, selected: Boolean): GradientDrawable =
        cardBackground(context, selected = selected, cornerRadiusDp = Radius.small)

    fun inputBackground(context: Context, cornerRadiusDp: Int = Radius.medium): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(context, cornerRadiusDp)
            setColor(Colors.bgInput)
            setStroke(dp(context, 1), Color.parseColor("#1E2632"))
        }

    fun dragHandle(context: Context): View =
        View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                dp(context, Dimensions.dragHandleWidth),
                dp(context, Dimensions.dragHandleHeight),
                Gravity.CENTER_HORIZONTAL,
            ).also {
                it.topMargin = dp(context, Spacing.spaceXs)
                it.bottomMargin = dp(context, Spacing.spaceSm)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpF(context, 2)
                setColor(Colors.handleColor)
            }
        }

    fun sectionTitle(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = text.uppercase()
            textSize = Typography.sizeMeta
            setTextColor(Colors.textSecondary)
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.08f
            setPadding(
                dp(context, Spacing.spaceSm),
                dp(context, Spacing.spaceMd),
                dp(context, Spacing.spaceSm),
                dp(context, Spacing.spaceXs),
            )
        }

    fun bodyText(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = text
            textSize = Typography.sizeBody
            setTextColor(Colors.textPrimary)
            setPadding(0, dp(context, Spacing.spaceXs), 0, dp(context, Spacing.spaceXs))
        }

    fun divider(context: Context): View =
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 1),
            ).also {
                it.topMargin = dp(context, Spacing.spaceSm)
                it.bottomMargin = dp(context, Spacing.spaceSm)
            }
            setBackgroundColor(Colors.borderSubtle)
        }
}
