package com.alessandrognola.superpong

import android.app.Activity
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * Scaffolding for Google Play Billing (gem purchases + Shop theme packs). Mirrors LeaderboardManager: safe no-op
 * until real in-app products exist.
 *
 * SETUP NEEDED before this does anything:
 *   1. Play Console > Monetize > Products > In-app products: create the 3 consumable gem products
 *      and 4 non-consumable theme products (theme_soccer/theme_basketball/theme_hockey/theme_space, €0.50 each).
 *   2. Replace the productId values in GEM_PACKS/THEME_PACKS below with the real ones if they differ.
 * Until then, priceFor() returns null and purchase() is a no-op - expected, not a bug.
 */
object BillingManager {
    private const val TAG = "BillingManager"

    data class GemPack(val productId: String, val gemAmount: Int)
    data class ThemeProduct(val productId: String, val themeId: String)

    val GEM_PACKS = listOf(
        GemPack("gems_100", 100),
        GemPack("gems_600", 600),
        GemPack("gems_1500", 1500)
    )

    val THEME_PACKS = listOf(
        ThemeProduct("theme_soccer", "soccer"),
        ThemeProduct("theme_basketball", "basketball"),
        ThemeProduct("theme_hockey", "hockey"),
        ThemeProduct("theme_space", "space")
    )

    @Volatile private var billingClient: BillingClient? = null
    private val productDetails = mutableMapOf<String, ProductDetails>()
    private var onGemsGranted: ((Int) -> Unit)? = null
    private var onThemeUnlocked: ((String) -> Unit)? = null

    fun init(activity: Activity, onGemsGranted: (Int) -> Unit, onThemeUnlocked: (String) -> Unit) {
        this.onGemsGranted = onGemsGranted
        this.onThemeUnlocked = onThemeUnlocked
        try {
            val listener = PurchasesUpdatedListener { billingResult, purchases ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                    for (purchase in purchases) handlePurchase(purchase)
                }
            }
            val client = BillingClient.newBuilder(activity)
                .setListener(listener)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .build()
            billingClient = client
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        queryProducts()
                        restoreThemePurchases()
                    }
                }
                override fun onBillingServiceDisconnected() { /* will retry on next launch */ }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Play Billing unavailable: ${e.message}")
        }
    }

    private fun queryProducts() {
        val client = billingClient ?: return
        try {
            val allIds = GEM_PACKS.map { it.productId } + THEME_PACKS.map { it.productId }
            val products = allIds.map { id ->
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(id)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
            }
            val params = QueryProductDetailsParams.newBuilder().setProductList(products).build()
            client.queryProductDetailsAsync(params) { billingResult, result ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    for (details in result.productDetailsList) {
                        productDetails[details.productId] = details
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "queryProductDetailsAsync failed: ${e.message}")
        }
    }

    /** Theme packs are non-consumable, so ownership must be restored on every launch (reinstall/new device). */
    private fun restoreThemePurchases() {
        val client = billingClient ?: return
        try {
            val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
            client.queryPurchasesAsync(params) { billingResult, purchases ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    for (purchase in purchases) handlePurchase(purchase)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "queryPurchasesAsync failed: ${e.message}")
        }
    }

    fun priceFor(productId: String): String? =
        productDetails[productId]?.oneTimePurchaseOfferDetails?.formattedPrice

    fun purchase(activity: Activity, productId: String) {
        val client = billingClient ?: return
        val details = productDetails[productId] ?: return
        try {
            val params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).build())
                )
                .build()
            client.launchBillingFlow(activity, params)
        } catch (e: Exception) {
            Log.w(TAG, "launchBillingFlow failed: ${e.message}")
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        val client = billingClient ?: return

        val pack = GEM_PACKS.find { purchase.products.contains(it.productId) }
        if (pack != null) {
            try {
                val consumeParams = ConsumeParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
                client.consumeAsync(consumeParams) { billingResult, _ ->
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        onGemsGranted?.invoke(pack.gemAmount)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "consumeAsync failed: ${e.message}")
            }
            return
        }

        val theme = THEME_PACKS.find { purchase.products.contains(it.productId) }
        if (theme != null) {
            onThemeUnlocked?.invoke(theme.themeId)
            if (!purchase.isAcknowledged) {
                try {
                    val ackParams = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
                    client.acknowledgePurchase(ackParams) { }
                } catch (e: Exception) {
                    Log.w(TAG, "acknowledgePurchase failed: ${e.message}")
                }
            }
        }
    }
}
