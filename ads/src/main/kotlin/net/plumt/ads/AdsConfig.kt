package net.plumt.ads

import net.plumt.ads.network.AdNetwork

/**
 * 동의(UMP) 흐름을 시험할 때 쓰는 가짜 지역. 운영 빌드에서는 [Disabled] 로 둔다.
 * UMP 가 지역을 더할 수 있어 열거형이 아니다 — 앱의 `when` 에는 `else` 를 둔다.
 */
public class ConsentDebugGeography private constructor(
    /** 상수 이름. 로그·분석에 쓴다. */
    public val name: String,
) {
    override fun toString(): String = name

    public companion object {
        private val all = ArrayList<ConsentDebugGeography>()
        private fun of(name: String) = ConsentDebugGeography(name).also { all += it }

        /** 실제 지역을 쓴다. */
        @JvmField public val Disabled: ConsentDebugGeography = of("Disabled")

        /** 유럽 경제 지역(EEA)인 것처럼 동작한다. */
        @JvmField public val Eea: ConsentDebugGeography = of("Eea")

        /** EEA 가 아닌 것처럼 동작한다(UMP 의 `DEBUG_GEOGRAPHY_OTHER`). */
        @JvmField public val NotEea: ConsentDebugGeography = of("NotEea")

        /** 개인정보 규제가 있는 미국 주인 것처럼 동작한다. */
        @JvmField public val RegulatedUsState: ConsentDebugGeography = of("RegulatedUsState")

        /** 정의된 모든 값(정의 순서). */
        public val values: List<ConsentDebugGeography> get() = all.toList()
    }
}

/**
 * 광고 계층 설정. 불변이고 [builder]·[adsConfig] 로 만든다. 정하지 않은 값은 기본값이다.
 * 광고 단위 ID 는 여기 두지 않는다 — 호출부가 화면·배치마다 [AdUnit] 으로 넘긴다.
 *
 * ```kotlin
 * val config = adsConfig {
 *     audience(AdAudience.forTarget(AdTarget.Teen))
 *     enabledSource(removeAdsSource)
 *     units(AppAdUnits.registry)
 * }
 * val ads = AdsClient.create(applicationContext, config)
 * ```
 *
 * @property mode 개발·운영 모드. 기본 [AdsMode.Auto] 는 앱의 디버그 가능 여부로 정한다
 * @property audience 대상 연령 설정. 기본 [AdAudience.Default]
 * @property testDeviceIds 시험 기기 ID. SDK 로그에 `Use RequestConfiguration.Builder.setTestDeviceIds` 로 찍히는 값.
 *   시험 기기에서는 실제 광고 단위도 시험 광고가 나온다. **개발 모드에서만 적용한다** — 운영 모드에서는 무시하고 [AdsWarning] 으로 알린다
 * @property consentDebugGeography 동의 폼 시험용 가짜 지역. [testDeviceIds] 에 든 기기에서만 효과가 있고, 개발 모드에서만 적용한다
 * @property appOpenCooldownMillis 다른 전면형 광고(전면·보상형)가 닫힌 뒤 앱 오프닝 광고를 막는 시간. 광고를 연달아 보이지 않게 한다
 * @property appOpenMinIntervalMillis 앱 오프닝 기본 정책의 최소 표시 간격
 * @property appOpenSkipFirstLaunches 앱 오프닝 기본 정책이 광고를 보이지 않는 처음 실행 수. 0 이면 첫 실행부터 보인다
 * @property enabledSource 광고 끄기 스위치. 기본 [AdsEnabledSource.Always]
 * @property events 분석 훅. 기본 [AdEventListener.None]. 앱 수명 동안 살아 있다
 * @property network 다른 네트워크를 쓸 때 교체한다. `null`(기본)이면 [net.plumt.ads.mobileads.MobileAdsNetwork]
 * @property frequencyStore 기본 빈도 정책이 표시 기록을 남기는 곳. `null`(기본)이면 앱 전용 SharedPreferences
 *   ([AdFrequencyStore.sharedPreferences])
 * @property units 광고 단위 등록부. 운영 모드면 [AdsClient.create] 때 바로 검사해 [AdsClient.configurationErrors] 를 채운다
 * @property connectivity 연결 상태. `null`(기본)이면 시스템 연결 관리자([AdConnectivity.system])
 */
public class AdsConfig private constructor(builder: Builder) {
    public val mode: AdsMode = builder.mode
    public val audience: AdAudience = builder.audience
    public val testDeviceIds: List<String> = builder.testDeviceIds.toList()
    public val consentDebugGeography: ConsentDebugGeography = builder.consentDebugGeography
    public val appOpenCooldownMillis: Long = builder.appOpenCooldownMillis
    public val appOpenMinIntervalMillis: Long = builder.appOpenMinIntervalMillis
    public val appOpenSkipFirstLaunches: Int = builder.appOpenSkipFirstLaunches
    public val enabledSource: AdsEnabledSource = builder.enabledSource
    public val events: AdEventListener = builder.events
    public val network: AdNetwork? = builder.network
    public val frequencyStore: AdFrequencyStore? = builder.frequencyStore
    public val units: AdUnitRegistry = builder.units
    public val connectivity: AdConnectivity? = builder.connectivity

    /** 모두 기본값인 설정. */
    public constructor() : this(Builder())

    /** 이 값에서 시작하는 빌더. 일부만 바꿀 때 쓴다. */
    public fun toBuilder(): Builder = Builder().also { b ->
        b.mode = mode
        b.audience = audience
        b.testDeviceIds = testDeviceIds
        b.consentDebugGeography = consentDebugGeography
        b.appOpenCooldownMillis = appOpenCooldownMillis
        b.appOpenMinIntervalMillis = appOpenMinIntervalMillis
        b.appOpenSkipFirstLaunches = appOpenSkipFirstLaunches
        b.enabledSource = enabledSource
        b.events = events
        b.network = network
        b.frequencyStore = frequencyStore
        b.units = units
        b.connectivity = connectivity
    }

    private fun parts(): List<Any?> = listOf(
        mode, audience, testDeviceIds, consentDebugGeography, appOpenCooldownMillis, appOpenMinIntervalMillis,
        appOpenSkipFirstLaunches, enabledSource, events, network, frequencyStore, units, connectivity,
    )

    override fun equals(other: Any?): Boolean = other is AdsConfig && parts() == other.parts()

    override fun hashCode(): Int = parts().hashCode()

    override fun toString(): String =
        "AdsConfig(mode=$mode, audience=$audience, testDeviceIds=${testDeviceIds.size}, consentDebugGeography=$consentDebugGeography, " +
            "appOpenCooldownMillis=$appOpenCooldownMillis, appOpenMinIntervalMillis=$appOpenMinIntervalMillis, " +
            "appOpenSkipFirstLaunches=$appOpenSkipFirstLaunches, units=${units.all.map { it.name }}, " +
            "network=${network?.javaClass?.simpleName ?: "default"})"

    /** [AdsConfig] 를 만든다. 스레드 안전하지 않다. */
    public class Builder internal constructor() {
        internal var mode: AdsMode = AdsMode.Auto
        internal var audience: AdAudience = AdAudience.Default
        internal var testDeviceIds: List<String> = emptyList()
        internal var consentDebugGeography: ConsentDebugGeography = ConsentDebugGeography.Disabled
        internal var appOpenCooldownMillis: Long = DEFAULT_APP_OPEN_COOLDOWN_MILLIS
        internal var appOpenMinIntervalMillis: Long = DEFAULT_APP_OPEN_MIN_INTERVAL_MILLIS
        internal var appOpenSkipFirstLaunches: Int = DEFAULT_APP_OPEN_SKIP_FIRST_LAUNCHES
        internal var enabledSource: AdsEnabledSource = AdsEnabledSource.Always
        internal var events: AdEventListener = AdEventListener.None
        internal var network: AdNetwork? = null
        internal var frequencyStore: AdFrequencyStore? = null
        internal var units: AdUnitRegistry = AdUnitRegistry.Empty
        internal var connectivity: AdConnectivity? = null

        /** [AdsConfig.mode] */
        public fun mode(mode: AdsMode): Builder = apply { this.mode = mode }

        /** [AdsConfig.audience] */
        public fun audience(audience: AdAudience): Builder = apply { this.audience = audience }

        /** [AdsConfig.testDeviceIds] */
        public fun testDeviceIds(ids: List<String>): Builder = apply { testDeviceIds = ids.toList() }

        /** [AdsConfig.consentDebugGeography] */
        public fun consentDebugGeography(geography: ConsentDebugGeography): Builder = apply { consentDebugGeography = geography }

        /** [AdsConfig.appOpenCooldownMillis] */
        public fun appOpenCooldownMillis(millis: Long): Builder = apply { appOpenCooldownMillis = millis }

        /** [AdsConfig.appOpenMinIntervalMillis] */
        public fun appOpenMinIntervalMillis(millis: Long): Builder = apply { appOpenMinIntervalMillis = millis }

        /** [AdsConfig.appOpenSkipFirstLaunches] */
        public fun appOpenSkipFirstLaunches(count: Int): Builder = apply { appOpenSkipFirstLaunches = count }

        /** [AdsConfig.enabledSource] */
        public fun enabledSource(source: AdsEnabledSource): Builder = apply { enabledSource = source }

        /** [AdsConfig.events] */
        public fun events(listener: AdEventListener): Builder = apply { events = listener }

        /** [AdsConfig.network] */
        public fun network(network: AdNetwork): Builder = apply { this.network = network }

        /** [AdsConfig.frequencyStore] */
        public fun frequencyStore(store: AdFrequencyStore): Builder = apply { frequencyStore = store }

        /** [AdsConfig.units] */
        public fun units(units: AdUnitRegistry): Builder = apply { this.units = units }

        /** [AdsConfig.connectivity] */
        public fun connectivity(connectivity: AdConnectivity): Builder = apply { this.connectivity = connectivity }

        /**
         * @throws IllegalArgumentException [testDeviceIds] 에 빈 문자열이 있거나 시간·횟수 값이 음수일 때
         */
        public fun build(): AdsConfig {
            require(testDeviceIds.none { it.isBlank() }) { "testDeviceIds 에 빈 문자열이 있습니다: $testDeviceIds" }
            require(appOpenCooldownMillis >= 0) { "appOpenCooldownMillis 는 0 이상이어야 합니다: $appOpenCooldownMillis" }
            require(appOpenMinIntervalMillis >= 0) { "appOpenMinIntervalMillis 는 0 이상이어야 합니다: $appOpenMinIntervalMillis" }
            require(appOpenSkipFirstLaunches >= 0) { "appOpenSkipFirstLaunches 는 0 이상이어야 합니다: $appOpenSkipFirstLaunches" }
            return AdsConfig(this)
        }
    }

    public companion object {
        /** 기본 앱 오프닝 대기: 60초. */
        public const val DEFAULT_APP_OPEN_COOLDOWN_MILLIS: Long = 60_000L

        /** 기본 앱 오프닝 최소 간격: 2분. */
        public const val DEFAULT_APP_OPEN_MIN_INTERVAL_MILLIS: Long = 120_000L

        /** 기본으로 앱 오프닝을 보이지 않는 처음 실행 수: 1(첫 실행). */
        public const val DEFAULT_APP_OPEN_SKIP_FIRST_LAUNCHES: Int = 1

        /** 빌더. */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}

/**
 * Kotlin DSL: `adsConfig { mode(AdsMode.Development); units(registry) }`
 *
 * @throws IllegalArgumentException [AdsConfig.Builder.build] 와 같다
 */
public fun adsConfig(block: AdsConfig.Builder.() -> Unit): AdsConfig = AdsConfig.builder().apply(block).build()
