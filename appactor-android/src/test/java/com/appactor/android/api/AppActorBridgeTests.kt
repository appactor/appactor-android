package com.appactor.android.api

import android.os.Looper
import com.appactor.android.models.AppActorBridgeError
import com.appactor.android.models.AppActorBridgeErrorCallback
import com.appactor.android.models.AppActorBridgeReceiptEvent
import com.appactor.android.models.AppActorCustomerInfo
import com.appactor.android.models.AppActorReceiptPipelineEvent
import com.appactor.android.models.AppActorSuccessCallback
import com.appactor.android.models.appActorPublicReceiptId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class AppActorBridgeTests {

    @Before
    fun setUp() {
        resetApiTestState()
    }

    @Test
    fun `bridge error callbacks are delivered on main thread with flat errors`() {
        val latch = CountDownLatch(1)
        val callbackOnMain = AtomicReference<Boolean?>()
        val capturedError = AtomicReference<AppActorBridgeError?>()

        AppActorBridge.logOut(
            onSuccess = AppActorSuccessCallback { latch.countDown() },
            onError = AppActorBridgeErrorCallback { error ->
                callbackOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                capturedError.set(error)
                latch.countDown()
            },
        )

        assertTrue(awaitMainThreadCallback(latch))
        assertEquals(AppActorBridgeError.CODE_NOT_CONFIGURED, capturedError.get()?.code)
        assertEquals(false, capturedError.get()?.isTransient)
        assertEquals(true, callbackOnMain.get())
    }

    @Test
    fun `bridge listener surfaces flatten receipt events and deliver on main thread`() {
        val customerLatch = CountDownLatch(1)
        val eventLatch = CountDownLatch(1)
        val customerCallbackOnMain = AtomicReference<Boolean?>()
        val eventCallbackOnMain = AtomicReference<Boolean?>()
        val capturedUserId = AtomicReference<String?>()
        val capturedEvent = AtomicReference<AppActorBridgeReceiptEvent?>()

        AppActorBridge.setCustomerInfoListener(
            AppActorSuccessCallback { info ->
                customerCallbackOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                capturedUserId.set(info.appUserId)
                customerLatch.countDown()
            },
        )
        AppActorBridge.setReceiptPipelineListener(
            AppActorSuccessCallback { event ->
                eventCallbackOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                capturedEvent.set(event)
                eventLatch.countDown()
            },
        )

        AppActor.onCustomerInfoChanged?.invoke(
            AppActorCustomerInfo(appUserId = "bridge_user_123"),
        )
        val hashedKey = appActorPublicReceiptId("raw_receipt_key_123")
        AppActor.onReceiptPipelineEvent?.invoke(
            AppActorReceiptPipelineEvent.RetryScheduled(
                key = hashedKey,
                productId = "com.appactor.pro.monthly",
                retryCount = 2,
                nextRetryAtMillis = 1234L,
                errorCode = "RATE_LIMITED",
                appUserId = "bridge_user_123",
            ),
        )

        assertTrue(awaitMainThreadCallback(customerLatch))
        assertTrue(awaitMainThreadCallback(eventLatch))
        assertEquals("bridge_user_123", capturedUserId.get())
        assertEquals(true, customerCallbackOnMain.get())
        assertEquals(AppActorBridgeReceiptEvent.TYPE_RETRY_SCHEDULED, capturedEvent.get()?.type)
        assertEquals("com.appactor.pro.monthly", capturedEvent.get()?.productId)
        assertEquals(2, capturedEvent.get()?.retryCount)
        assertTrue(capturedEvent.get()?.nextAttemptAt != null)
        assertEquals("RATE_LIMITED", capturedEvent.get()?.errorCode)
        assertNull(capturedEvent.get()?.transactionId)
        assertNull(capturedEvent.get()?.key)
        assertEquals(true, eventCallbackOnMain.get())
    }

    @Test
    fun `clear listeners removes bridge listeners`() {
        AppActorBridge.setCustomerInfoListener(AppActorSuccessCallback { })
        AppActorBridge.setReceiptPipelineListener(AppActorSuccessCallback { })

        AppActorBridge.clearListeners()

        assertNull(AppActor.onCustomerInfoChanged)
        assertNull(AppActor.onReceiptPipelineEvent)
    }
}
