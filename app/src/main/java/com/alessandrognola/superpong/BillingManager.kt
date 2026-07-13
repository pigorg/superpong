package com.alessandrognola.superpong

import android.app.Activity
import android.util.Log
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

/**
 * Scaffolding for Google Play Billing (gem purchases). Mirrors LeaderboardManager: safe no-op
 * until real in-app products exist.
 *
 * SETUP NEEDED before this does anything:
 *   1. Play Console > Monetize > Products > In-app products: create 3 consumable products.
 *   2. Replace the productId values in GEM_PACKS below with the real ones.
 * Until then, priceFor() returns null and purchase() is a no-op - expected, not a bug.
 */
object BillingManager {
    private const val TAG = "BillingManager"

    data class GemPack(val productId: String, val gemAmount: Int)

    val GEM_PACKS = listOf(
        GemPack("gems_100", 100),
        GemPack("gems_600", 600),
        GemPack("gems_1500", 1500)
    )

    @Volatile private var billingClient: BillingClient? = null
    private val productDetails = mutableMapOf<String, ProductDetails>()
    private var onGemsGranted: ((Int) -> Unit)? = null

    fun init(activity: Activity, onGemsGranted: (Int) -> Unit) {
        this.onGemsGranted = onGemsGranted
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
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) queryProducts()
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
            val products = GEM_PACKS.map {
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(it.productId)
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
        val pack = GEM_PACKS.find { purchase.products.contains(it.productId) } ?: return
        val client = billingClient ?: return
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
    }
}
