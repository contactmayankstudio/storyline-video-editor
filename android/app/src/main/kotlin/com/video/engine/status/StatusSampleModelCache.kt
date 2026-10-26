package com.video.engine.status

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.InputStream
import java.util.Locale

object StatusSampleModelCache {

    private const val CACHE_SIZE_BYTES = 24 * 1024 * 1024 // 24 MB cache for smooth 60fps scrolling

    private val memoryCache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(CACHE_SIZE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount
        }
    }

    const val MODEL_FEMALE_STYLISH = "sample_models/model_female_stylish.jpg"
    const val MODEL_MALE_ATTITUDE = "sample_models/model_male_attitude.jpg"
    const val MODEL_COUPLE_LOVE = "sample_models/model_couple_love.jpg"
    const val MODEL_FEMALE_CELEBRATION = "sample_models/model_female_celebration.jpg"

    val ALL_MODEL_ASSET_PATHS = listOf(
        MODEL_FEMALE_STYLISH,
        MODEL_MALE_ATTITUDE,
        MODEL_COUPLE_LOVE,
        MODEL_FEMALE_CELEBRATION
    )

    fun getModelAssetForTemplate(template: StatusTemplate): String {
        val cat = template.category.lowercase(Locale.US)
        val title = template.title.lowercase(Locale.US)
        val id = template.id.lowercase(Locale.US)

        return when {
            cat.contains("love") || cat.contains("couple") || title.contains("love") || title.contains("couple") || title.contains("heart") ->
                MODEL_COUPLE_LOVE

            cat.contains("attitude") || cat.contains("swag") || cat.contains("phonk") || title.contains("sigma") || title.contains("attitude") || title.contains("bass") || title.contains("gangster") ->
                MODEL_MALE_ATTITUDE

            cat.contains("celebration") || cat.contains("birthday") || cat.contains("spiritual") || cat.contains("bhakti") || title.contains("birthday") || title.contains("party") || title.contains("festive") ->
                MODEL_FEMALE_CELEBRATION

            else -> {
                // For Trending, Viral, Aesthetic, Speed-ramp: alternate naturally between female stylish and male attitude
                if (Math.abs(template.id.hashCode()) % 2 == 0) {
                    MODEL_FEMALE_STYLISH
                } else {
                    MODEL_MALE_ATTITUDE
                }
            }
        }
    }

    fun getModelBitmap(
        context: Context,
        assetPath: String,
        targetWidth: Int = 360,
        targetHeight: Int = 640
    ): Bitmap? {
        val cacheKey = "$assetPath-${targetWidth}x$targetHeight"
        synchronized(memoryCache) {
            val cached = memoryCache.get(cacheKey)
            if (cached != null && !cached.isRecycled) {
                return cached
            }
        }

        val decoded = decodeSampledBitmapFromAsset(context, assetPath, targetWidth, targetHeight)
            ?: decodeFallbackBitmap(context, targetWidth, targetHeight)

        if (decoded != null) {
            synchronized(memoryCache) {
                memoryCache.put(cacheKey, decoded)
            }
        }
        return decoded
    }

    fun getSampleBitmapForTemplate(
        context: Context,
        template: StatusTemplate,
        targetWidth: Int = 360,
        targetHeight: Int = 640
    ): Bitmap? {
        val assetPath = getModelAssetForTemplate(template)
        return getModelBitmap(context, assetPath, targetWidth, targetHeight)
    }

    fun getAllSampleBitmaps(
        context: Context,
        targetWidth: Int = 540,
        targetHeight: Int = 960
    ): List<Bitmap> {
        val list = mutableListOf<Bitmap>()
        for (path in ALL_MODEL_ASSET_PATHS) {
            val bmp = getModelBitmap(context, path, targetWidth, targetHeight)
            if (bmp != null && !bmp.isRecycled) {
                list.add(bmp)
            }
        }
        return list
    }

    private fun decodeSampledBitmapFromAsset(
        context: Context,
        assetPath: String,
        reqWidth: Int,
        reqHeight: Int
    ): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.assets.open(assetPath).use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.RGB_565 // Fast and memory efficient

            context.assets.open(assetPath).use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (_: Exception) {
            // If specific model doesn't exist yet, try first available model
            tryFirstAvailableModel(context, reqWidth, reqHeight)
        }
    }

    private fun tryFirstAvailableModel(context: Context, reqWidth: Int, reqHeight: Int): Bitmap? {
        for (path in ALL_MODEL_ASSET_PATHS) {
            try {
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                context.assets.open(path).use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                }
                options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
                options.inJustDecodeBounds = false
                options.inPreferredConfig = Bitmap.Config.RGB_565
                context.assets.open(path).use { stream ->
                    return BitmapFactory.decodeStream(stream, null, options)
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun decodeFallbackBitmap(context: Context, reqWidth: Int, reqHeight: Int): Bitmap? {
        return tryFirstAvailableModel(context, reqWidth, reqHeight)
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    fun clear() {
        synchronized(memoryCache) {
            memoryCache.evictAll()
        }
    }
}
