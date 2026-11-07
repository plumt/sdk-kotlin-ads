package net.plumt.ads

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import net.plumt.ads.consent.ConsentGateway
import net.plumt.ads.consent.ConsentSnapshot
import net.plumt.ads.network.BannerProvider
import net.plumt.ads.network.AdNetwork
import net.plumt.ads.network.FullScreenAd
import net.plumt.ads.network.FullScreenAdCallbacks
import net.plumt.ads.network.FullScreenAdProvider
import android.view.ViewGroup
import net.plumt.ads.network.NativeAdAssets
import net.plumt.ads.network.NativeAdHandle
import net.plumt.ads.network.NativeAdProvider
import net.plumt.ads.network.NativeAdViews

class FakeClock(var now: Long = 0L) : AdClock {
    override fun nowMillis(): Long = now
    fun advance(ms: Long) {
        now += ms
    }
}

class FakeAd : FullScreenAd {
    var destroyed = 0
    var shown = 0
    var callbacks: FullScreenAdCallbacks? = null
    var lastActivity: Activity? = null
    override fun show(activity: Activity, callbacks: FullScreenAdCallbacks) {
        shown++
        lastActivity = activity
        this.callbacks = callbacks
    }

    override fun destroy() {
        destroyed++
    }
}

/** 큐에 넣은 결과를 차례로 돌려주는 공급자. [gate] 를 걸면 완료될 때까지 멈춘다. */
class FakeProvider : FullScreenAdProvider {
    val results = ArrayDeque<Any>() // FullScreenAd 또는 Throwable
    var loads = 0
    var gate: CompletableDeferred<Unit>? = null
    val produced = mutableListOf<FakeAd>()

    fun enqueueAd(): FakeAd = FakeAd().also { results.addLast(it); produced += it }
    fun enqueueError(e: Throwable) = results.addLast(e)

    override suspend fun load(context: Context, adUnitId: String): FullScreenAd {
        loads++
        gate?.await()
        return when (val r = results.removeFirstOrNull() ?: FakeAd().also { produced += it }) {
            is Throwable -> throw r
            else -> r as FullScreenAd
        }
    }
}

class FakeGateway(var snapshot: ConsentSnapshot = ConsentSnapshot.Initial) : ConsentGateway {
    var gatherCalls = 0
    var privacyCalls = 0
    var onGather: ((AdsException?) -> Unit) -> Unit = { done -> done(null) }
    var onPrivacy: ((AdsException?) -> Unit) -> Unit = { done -> done(null) }
    override fun current(): ConsentSnapshot = snapshot
    override fun gather(activity: Activity, onUpdateRequested: () -> Unit, onDone: (AdsException?) -> Unit) {
        gatherCalls++
        onUpdateRequested()
        onGather(onDone)
    }

    override fun showPrivacyOptions(activity: Activity, onDone: (AdsException?) -> Unit) {
        privacyCalls++
        onPrivacy(onDone)
    }
}

class FakeNetwork : AdNetwork {
    var initCalls = 0
    var initFailure: Exception? = null
    var lastConfig: AdsConfig? = null
    val nativeProvider = FakeNativeProvider()
    val interstitialProvider = FakeProvider()
    val rewardedProvider = FakeProvider()
    val appOpenProvider = FakeProvider()
    override suspend fun initialize(context: Context, config: AdsConfig) {
        initCalls++
        lastConfig = config
        initFailure?.let { throw it }
    }

    override val interstitial: FullScreenAdProvider = interstitialProvider
    override val rewarded: FullScreenAdProvider = rewardedProvider
    override val appOpen: FullScreenAdProvider = appOpenProvider
    override val banner: BannerProvider = BannerProvider { _, _, _, _ -> error("미사용") }
    override val native: NativeAdProvider = nativeProvider
}

class RecordingEvents : AdEventListener {
    val list = mutableListOf<AdEvent>()
    override fun onEvent(event: AdEvent) {
        list += event
    }

    inline fun <reified T : AdEvent> count() = list.count { it is T }
}

val ALLOW = ConsentSnapshot(net.plumt.ads.consent.ConsentStatus.Obtained, canRequestAds = true, privacyOptionsRequired = true)
val DENY = ConsentSnapshot(net.plumt.ads.consent.ConsentStatus.Required, canRequestAds = false, privacyOptionsRequired = false)

class FakeNativeHandle(val id: String = "") : NativeAdHandle {
    var destroyed = 0
    override val assets = NativeAdAssets(null, null, null, null, null, null, null, null)
    override fun bind(container: ViewGroup, views: NativeAdViews): Boolean = true
    override fun destroy() {
        destroyed++
    }
}

/** 큐에 넣은 결과(목록 또는 Throwable)를 차례로 돌려준다. 비면 요청한 ID 로 한 장. */
class FakeNativeProvider : NativeAdProvider {
    val results = ArrayDeque<Any>()
    val requestedIds = mutableListOf<String>()
    var gate: CompletableDeferred<Unit>? = null
    val produced = mutableListOf<FakeNativeHandle>()

    override suspend fun load(context: Context, adUnitId: String, count: Int): List<NativeAdHandle> {
        requestedIds += adUnitId
        gate?.await()
        val r = results.removeFirstOrNull() ?: listOf(FakeNativeHandle(adUnitId))
        if (r is Throwable) throw r
        @Suppress("UNCHECKED_CAST")
        return (r as List<FakeNativeHandle>).also { produced += it }
    }
}
