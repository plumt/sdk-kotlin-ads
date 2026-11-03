package net.plumt.ads.compose

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable

/**
 * 목록에 광고를 끼우는 규칙. 첫 광고는 콘텐츠 [firstAdAfter] 개 뒤에, 그다음부터는 콘텐츠 [interval] 개마다 온다.
 * 마지막 콘텐츠 뒤에는 광고를 두지 않는다(뒤에 콘텐츠가 있는 자리에만 끼운다).
 *
 * 예: `interval = 5, firstAdAfter = 3` 이면 콘텐츠 0,1,2 · 광고 · 콘텐츠 3..7 · 광고 · …
 *
 * @property interval 광고 사이 콘텐츠 수
 * @property firstAdAfter 첫 광고 앞 콘텐츠 수
 * @property maxAds 끼울 광고 수 상한
 * @throws IllegalArgumentException [interval]·[firstAdAfter] 가 1 미만이거나 [maxAds] 가 음수일 때
 */
public class AdInterleave(
    public val interval: Int,
    public val firstAdAfter: Int = interval,
    public val maxAds: Int = Int.MAX_VALUE,
) {
    init {
        require(interval >= 1) { "interval 은 1 이상이어야 합니다: $interval" }
        require(firstAdAfter >= 1) { "firstAdAfter 는 1 이상이어야 합니다: $firstAdAfter" }
        require(maxAds >= 0) { "maxAds 는 0 이상이어야 합니다: $maxAds" }
    }

    /**
     * 콘텐츠가 [contentCount] 개일 때의 배치.
     *
     * @throws IllegalArgumentException [contentCount] 가 음수일 때
     */
    public fun plan(contentCount: Int): InterleavePlan {
        require(contentCount >= 0) { "contentCount 는 0 이상이어야 합니다: $contentCount" }
        return InterleavePlan(this, contentCount)
    }
}

/** [AdInterleave] 를 콘텐츠 개수에 적용한 불변 결과. 목록 인덱스와 콘텐츠 인덱스·광고 슬롯을 오간다. */
public class InterleavePlan internal constructor(
    private val spec: AdInterleave,
    public val contentCount: Int,
) {
    /** 끼워지는 광고 수. */
    public val adCount: Int = if (contentCount <= spec.firstAdAfter) {
        0
    } else {
        minOf(spec.maxAds.toLong(), ((contentCount - 1 - spec.firstAdAfter) / spec.interval + 1).toLong()).toInt()
    }

    /** 광고를 합친 전체 항목 수. */
    public val totalCount: Int = contentCount + adCount

    /** [index] 가 광고 자리인가. */
    public fun isAd(index: Int): Boolean {
        checkIndex(index)
        return adSlotOrNull(index) != null
    }

    /**
     * 광고 자리의 슬롯 번호(0부터).
     *
     * @throws IllegalArgumentException [index] 가 광고 자리가 아닐 때
     */
    public fun adSlot(index: Int): Int {
        checkIndex(index)
        return requireNotNull(adSlotOrNull(index)) { "광고 자리가 아닙니다: $index" }
    }

    /**
     * 콘텐츠 자리의 콘텐츠 인덱스.
     *
     * @throws IllegalArgumentException [index] 가 콘텐츠 자리가 아닐 때
     */
    public fun contentIndex(index: Int): Int {
        checkIndex(index)
        require(adSlotOrNull(index) == null) { "콘텐츠 자리가 아닙니다: $index" }
        return index - adsBefore(index)
    }

    /**
     * 콘텐츠 인덱스에 해당하는 목록 인덱스.
     *
     * @throws IllegalArgumentException [contentIndex] 가 0..contentCount-1 밖일 때
     */
    public fun listIndexOfContent(contentIndex: Int): Int {
        require(contentIndex in 0 until contentCount) {
            "contentIndex 는 0..${contentCount - 1} 이어야 합니다: $contentIndex"
        }
        // 이 콘텐츠 앞에 오는 광고 수: 광고 k 는 콘텐츠 firstAdAfter + k*interval 개 뒤에 온다
        val before = if (contentIndex < spec.firstAdAfter) 0 else (contentIndex - spec.firstAdAfter) / spec.interval + 1
        return contentIndex + minOf(before, adCount)
    }

    private fun checkIndex(index: Int) {
        require(index in 0 until totalCount) { "index 는 0..${totalCount - 1} 이어야 합니다: $index" }
    }

    // 광고 k 의 목록 인덱스 = firstAdAfter + k * (interval + 1)
    private fun adSlotOrNull(index: Int): Int? {
        val offset = index - spec.firstAdAfter
        if (offset < 0 || offset % (spec.interval + 1) != 0) return null
        val slot = offset / (spec.interval + 1)
        return if (slot < adCount) slot else null
    }

    private fun adsBefore(index: Int): Int {
        val offset = index - spec.firstAdAfter
        if (offset < 0) return 0
        return minOf(adCount, offset / (spec.interval + 1) + 1)
    }
}

/** 광고 항목의 목록 키 접두어. 콘텐츠 키가 이 접두어로 시작하는 문자열이면 충돌한다. */
public const val AD_ITEM_KEY_PREFIX: String = "net.plumt.ads.slot:"

/**
 * [items] 사이에 광고를 끼워 넣는다. 콘텐츠 키는 [key], 광고 키는 슬롯 번호 기반 문자열이라
 * 항목이 추가·삭제돼도 스크롤 위치와 상태가 안정적으로 유지된다.
 *
 * @param adsEnabled false 면 광고 없이 콘텐츠만 낸다(보통 `AdsClient.adsAvailable` 값을 넘긴다)
 * @param key 콘텐츠 키. 안드로이드 규칙상 Bundle 에 담을 수 있는 타입이어야 한다
 * @param adContent 슬롯 번호별 광고 모양. 슬롯 번호를 `NativeAdPool` 인덱스로 쓴다
 */
public fun <T> LazyListScope.itemsWithAds(
    items: List<T>,
    interleave: AdInterleave,
    adsEnabled: Boolean,
    key: (item: T) -> Any,
    contentType: (item: T) -> Any? = { null },
    adContent: @Composable LazyItemScope.(slot: Int) -> Unit,
    itemContent: @Composable LazyItemScope.(item: T) -> Unit,
) {
    if (!adsEnabled) {
        items(items.size, key = { key(items[it]) }, contentType = { contentType(items[it]) }) { itemContent(items[it]) }
        return
    }
    val plan = interleave.plan(items.size)
    items(
        count = plan.totalCount,
        key = { index -> if (plan.isAd(index)) "$AD_ITEM_KEY_PREFIX${plan.adSlot(index)}" else key(items[plan.contentIndex(index)]) },
        contentType = { index -> if (plan.isAd(index)) AD_CONTENT_TYPE else contentType(items[plan.contentIndex(index)]) },
    ) { index ->
        if (plan.isAd(index)) adContent(plan.adSlot(index)) else itemContent(items[plan.contentIndex(index)])
    }
}

private const val AD_CONTENT_TYPE = "net.plumt.ads.slot"
