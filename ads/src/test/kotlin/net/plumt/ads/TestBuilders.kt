package net.plumt.ads

import android.view.View
import net.plumt.ads.network.NativeAdViews

// 테스트를 짧게 쓰려고 빌더를 이름 붙은 인자로 감싼다. 공개 API 는 빌더·오버로드다(기본값 인자는 바이너리 호환을 깬다)

@Suppress("LongParameterList")
fun config(
    mode: AdsMode = AdsMode.Auto,
    audience: AdAudience = AdAudience.Default,
    testDeviceIds: List<String> = emptyList(),
    consentDebugGeography: ConsentDebugGeography = ConsentDebugGeography.Disabled,
    appOpenCooldownMillis: Long = AdsConfig.DEFAULT_APP_OPEN_COOLDOWN_MILLIS,
    appOpenMinIntervalMillis: Long = AdsConfig.DEFAULT_APP_OPEN_MIN_INTERVAL_MILLIS,
    appOpenSkipFirstLaunches: Int = AdsConfig.DEFAULT_APP_OPEN_SKIP_FIRST_LAUNCHES,
): AdsConfig = AdsConfig.builder()
    .mode(mode).audience(audience).testDeviceIds(testDeviceIds).consentDebugGeography(consentDebugGeography)
    .appOpenCooldownMillis(appOpenCooldownMillis).appOpenMinIntervalMillis(appOpenMinIntervalMillis)
    .appOpenSkipFirstLaunches(appOpenSkipFirstLaunches)
    .build()

@Suppress("LongParameterList")
fun preload(
    autoPreload: Boolean = true,
    reloadAfterDismiss: Boolean = true,
    maxRetries: Int = 2,
    retryBackoffMillis: Long = 5_000L,
    expireAfterMillis: Long = 3_600_000L,
    noFillBackoffMillis: Long = 60_000L,
    retryOnNetworkRestore: Boolean = true,
    loadTimeoutMillis: Long = 30_000L,
): PreloadPolicy = PreloadPolicy.builder()
    .autoPreload(autoPreload).reloadAfterDismiss(reloadAfterDismiss).maxRetries(maxRetries)
    .retryBackoffMillis(retryBackoffMillis).expireAfterMillis(expireAfterMillis).noFillBackoffMillis(noFillBackoffMillis)
    .retryOnNetworkRestore(retryOnNetworkRestore).loadTimeoutMillis(loadTimeoutMillis)
    .build()

@Suppress("LongParameterList")
fun interval(
    minIntervalMillis: Long = IntervalFrequencyPolicy.DEFAULT_MIN_INTERVAL_MILLIS,
    initialDelayMillis: Long = IntervalFrequencyPolicy.DEFAULT_INITIAL_DELAY_MILLIS,
    maxShows: Int = Int.MAX_VALUE,
    clock: AdClock = AdClock.Wall,
    store: AdFrequencyStore = AdFrequencyStore.inMemory(),
    key: String = IntervalFrequencyPolicy.DEFAULT_KEY,
): IntervalFrequencyPolicy = IntervalFrequencyPolicy.builder()
    .minIntervalMillis(minIntervalMillis).initialDelayMillis(initialDelayMillis).maxShows(maxShows)
    .clock(clock).store(store).key(key)
    .build()

@Suppress("LongParameterList")
fun views(
    headline: View? = null,
    body: View? = null,
    callToAction: View? = null,
    advertiser: View? = null,
    price: View? = null,
    store: View? = null,
    starRating: View? = null,
    icon: View? = null,
    media: View? = null,
): NativeAdViews = NativeAdViews.builder()
    .headline(headline).body(body).callToAction(callToAction).advertiser(advertiser).price(price).store(store)
    .starRating(starRating).icon(icon).media(media)
    .build()

/** 0.0.x 형태의 이름 붙은 인자로 [AdsClient.fullScreenController] 를 부른다. */
@Suppress("LongParameterList")
fun AdsClient.fullScreenController(
    unit: AdUnit,
    placement: String = unit.name,
    policy: AdFrequencyPolicy? = null,
    preload: PreloadPolicy = PreloadPolicy.Default,
    fallbacks: List<AdUnit> = emptyList(),
    frequencyScope: FrequencyScope = FrequencyScope.PerFormat,
): FullScreenAdController = fullScreenController(
    unit,
    FullScreenAdOptions.builder().placement(placement).preload(preload).fallbacks(fallbacks).frequencyScope(frequencyScope)
        .apply { policy?.let(::policy) }.build(),
)

/** 분류를 정한 [AdsException]. */
fun adsError(kind: AdsErrorKind, code: Int, message: String, reason: AdErrorReason, cause: Throwable? = null): AdsException =
    AdsException(kind, code, message, cause, reason)
