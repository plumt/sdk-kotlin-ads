package net.plumt.ads

/**
 * Google 이 공개한 시험용 광고 ID. 어느 앱에서 써도 시험 광고만 오고 수익·무효 트래픽 문제가 없다.
 * 값의 출처는 Google Mobile Ads SDK 공식 문서의 "시험 광고 사용" 절이다.
 */
public object TestAdIds {
    /** 시험 ID 의 게시자 번호 부분. 이것으로 시작하면 시험 ID 다. */
    public const val PUBLISHER_PREFIX: String = "ca-app-pub-3940256099942544"

    /** 시험용 앱 ID(매니페스트 `com.google.android.gms.ads.APPLICATION_ID`). */
    public const val APP_ID: String = "ca-app-pub-3940256099942544~3347511713"

    /** 적응형 배너. */
    public const val BANNER: String = "ca-app-pub-3940256099942544/9214589741"

    /** 전면. */
    public const val INTERSTITIAL: String = "ca-app-pub-3940256099942544/1033173712"

    /** 보상형. */
    public const val REWARDED: String = "ca-app-pub-3940256099942544/5224354917"

    /** 앱 오프닝. */
    public const val APP_OPEN: String = "ca-app-pub-3940256099942544/9257395921"

    /** 네이티브(이미지). */
    public const val NATIVE: String = "ca-app-pub-3940256099942544/2247696110"

    /** 네이티브(동영상). 미디어 뷰가 있는 레이아웃을 시험할 때 쓴다. */
    public const val NATIVE_VIDEO: String = "ca-app-pub-3940256099942544/1044960115"

    /** [id] 가 Google 시험 ID(앱 ID 포함)인가. 앞뒤 공백은 무시한다. */
    public fun isTestId(id: String): Boolean = id.trim().startsWith(PUBLISHER_PREFIX)

    /** [format] 의 기본 시험 단위 ID. */
    public fun forFormat(format: AdFormat): String = format.testUnitId
}

/**
 * 이름 붙은 광고 단위 하나: 이름, 형식, 운영 ID, 개발 ID. 클라이언트가 [AdsClient.mode] 에 맞는 ID 를 고른다.
 * 같은 형식의 단위가 여러 개여도(배너 1·2·3) 서로 독립이다 — 빈도 제한을 단위별로 둘지 형식별로 공유할지는
 * [FrequencyScope] 로 고른다. 앱은 단위를 한 곳([AdUnitRegistry])에 모아 두고 화면에 넘긴다.
 *
 * ```
 * object AppAdUnits {
 *     val homeBanner = AdUnit.banner("home_banner", production = BuildConfig.HOME_BANNER_ID)   // 개발 ID 는 시험 ID 가 기본
 *     val listNative = AdUnit.native("list_native", production = BuildConfig.LIST_NATIVE_ID)
 *     val registry = AdUnitRegistry(homeBanner, listNative)
 * }
 * ```
 *
 * @property name 앱 안에서 유일한 이름. 분석 사건·오류 메시지·단위별 빈도 기록 키에 쓰인다
 * @property production 운영 ID. 개발만 하는 동안은 비워 둬도 된다 — 운영 모드에서 비어 있으면 그 광고를 요청하지 않고 실패로 알린다
 * @property development 개발 ID. 생략하면 [TestAdIds.forFormat]
 * @throws IllegalArgumentException [name]·[development] 가 비었을 때
 */
public class AdUnit(
    public val name: String,
    public val format: AdFormat,
    public val production: String,
    public val development: String,
) {
    /** 개발 ID 를 [TestAdIds.forFormat] 으로 둔다. */
    public constructor(name: String, format: AdFormat, production: String) :
        this(name, format, production, TestAdIds.forFormat(format))

    init {
        require(name.isNotBlank()) { "name 이 비었습니다: format=$format" }
        require(development.isNotBlank()) { "development 가 비었습니다: name=$name" }
    }

    /**
     * [mode] 에서 쓸 ID.
     *
     * @throws IllegalArgumentException [mode] 가 [AdsMode.Auto] 일 때(클라이언트가 정한 [AdsClient.mode] 를 넘긴다)
     * @throws AdsException 운영 모드인데 운영 ID 가 비었거나([AdsException.CODE_PRODUCTION_ID_MISSING])
     *   시험 ID·개발 ID 와 같을 때([AdsException.CODE_TEST_ID_IN_PRODUCTION]). 종류는 [AdsErrorKind.Configuration],
     *   분류는 [AdErrorReason.Misconfigured]
     */
    public fun idFor(mode: AdsMode): String {
        require(mode != AdsMode.Auto) { "정하지 않은 모드입니다: $mode (AdsClient.mode 를 넘기세요)" }
        if (mode == AdsMode.Development) return development.trim()
        val id = production.trim()
        if (id.isEmpty()) {
            throw AdsException(
                AdsErrorKind.Configuration, AdsException.CODE_PRODUCTION_ID_MISSING,
                "운영 모드인데 운영 광고 단위 ID 가 비었습니다: name=$name, format=$format",
                null, AdErrorReason.Misconfigured,
            )
        }
        if (TestAdIds.isTestId(id) || id == development.trim()) {
            throw AdsException(
                AdsErrorKind.Configuration, AdsException.CODE_TEST_ID_IN_PRODUCTION,
                "운영 모드인데 운영 광고 단위 ID 가 시험 ID 입니다: name=$name, format=$format, id=$id",
                null, AdErrorReason.Misconfigured,
            )
        }
        return id
    }

    /** [mode] 에서 이 단위가 맞지 않으면 그 원인, 맞으면 null. [idFor] 와 같은 판정이다. */
    public fun problemFor(mode: AdsMode): AdsException? = try {
        idFor(mode)
        null
    } catch (e: AdsException) {
        e
    }

    /**
     * 이 단위가 [expected] 형식인지 확인한다. 컨트롤러·Compose 컴포넌트가 받는 즉시 부른다.
     *
     * @throws IllegalArgumentException 형식이 다를 때
     */
    public fun requireFormat(vararg expected: AdFormat): AdUnit = apply {
        require(format in expected) {
            "형식이 맞지 않는 광고 단위입니다: name=$name, format=$format (허용: ${expected.joinToString("·")})"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is AdUnit && name == other.name && format == other.format && production == other.production &&
            development == other.development

    override fun hashCode(): Int = ((name.hashCode() * 31 + format.hashCode()) * 31 + production.hashCode()) * 31 + development.hashCode()

    override fun toString(): String = "AdUnit(name=$name, format=$format, production=$production, development=$development)"

    public companion object {
        /** 배너 단위. 개발 ID 는 [TestAdIds.BANNER]. */
        @JvmStatic
        public fun banner(name: String, production: String): AdUnit = AdUnit(name, AdFormat.Banner, production)

        /** 배너 단위. */
        @JvmStatic
        public fun banner(name: String, production: String, development: String): AdUnit =
            AdUnit(name, AdFormat.Banner, production, development)

        /** 네이티브 단위. 개발 ID 는 [TestAdIds.NATIVE]. */
        @JvmStatic
        public fun native(name: String, production: String): AdUnit = AdUnit(name, AdFormat.Native, production)

        /** 네이티브 단위. */
        @JvmStatic
        public fun native(name: String, production: String, development: String): AdUnit =
            AdUnit(name, AdFormat.Native, production, development)

        /** 전면 단위. 개발 ID 는 [TestAdIds.INTERSTITIAL]. */
        @JvmStatic
        public fun interstitial(name: String, production: String): AdUnit = AdUnit(name, AdFormat.Interstitial, production)

        /** 전면 단위. */
        @JvmStatic
        public fun interstitial(name: String, production: String, development: String): AdUnit =
            AdUnit(name, AdFormat.Interstitial, production, development)

        /** 보상형 단위. 개발 ID 는 [TestAdIds.REWARDED]. */
        @JvmStatic
        public fun rewarded(name: String, production: String): AdUnit = AdUnit(name, AdFormat.Rewarded, production)

        /** 보상형 단위. */
        @JvmStatic
        public fun rewarded(name: String, production: String, development: String): AdUnit =
            AdUnit(name, AdFormat.Rewarded, production, development)

        /** 앱 오프닝 단위. 개발 ID 는 [TestAdIds.APP_OPEN]. */
        @JvmStatic
        public fun appOpen(name: String, production: String): AdUnit = AdUnit(name, AdFormat.AppOpen, production)

        /** 앱 오프닝 단위. */
        @JvmStatic
        public fun appOpen(name: String, production: String, development: String): AdUnit =
            AdUnit(name, AdFormat.AppOpen, production, development)
    }
}

/**
 * 앱의 광고 단위 등록부. 이름으로 찾고, [AdsConfig.units] 로 넘기면 운영 모드에서 만들 때 한 번에 검사해
 * 잘못된 단위를 [AdsClient.configurationErrors] 에 미리 올린다(화면에 가기 전에 안다).
 *
 * @throws IllegalArgumentException 이름이 겹칠 때
 */
public class AdUnitRegistry(units: List<AdUnit>) {
    public constructor(vararg units: AdUnit) : this(units.toList())

    /** 등록 순서대로의 단위. */
    public val all: List<AdUnit> = units.toList()

    private val byName: Map<String, AdUnit> = LinkedHashMap<String, AdUnit>().also { map ->
        all.forEach { unit ->
            require(map.put(unit.name, unit) == null) { "광고 단위 이름이 겹칩니다: ${unit.name}" }
        }
    }

    /** 이름으로 찾는다. 없으면 null. */
    public fun find(name: String): AdUnit? = byName[name]

    /**
     * 이름으로 찾는다.
     *
     * @throws IllegalArgumentException 등록되지 않은 이름일 때
     */
    public operator fun get(name: String): AdUnit =
        requireNotNull(byName[name]) { "등록되지 않은 광고 단위입니다: $name (등록: ${byName.keys.joinToString()})" }

    /** [format] 형식의 단위들(등록 순서). */
    public fun ofFormat(format: AdFormat): List<AdUnit> = all.filter { it.format == format }

    /** [mode] 에서 맞지 않는 단위의 원인들. 비었으면 모두 정상이다. 앱 단위 테스트에서 운영 설정을 검사할 때도 쓴다. */
    public fun problemsFor(mode: AdsMode): List<AdsException> = all.mapNotNull { it.problemFor(mode) }

    public companion object {
        /** 빈 등록부. */
        @JvmField public val Empty: AdUnitRegistry = AdUnitRegistry(emptyList())
    }
}
