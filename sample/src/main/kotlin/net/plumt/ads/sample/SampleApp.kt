package net.plumt.ads.sample

import android.app.Application
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.plumt.ads.AdEventListener
import net.plumt.ads.AdUnit
import net.plumt.ads.AdUnitRegistry
import net.plumt.ads.AdsClient
import net.plumt.ads.AdsMode
import net.plumt.ads.ConsentGatherResult
import net.plumt.ads.MutableAdsEnabledSource
import net.plumt.ads.TestAdIds
import net.plumt.ads.adsConfig

/** 확인용 광고 단위. 운영 ID 는 비워 두고 개발 모드에서 형식별 Google 시험 ID 를 쓴다. */
object SampleUnits {
    val banner = AdUnit.banner("sample_banner", "")
    val interstitial = AdUnit.interstitial("sample_interstitial", "")
    val rewarded = AdUnit.rewarded("sample_rewarded", "")
    val appOpen = AdUnit.appOpen("sample_app_open", "")
    val native = AdUnit.native("sample_native", "")
    val nativeVideo = AdUnit.native("sample_native_video", "", TestAdIds.NATIVE_VIDEO)
    val exit = AdUnit.native("sample_exit", "")
    val registry = AdUnitRegistry(banner, interstitial, rewarded, appOpen, native, nativeVideo, exit)
}

class SampleApp : Application() {
    lateinit var ads: AdsClient
        private set

    /** 광고 끄기 스위치. 화면의 스위치가 바꾼다. */
    val adsSwitch = MutableAdsEnabledSource(true)

    private val log = MutableStateFlow<List<String>>(emptyList())

    /** 최근 사건(새 것이 위). */
    val eventLog: StateFlow<List<String>> = log.asStateFlow()

    private val consentResult = MutableStateFlow<ConsentGatherResult?>(null)

    /** 첫 화면 동의 수집 결과. */
    val firstConsent: StateFlow<ConsentGatherResult?> = consentResult.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        ads = AdsClient.create(
            this,
            adsConfig {
                // 확인용 앱은 release 로 설치해도 시험 광고만 요청한다
                mode(AdsMode.Development)
                enabledSource(adsSwitch)
                units(SampleUnits.registry)
                events(AdEventListener { event -> record(event.toString()) })
            },
        )
        ads.gatherConsentOnFirstResume(this) { result ->
            consentResult.value = result
            record("첫 화면 동의: $result")
        }
    }

    fun record(line: String) {
        Log.i("AdsSample", line)
        log.value = (listOf(line) + log.value).take(MAX_LOG)
    }

    private companion object {
        const val MAX_LOG = 30
    }
}
