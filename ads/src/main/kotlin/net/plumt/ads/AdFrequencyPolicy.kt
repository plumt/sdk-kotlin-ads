package net.plumt.ads

/**
 * 전면형 광고를 보여도 되는 빈도를 정하는 교체 지점. 메인 스레드에서만 불린다.
 * 프로세스 재시작을 넘겨 제한하고 싶으면 직접 구현해 저장소에 기록한다.
 */
public interface AdFrequencyPolicy {
    /** 지금 [placement] 광고를 보여도 되는가. 상태를 바꾸지 않는다. */
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
 * 기본 빈도 제한. 인스턴스 하나가 자기에게 묶인 모든 배치를 합쳐 센다 — 배치마다 따로 세려면 인스턴스를 따로 만든다.
 *
 * - 만든 시점부터 [initialDelayMillis] 가 지나기 전에는 막는다(앱 시작 직후 전면 광고 방지)
 * - 마지막 표시 뒤 [minIntervalMillis] 가 지나기 전에는 막는다
 * - 표시 횟수가 [maxShows] 에 닿으면 이 인스턴스가 버려질 때까지 막는다(세션 상한)
 *
 * @throws IllegalArgumentException 시간이 음수이거나 [maxShows] 가 0 이하일 때
 */
public class IntervalFrequencyPolicy(
    public val minIntervalMillis: Long = DEFAULT_MIN_INTERVAL_MILLIS,
    public val initialDelayMillis: Long = DEFAULT_INITIAL_DELAY_MILLIS,
    public val maxShows: Int = Int.MAX_VALUE,
    private val clock: AdClock = AdClock.Default,
) : AdFrequencyPolicy {
    init {
        require(minIntervalMillis >= 0) { "minIntervalMillis 는 0 이상이어야 합니다: $minIntervalMillis" }
        require(initialDelayMillis >= 0) { "initialDelayMillis 는 0 이상이어야 합니다: $initialDelayMillis" }
        require(maxShows > 0) { "maxShows 는 1 이상이어야 합니다: $maxShows" }
    }

    private val createdAt = clock.nowMillis()
    private var lastShownAt: Long? = null
    private var shows = 0

    /** 지금까지 기록된 표시 횟수. */
    public val shownCount: Int
        get() = shows

    @Synchronized
    override fun canShow(placement: String): Boolean {
        val now = clock.nowMillis()
        if (shows >= maxShows) return false
        if (now - createdAt < initialDelayMillis) return false
        val last = lastShownAt
        return last == null || now - last >= minIntervalMillis
    }

    @Synchronized
    override fun onShown(placement: String) {
        lastShownAt = clock.nowMillis()
        shows++
    }

    public companion object {
        /** 기본 최소 간격: 2분. */
        public const val DEFAULT_MIN_INTERVAL_MILLIS: Long = 120_000L

        /** 기본 시작 유예: 30초. */
        public const val DEFAULT_INITIAL_DELAY_MILLIS: Long = 30_000L
    }
}
