package net.plumt.ads

/**
 * 전면형 광고를 보여도 되는 빈도를 정하는 교체 지점. 메인 스레드에서만 불린다.
 *
 * 정책 인스턴스가 곧 빈도 상태다. 화면·컨트롤러마다 새로 만들면 화면 재진입·회전마다 제한이 초기화된다 —
 * 앱에 하나만 두고 공유한다. [AdsClient.defaultPolicy] 가 형식별 공유 인스턴스를 준다.
 * 프로세스 재시작을 넘겨 제한하려면 [AdFrequencyStore] 에 기록하는 정책을 쓴다.
 */
public interface AdFrequencyPolicy {
    /** 지금 [placement] 광고를 보여도 되는가. 표시 기록을 늘리지 않는다. */
    public fun canShow(placement: String): Boolean

    /** 광고가 실제로 화면에 올랐다. 다음 판정에 반영한다. */
    public fun onShown(placement: String)

    public companion object {
        /** 제한 없음. 사용자가 직접 요청하는 보상형 광고에 맞다. */
        public val Unlimited: AdFrequencyPolicy = object : AdFrequencyPolicy {
            override fun canShow(placement: String): Boolean = true
            override fun onShown(placement: String) = Unit
        }

        /** 모든 정책이 허용할 때만 허용한다. 표시 사실은 전부에 알린다. */
        public fun all(vararg policies: AdFrequencyPolicy): AdFrequencyPolicy = object : AdFrequencyPolicy {
            override fun canShow(placement: String): Boolean = policies.all { it.canShow(placement) }
            override fun onShown(placement: String) = policies.forEach { it.onShown(placement) }
        }
    }
}

/**
 * 기본 빈도 제한. 인스턴스 하나가 자기에게 묶인 모든 배치를 합쳐 센다 — 배치마다 따로 세려면 [key] 를 달리한 인스턴스를 만든다.
 *
 * - 만든 시점부터 [initialDelayMillis] 가 지나기 전에는 막는다(앱 시작 직후 전면 광고 방지). 인스턴스(보통 프로세스)마다 다시 센다
 * - 마지막 표시 뒤 [minIntervalMillis] 가 지나기 전에는 막는다. 마지막 표시 시각은 [store] 에 남아, 영속 저장소를 쓰면 프로세스 재시작을 넘어 이어진다
 * - 이 인스턴스의 표시 횟수가 [maxShows] 에 닿으면 인스턴스가 버려질 때까지 막는다(세션 상한, 저장하지 않는다)
 * - [clock] 이 뒤로 가면(기기 시계 변경) 그 시각부터 유예와 간격을 다시 센다 — 일찍 허용하지 않는다
 *
 * 불변 설정은 [builder] 로 정한다. 인자 없는 생성자는 모든 기본값(메모리 저장소, 기기 시계)이다.
 *
 * @property minIntervalMillis 최소 표시 간격. 기본 [DEFAULT_MIN_INTERVAL_MILLIS]
 * @property initialDelayMillis 시작 유예. 기본 [DEFAULT_INITIAL_DELAY_MILLIS]
 * @property maxShows 세션 표시 상한. 기본 `Int.MAX_VALUE`
 * @property key 저장소 안의 키 접두어. 기본 [DEFAULT_KEY]
 */
public class IntervalFrequencyPolicy private constructor(builder: Builder) : AdFrequencyPolicy {
    public val minIntervalMillis: Long = builder.minIntervalMillis
    public val initialDelayMillis: Long = builder.initialDelayMillis
    public val maxShows: Int = builder.maxShows
    public val key: String = builder.key
    private val clock: AdClock = builder.clock
    private val store: AdFrequencyStore = builder.store

    /** 모든 기본값. 저장소는 메모리라 프로세스가 끝나면 기록이 사라진다. */
    public constructor() : this(Builder())

    private val lastShownKey = "$key.last_shown_at"
    private var startedAt = clock.nowMillis()
    private var shows = 0

    /** 이 인스턴스가 기록한 표시 횟수. */
    public val shownCount: Int
        get() = shows

    @Synchronized
    override fun canShow(placement: String): Boolean {
        val now = clock.nowMillis()
        if (shows >= maxShows) return false
        // 시계가 뒤로 갔으면 지금을 기준점으로 삼아 다시 센다 — 일찍 허용하지도, 되돌린 만큼 오래 막지도 않는다
        if (now < startedAt) startedAt = now
        var last = store.getLong(lastShownKey)
        if (last != null && now < last) {
            store.putLong(lastShownKey, now)
            last = now
        }
        if (now - startedAt < initialDelayMillis) return false
        return last == null || now - last >= minIntervalMillis
    }

    @Synchronized
    override fun onShown(placement: String) {
        store.putLong(lastShownKey, clock.nowMillis())
        shows++
    }

    /** [IntervalFrequencyPolicy] 를 만든다. 스레드 안전하지 않다. */
    public class Builder internal constructor() {
        internal var minIntervalMillis: Long = DEFAULT_MIN_INTERVAL_MILLIS
        internal var initialDelayMillis: Long = DEFAULT_INITIAL_DELAY_MILLIS
        internal var maxShows: Int = Int.MAX_VALUE
        internal var clock: AdClock = AdClock.Wall
        internal var store: AdFrequencyStore = InMemoryFrequencyStore()
        internal var key: String = DEFAULT_KEY

        /** 마지막 표시 뒤 이 시간이 지나기 전에는 막는다. */
        public fun minIntervalMillis(value: Long): Builder = apply { minIntervalMillis = value }

        /** 만든 시점부터 이 시간이 지나기 전에는 막는다. */
        public fun initialDelayMillis(value: Long): Builder = apply { initialDelayMillis = value }

        /** 이 인스턴스의 표시 횟수 상한. */
        public fun maxShows(value: Int): Builder = apply { maxShows = value }

        /** 시각. [store] 가 영속이면 재부팅을 넘어 비교할 수 있는 [AdClock.Wall](기본)이어야 한다. */
        public fun clock(clock: AdClock): Builder = apply { this.clock = clock }

        /** 마지막 표시 시각을 남기는 곳. 기본은 메모리(프로세스가 끝나면 사라진다). */
        public fun store(store: AdFrequencyStore): Builder = apply { this.store = store }

        /** [store] 안의 키 접두어. */
        public fun key(key: String): Builder = apply { this.key = key }

        /** @throws IllegalArgumentException 시간이 음수이거나 [maxShows] 가 0 이하이거나 [key] 가 비었을 때 */
        public fun build(): IntervalFrequencyPolicy {
            require(minIntervalMillis >= 0) { "minIntervalMillis 는 0 이상이어야 합니다: $minIntervalMillis" }
            require(initialDelayMillis >= 0) { "initialDelayMillis 는 0 이상이어야 합니다: $initialDelayMillis" }
            require(maxShows > 0) { "maxShows 는 1 이상이어야 합니다: $maxShows" }
            require(key.isNotBlank()) { "key 가 비었습니다" }
            return IntervalFrequencyPolicy(this)
        }
    }

    public companion object {
        /** 기본 최소 간격: 2분. */
        public const val DEFAULT_MIN_INTERVAL_MILLIS: Long = 120_000L

        /** 기본 시작 유예: 30초. */
        public const val DEFAULT_INITIAL_DELAY_MILLIS: Long = 30_000L

        /** 기본 저장소 키 접두어. */
        public const val DEFAULT_KEY: String = "interval"

        /** 빌더. */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}

/**
 * 앱의 처음 [skipFirstLaunches] 번 실행에서는 막는다. 첫 실행(온보딩)에 앱 오프닝 광고를 띄우지 말라는 권장에 맞춘다.
 *
 * 실행 횟수는 [store] 에 남는다. 이 인스턴스가 처음 판정할 때 한 번 센다 — 앱에 인스턴스 하나만 두어야 한 실행을 두 번 세지 않는다.
 *
 * @throws IllegalArgumentException [skipFirstLaunches] 가 음수이거나 [key] 가 비었을 때
 */
public class LaunchCountFrequencyPolicy(
    public val skipFirstLaunches: Int,
    private val store: AdFrequencyStore,
    public val key: String,
) : AdFrequencyPolicy {
    /** 키를 [DEFAULT_KEY] 로 둔다. */
    public constructor(skipFirstLaunches: Int, store: AdFrequencyStore) : this(skipFirstLaunches, store, DEFAULT_KEY)

    init {
        require(skipFirstLaunches >= 0) { "skipFirstLaunches 는 0 이상이어야 합니다: $skipFirstLaunches" }
        require(key.isNotBlank()) { "key 가 비었습니다" }
    }

    private var launch: Long? = null

    /** 이번 실행의 번호(1부터). 처음 읽을 때 센다. */
    @get:Synchronized
    public val launchNumber: Long
        get() = launch ?: (store.getLong(key) ?: 0L).coerceIn(0L, Long.MAX_VALUE - 1).plus(1).also {
            store.putLong(key, it)
            launch = it
        }

    override fun canShow(placement: String): Boolean = launchNumber > skipFirstLaunches

    override fun onShown(placement: String): Unit = Unit

    public companion object {
        /** 기본 저장소 키. */
        public const val DEFAULT_KEY: String = "launch_count"
    }
}
