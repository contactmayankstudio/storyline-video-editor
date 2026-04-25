package com.video.engine

import android.app.Activity
import android.util.Log
import com.video.engine.UiToast as Toast
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

class RewardedUnlockController(
    private val activity: Activity,
) {
    companion object {
        private const val TAG = "[Reward]"
    }

    private var rewardedAd: RewardedAd? = null
    private var loading = false
    private var pendingUnlockCallback: ((Boolean) -> Unit)? = null
    private var pendingShowAfterLoad = false
    private var watermarkUnlocked = false

    fun isWatermarkUnlocked(): Boolean = watermarkUnlocked

    fun preload() {
        if (watermarkUnlocked || rewardedAd != null || loading || activity.isFinishing || activity.isDestroyed) {
            return
        }
        loadRewardedAd(showOnLoad = false)
    }

    fun requestWatermarkUnlock(onResult: (Boolean) -> Unit) {
        if (watermarkUnlocked) {
            onResult(true)
            return
        }
        if (activity.isFinishing || activity.isDestroyed) {
            onResult(false)
            return
        }
        rewardedAd?.let { ad ->
            showRewardedAd(ad, onResult)
            return
        }
        pendingUnlockCallback = onResult
        pendingShowAfterLoad = true
        loadRewardedAd(showOnLoad = true)
    }

    fun onDestroy() {
        rewardedAd = null
        pendingUnlockCallback = null
        pendingShowAfterLoad = false
        loading = false
    }

    private fun loadRewardedAd(showOnLoad: Boolean) {
        if (loading || activity.isFinishing || activity.isDestroyed) {
            return
        }
        loading = true
        pendingShowAfterLoad = pendingShowAfterLoad || showOnLoad
        Log.d(TAG, "Loading rewarded ad for watermark unlock")
        MobileAds.initialize(activity) {}
        RewardedAd.load(
            activity,
            activity.getString(R.string.admob_rewarded_hd_unlock_unit_id),
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    loading = false
                    rewardedAd = ad
                    Log.d(TAG, "Rewarded ad loaded for watermark unlock")
                    if (pendingShowAfterLoad) {
                        val callback = pendingUnlockCallback
                        if (callback != null) {
                            showRewardedAd(ad, callback)
                        }
                    }
                }

                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    loading = false
                    rewardedAd = null
                    Log.w(TAG, "Rewarded ad failed: ${loadAdError.message}")
                    if (pendingShowAfterLoad) {
                        pendingShowAfterLoad = false
                        val callback = pendingUnlockCallback
                        pendingUnlockCallback = null
                        Toast.makeText(activity, "Ad not ready yet", Toast.LENGTH_SHORT).show()
                        callback?.invoke(false)
                    }
                }
            },
        )
    }

    private fun showRewardedAd(ad: RewardedAd, onResult: (Boolean) -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) {
            onResult(false)
            return
        }
        var earnedReward = false
        rewardedAd = null
        pendingShowAfterLoad = false
        pendingUnlockCallback = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                onResult(earnedReward)
                preload()
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                Log.w(TAG, "Rewarded ad failed to show: ${adError.message}")
                Toast.makeText(activity, "Unable to show ad", Toast.LENGTH_SHORT).show()
                onResult(false)
                preload()
            }
        }
        ad.show(activity) {
            earnedReward = true
            watermarkUnlocked = true
            Toast.makeText(activity, "Watermark removed for this session", Toast.LENGTH_SHORT).show()
        }
    }
}
