package com.video.engine.billing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages Google Play Billing subscriptions and one-time purchases.
 * Premium features like "AI Background Removal" and "4K Export" require active entitlements.
 */
class BillingManager {

    companion object {
        const val PRODUCT_PRO_SUBSCRIPTION = "pro_subscription"
        const val PRODUCT_PREMIUM_LIFETIME = "premium_lifetime"
    }

    // State indicating if the user has an active pro subscription
    private val _isProSubscriptionActive = MutableStateFlow(false)
    val isProSubscriptionActive: StateFlow<Boolean> = _isProSubscriptionActive.asStateFlow()

    // State indicating if the user has purchased lifetime premium access
    private val _isPremiumLifetimeActive = MutableStateFlow(false)
    val isPremiumLifetimeActive: StateFlow<Boolean> = _isPremiumLifetimeActive.asStateFlow()

    /**
     * Checks whether the user currently has access to premium features.
     * @return true if either a pro subscription or premium lifetime entitlement is active.
     */
    fun hasPremiumAccess(): Boolean {
        return _isProSubscriptionActive.value || _isPremiumLifetimeActive.value
    }

    // ==========================================
    // Mock implementations for early scaffolding
    // ==========================================

    fun mockPurchase(productId: String) {
        when (productId) {
            PRODUCT_PRO_SUBSCRIPTION -> _isProSubscriptionActive.value = true
            PRODUCT_PREMIUM_LIFETIME -> _isPremiumLifetimeActive.value = true
        }
    }

    fun mockRevokePurchases() {
        _isProSubscriptionActive.value = false
        _isPremiumLifetimeActive.value = false
    }
}
