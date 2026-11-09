package net.plumt.ads

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

class UsableNetworksTest {
    @Test fun initialValueUntilAnyCallback() {
        assertTrue(UsableNetworks(initial = true).online())
        assertFalse(UsableNetworks(initial = false).online())
    }

    @Test fun lostNetworkIsExcludedEvenIfItWasTheOnlyOne() {
        val t = UsableNetworks(initial = true)
        assertTrue(t.update("wifi", usable = true))
        // 잃은 망을 빼고 판정한다 — activeNetwork 가 아직 그 망을 가리켜도 오프라인이다
        assertFalse(t.lost("wifi"))
    }

    @Test fun anotherUsableNetworkKeepsOnline() {
        val t = UsableNetworks(initial = true)
        t.update("wifi", true)
        t.update("cell", true)
        assertTrue(t.lost("wifi"))
        assertFalse(t.lost("cell"))
    }

    @Test fun unvalidatedNetworkIsNotOnline() {
        val t = UsableNetworks(initial = true)
        assertFalse(t.update("captive", usableCapabilities(internet = true, validated = false)))
        assertTrue(t.update("captive", usableCapabilities(internet = true, validated = true)))
        assertFalse(t.update("captive", usableCapabilities(internet = false, validated = true)))
    }

    @Test fun unavailableWithoutKnownNetworkIsOffline() {
        assertFalse(UsableNetworks(initial = true).unavailable())
        val t = UsableNetworks(initial = false)
        t.update("wifi", true)
        assertTrue(t.unavailable())
    }

    @Test fun afterAnyCallbackInitialIsIgnored() {
        val t = UsableNetworks(initial = true)
        // 콜백이 온 뒤로는 초기값을 쓰지 않는다
        assertFalse(t.lost("unknown"))
    }
}

/** 시스템 연결 관리자 콜백 순서 그대로 — onLost 시점에 activeNetwork 가 아직 잃은 망이어도 false 가 와야 한다. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 33, 35])
class SystemConnectivityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private fun caps(validated: Boolean): NetworkCapabilities {
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (validated) shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return caps
    }

    @Test fun lostWhileActiveNetworkStillPointsToItEmitsOffline() = runTest(UnconfinedTestDispatcher()) {
        val values = mutableListOf<Boolean>()
        val job = launch { AdConnectivity.system(context).online().collect { values += it } }
        val callback = shadowOf(cm).networkCallbacks.single()
        val wifi = ShadowNetwork.newInstance(7)

        callback.onCapabilitiesChanged(wifi, caps(validated = true))
        assertEquals(true, values.last())
        // activeNetwork 는 건드리지 않는다(시스템이 아직 갱신하지 않은 경합 상태)
        callback.onLost(wifi)
        assertEquals(false, values.last())

        callback.onCapabilitiesChanged(wifi, caps(validated = false))
        assertEquals(false, values.last())
        callback.onCapabilitiesChanged(wifi, caps(validated = true))
        assertEquals(true, values.last())
        job.cancel()
    }

    @Test fun unsubscribingUnregistersCallback() = runTest(UnconfinedTestDispatcher()) {
        val job = launch { AdConnectivity.system(context).online().collect {} }
        assertEquals(1, shadowOf(cm).networkCallbacks.size)
        job.cancel()
        assertEquals(0, shadowOf(cm).networkCallbacks.size)
    }
}
