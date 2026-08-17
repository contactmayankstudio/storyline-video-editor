package com.video.engine

import android.app.Activity
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

class AdsController(
    private val activity: Activity,
) {
    companion object {
        private const val TAG = "[Ads]"
    }

    private val bannerViews = linkedMapOf<Int, AdView>()
    private val enabled = activity.resources.getBoolean(R.bool.storyline_runtime_ads_enabled)
    private var initialized = false
    private var interstitialAd: InterstitialAd? = null
    private var interstitialLoading = false

    fun initialize() {
        if (!enabled) return
        if (initialized) return
        initialized = true
        Log.d(TAG, "Initializing banner/interstitial ads")
        MobileAds.setRequestConfiguration(
            RequestConfiguration.Builder()
                .setTestDeviceIds(listOf(AdRequest.DEVICE_ID_EMULATOR))
                .build(),
        )
        MobileAds.initialize(activity) {}
        preloadInterstitial()
    }

    fun preloadPostExportInterstitial() {
        initialize()
        preloadInterstitial()
    }

    fun isPostExportInterstitialReady(): Boolean =
        enabled && interstitialAd != null

    // Call once after export completes
    fun showPostExportInterstitial(onDismissed: () -> Unit = {}): Boolean {
        if (!enabled) {
            onDismissed()
            return true
        }
        initialize()
        val ad = interstitialAd
        if (ad == null) {
            Log.d(TAG, "Post-export interstitial not ready; preloading")
            preloadInterstitial()
            return false
        }
        interstitialAd = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                onDismissed()
                preloadInterstitial()
            }
            override fun onAdFailedToShowFullScreenContent(e: AdError) {
                onDismissed()
                preloadInterstitial()
            }
        }
        Log.d(TAG, "Showing post-export interstitial")
        ad.show(activity)
        return true
    }

    private fun preloadInterstitial() {
        if (!enabled) return
        if (interstitialLoading || interstitialAd != null) return
        if (activity.isFinishing || activity.isDestroyed) return
        interstitialLoading = true
        InterstitialAd.load(
            activity,
            activity.getString(R.string.admob_interstitial_post_export_unit_id),
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialLoading = false
                    interstitialAd = ad
                    Log.d(TAG, "Interstitial loaded")
                }
                override fun onAdFailedToLoad(e: LoadAdError) {
                    interstitialLoading = false
                    Log.w(TAG, "Interstitial load failed: ${e.message}")
                }
            }
        )
    }

    fun attachTopBanner(container: FrameLayout?) {
        if (!enabled) {
            releaseBanner(container)
            return
        }
        initialize()
        val target = container ?: return
        target.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            val widthPx = target.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
            val density = activity.resources.displayMetrics.density
            val widthDp = (widthPx / density).toInt().coerceAtLeast(320)
            val existing = bannerViews[target.id]
            val desiredSize = AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, widthDp)
            if (existing != null && existing.adSize == desiredSize) {
                target.visibility = View.VISIBLE
                return@post
            }
            releaseBanner(target)
            Log.d(TAG, "Loading top banner for host=${target.id}, widthDp=$widthDp")
            val adView = AdView(activity).apply {
                adUnitId = activity.getString(R.string.admob_banner_unit_id)
                setAdSize(desiredSize)
                adListener = object : AdListener() {
                    override fun onAdLoaded() {
                        target.visibility = View.VISIBLE
                    }

                    override fun onAdFailedToLoad(adError: LoadAdError) {
                        Log.w(TAG, "Banner load failed for host=${target.id}: ${adError.message}")
                        hideBannerHost(target)
                    }
                }
            }
            target.removeAllViews()
            target.addView(
                adView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
            bannerViews[target.id] = adView
            target.visibility = View.VISIBLE
            adView.loadAd(AdRequest.Builder().build())
        }
    }

    fun releaseBanner(container: FrameLayout?) {
        val target = container ?: return
        bannerViews.remove(target.id)?.let { adView ->
            runCatching { adView.pause() }
            runCatching { adView.destroy() }
        }
        target.removeAllViews()
        hideBannerHost(target)
    }

    private fun hideBannerHost(target: FrameLayout) {
        target.visibility = View.GONE
    }

    fun onResume() {
        if (!enabled) return
        bannerViews.values.forEach { runCatching { it.resume() } }
    }

    fun onPause() {
        if (!enabled) return
        bannerViews.values.forEach { runCatching { it.pause() } }
    }

    fun onDestroy() {
        if (!enabled) return
        bannerViews.values.forEach { adView ->
            runCatching { adView.destroy() }
        }
        bannerViews.clear()
    }
}
