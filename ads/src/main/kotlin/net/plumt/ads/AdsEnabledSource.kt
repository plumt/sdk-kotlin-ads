package net.plumt.ads

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/**
 * 광고를 보여도 되는지 앱이 알려 주는 전역 스위치. 광고 제거 구매·원격 설정·운영 점검 같은 것을 여기에 꽂는다.
 *
 * `false` 가 흐르면 라이브러리는 새 요청을 보내지 않고, 들고 있던 광고를 버리고, 광고 슬롯을 그리지 않는다.
 * 값이 아직 흐르기 전에는 꺼진 것으로 취급한다.
 */
public interface AdsEnabledSource {
    /** 현재 값과 이후 변경을 흘린다. 구독하면 곧바로 현재 값이 와야 한다. */
    public val enabled: Flow<Boolean>

    public companion object {
        /** 항상 켜짐. */
        public val Always: AdsEnabledSource = object : AdsEnabledSource {
            override val enabled: Flow<Boolean> = flowOf(true)
        }

        /** 항상 꺼짐. 광고 없는 내부·시연 빌드에 쓴다 — 동의 폼·SDK 초기화·광고 요청이 모두 일어나지 않는다. */
        public val Never: AdsEnabledSource = object : AdsEnabledSource {
            override val enabled: Flow<Boolean> = flowOf(false)
        }

        /** 빌드 시점에 정해진 값. 예: `AdsEnabledSource.of(BuildConfig.ADS_ENABLED)`. */
        public fun of(enabled: Boolean): AdsEnabledSource = if (enabled) Always else Never

        /** 임의의 [Flow] 를 스위치로 쓴다. */
        public fun from(flow: Flow<Boolean>): AdsEnabledSource = object : AdsEnabledSource {
            override val enabled: Flow<Boolean> = flow
        }

        /**
         * 모두 켜져 있을 때만 켜짐. 예: 빌드 스위치 + 광고 제거 구매 + 원격 설정.
         * 아무 스위치도 첫 값을 내기 전에는 아무것도 흘리지 않는다(= 꺼짐으로 취급). 하나가 예외로 끝나면 합친 Flow 도 그 예외로 끝난다.
         *
         * @throws IllegalArgumentException [sources] 가 비었을 때
         */
        public fun all(vararg sources: AdsEnabledSource): AdsEnabledSource {
            require(sources.isNotEmpty()) { "sources 가 비었습니다" }
            if (sources.size == 1) return sources[0]
            val flows = sources.map { it.enabled }
            return from(combine(flows) { values -> values.all { it } }.distinctUntilChanged())
        }
    }
}

/** 값을 코드로 바꾸는 기본 스위치. 구매 결과를 받는 쪽이 [set] 을 부른다. */
public class MutableAdsEnabledSource(initial: Boolean = true) : AdsEnabledSource {
    private val state = MutableStateFlow(initial)

    override val enabled: StateFlow<Boolean> = state.asStateFlow()

    /** 스위치를 바꾼다. */
    public fun set(enabled: Boolean) {
        state.value = enabled
    }
}
