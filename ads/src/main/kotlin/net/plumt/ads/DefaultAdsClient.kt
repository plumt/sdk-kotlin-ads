package net.plumt.ads

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.plumt.ads.consent.ConsentGateway
import net.plumt.ads.consent.ConsentManager
import net.plumt.ads.consent.ConsentSnapshot
import net.plumt.ads.consent.DefaultConsentManager
import net.plumt.ads.consent.UmpConsentGateway
import net.plumt.ads.network.AdNetwork

internal class DefaultAdsClient(
    private val context: Context,
    override val config: AdsConfig,
    enabledSource: AdsEnabledSource,
    override val events: AdEventListener,
    override val network: AdNetwork,
    gateway: ConsentGateway,
    private val clock: AdClock,
    private val scope: CoroutineScope,
) : AdsClient {

    private val consentManager = DefaultConsentManager(gateway)
    override val consent: ConsentManager = consentManager

    override val adsEnabled: StateFlow<Boolean> =
        enabledSource.enabled.stateIn(scope, SharingStarted.Eagerly, false)

    private val initialized = MutableStateFlow(false)
    private val initError = MutableStateFlow<AdsException?>(null)

    override val initializationError: StateFlow<AdsException?> = initError.asStateFlow()
    private var initializing = false

    override val adsAvailable: StateFlow<Boolean> =
        combine(adsEnabled, consent.snapshot, initialized) { enabled, snap, init ->
            enabled && snap.canRequestAds && init
        }.stateIn(scope, SharingStarted.Eagerly, false)

    init {
        // 스위치가 나중에 켜지면 그때 초기화한다. 꺼져 있는 동안은 SDK 도 건드리지 않는다
        scope.launch {
            combine(adsEnabled, consent.snapshot) { enabled, snap -> enabled && snap.canRequestAds }
                .collect { ok -> if (ok) initializeNow() }
        }
    }

    override fun initializeIfConsented() {
        consentManager.refreshFromStorage()
    }

    override suspend fun gatherConsent(activity: Activity) {
        // 광고를 끈 사용자에게 동의 폼을 띄우지 않는다
        if (!adsEnabled.value) return
        consentManager.gather(activity)
    }

    private fun initializeNow() {
        if (initialized.value || initializing) return
        initializing = true
        scope.launch {
            try {
                network.initialize(context, config)
                initialized.value = true
                initError.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: AdsException) {
                // 다음 동의·스위치 변화 때 다시 시도한다. 호출부는 initializationError 로 안다
                initError.value = e
            } finally {
                initializing = false
            }
        }
    }

    override fun fullScreenController(
        format: AdFormat,
        placement: String,
        adUnitId: String,
        policy: AdFrequencyPolicy,
        preload: PreloadPolicy,
    ): FullScreenAdController {
        val provider = when (format) {
            AdFormat.Interstitial -> network.interstitial
            AdFormat.Rewarded -> network.rewarded
            AdFormat.AppOpen -> network.appOpen
            else -> throw IllegalArgumentException("전면형 형식이 아닙니다: $format (Interstitial·Rewarded·AppOpen 만 허용)")
        }
        require(placement.isNotBlank()) { "placement 가 비었습니다" }
        require(adUnitId.isNotBlank()) { "adUnitId 가 비었습니다: placement=$placement" }
        return FullScreenAdController(
            format, placement, adUnitId, context, provider, policy, preload, adsAvailable, events, clock, scope,
        )
    }

    override fun close() = scope.cancel()

    companion object {
        fun create(
            context: Context,
            config: AdsConfig,
            enabledSource: AdsEnabledSource,
            events: AdEventListener,
            network: AdNetwork,
        ): AdsClient = DefaultAdsClient(
            context, config, enabledSource, events, network,
            UmpConsentGateway(context, config), AdClock.Default, defaultScope(),
        )
    }
}
