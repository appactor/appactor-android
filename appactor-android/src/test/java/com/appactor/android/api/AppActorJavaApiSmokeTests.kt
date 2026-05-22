package com.appactor.android.api

import android.app.Activity
import com.appactor.android.models.AppActorBridgeErrorCallback
import com.appactor.android.models.AppActorErrorCallback
import com.appactor.android.models.AppActorPackage
import com.appactor.android.models.AppActorPurchaseParams
import com.appactor.android.models.AppActorSuccessCallback
import org.junit.Assert.assertTrue
import org.junit.Test

class AppActorJavaApiSmokeTests {

    @Test
    fun `java facades expose placement overloads without removing callback overloads`() {
        val bridgePurchaseSignatures = AppActorBridge::class.java.methods
            .filter { it.name == "purchase" }
            .map { method -> method.parameterTypes.toList() }

        assertTrue(
            bridgePurchaseSignatures.any {
                it == listOf(
                    Activity::class.java,
                    AppActorPackage::class.java,
                    AppActorSuccessCallback::class.java,
                    AppActorBridgeErrorCallback::class.java,
                )
            }
        )
        assertTrue(
            AppActorBridge::class.java.methods
                .filter { it.name == "purchaseWithPlacement" }
                .map { method -> method.parameterTypes.toList() }
                .any {
                    it == listOf(
                        Activity::class.java,
                        AppActorPackage::class.java,
                        String::class.java,
                        AppActorSuccessCallback::class.java,
                        AppActorBridgeErrorCallback::class.java,
                    )
                }
        )
        assertTrue(
            bridgePurchaseSignatures.any {
                it == listOf(
                    Activity::class.java,
                    AppActorPurchaseParams::class.java,
                    AppActorSuccessCallback::class.java,
                    AppActorBridgeErrorCallback::class.java,
                )
            }
        )
        assertTrue(
            AppActorBridge::class.java.methods
                .filter { it.name == "purchaseWithPlacement" }
                .map { method -> method.parameterTypes.toList() }
                .any {
                    it == listOf(
                        Activity::class.java,
                        AppActorPurchaseParams::class.java,
                        String::class.java,
                        AppActorSuccessCallback::class.java,
                        AppActorBridgeErrorCallback::class.java,
                    )
                }
        )

        val javaPurchaseSignatures = AppActorJava::class.java.methods
            .filter { it.name == "purchaseAsync" }
            .map { method -> method.parameterTypes.toList() }

        assertTrue(
            AppActorJava::class.java.methods
                .filter { it.name == "purchaseAsyncWithPlacement" }
                .map { method -> method.parameterTypes.toList() }
                .any {
                    it == listOf(
                        Activity::class.java,
                        AppActorPackage::class.java,
                        String::class.java,
                        AppActorSuccessCallback::class.java,
                        AppActorErrorCallback::class.java,
                    )
                }
        )
        assertTrue(
            javaPurchaseSignatures.any {
                it == listOf(
                    Activity::class.java,
                    AppActorPackage::class.java,
                    AppActorSuccessCallback::class.java,
                    AppActorErrorCallback::class.java,
                )
            }
        )
    }
}
