package net.plumt.ads.compose

import net.plumt.ads.network.BannerHandle

/**
 * 배너 슬롯 하나의 배너 뷰 수명. 연결·화면 상태를 받아 뷰를 만들고 없앤다. 메인 스레드에서만 부른다.
 *
 * - 연결돼 있을 때만 뷰가 있다. 오프라인이 되면 뷰를 destroy 하고 슬롯에서 뗀다 — 광고 SDK 의 `pause` 는
 *   자동 갱신 타이머를 확실히 멈추지 못해(요청 전 호출은 무시되고, 실제 처리는 SDK 내부 원격 구현이 정한다)
 *   오프라인 동안 60초마다 갱신 요청이 나갔다. 없앤 뷰는 요청을 보낼 수 없다
 * - 연결이 돌아오면 새 뷰를 한 번 만들어 요청한다. 같은 연결 상태가 다시 와도 또 만들지 않는다
 * - 첫 연결 상태를 받기 전에는 만들지 않는다(오프라인으로 시작하면 요청하지 않는다)
 * - 뷰가 있는 동안 자동 갱신은 화면이 전면(RESUMED)일 때만 돌린다. 멈춤 여부는 늘 두 상태를 합쳐 정한다
 *   (`멈춤 = 전면 아님 || 오프라인`) — 수명주기의 resume 이 오프라인을 덮지 않는다
 * - 만들기에 실패하면([create] 가 null) 이 슬롯은 더 만들지 않는다(실패는 [create] 가 알린다)
 *
 * @param create 새 배너 뷰를 만든다. 요청은 보내지 않는다. 만들 수 없으면 null
 * @param onRequested 요청을 보내기 직전(분석 사건용)
 * @param onHandle 슬롯에 붙은 뷰가 바뀌었다(null 이면 뗐다)
 */
internal class BannerSlotController(
    private val create: () -> BannerHandle?,
    private val onRequested: () -> Unit = {},
    private val onHandle: (BannerHandle?) -> Unit = {},
) {
    /** 지금 슬롯에 붙은 뷰. 오프라인·만들기 전·폐기 뒤에는 null. */
    var handle: BannerHandle? = null
        private set

    /** 마지막으로 받은 연결 상태. 아직 모르면 null. */
    var online: Boolean? = null
        private set

    private var resumed = false
    private var gate = BannerRunGate()
    private var createFailed = false
    private var disposed = false

    /** 연결 상태가 왔다. */
    fun setOnline(value: Boolean) {
        if (disposed || online == value) return
        online = value
        if (value) attachNew() else detach()
    }

    /** 화면이 전면(RESUMED)인지가 바뀌었다. */
    fun setResumed(value: Boolean) {
        if (disposed) return
        resumed = value
        applyRun()
    }

    /** 같은 뷰로 다시 요청한다(첫 광고 전 실패의 재시도). 뷰가 없으면(오프라인) 아무것도 하지 않는다. */
    fun loadAgain() {
        val current = handle ?: return
        onRequested()
        current.load()
        gate.reassert()
    }

    /** 요청·응답 직후. 화면이 전면이 아니라 멈춰 있어야 하면 다시 멈춘다. */
    fun reassert() = gate.reassert()

    /** 슬롯이 컴포지션을 떠났다. 뷰를 없애고 이후 사건은 무시한다. */
    fun dispose() {
        if (disposed) return
        disposed = true
        detach()
    }

    private fun attachNew() {
        if (handle != null || createFailed) return
        val created = create()
        if (created == null) {
            createFailed = true
            return
        }
        handle = created
        gate = BannerRunGate().also { it.attach(created) }
        onHandle(created)
        // 요청을 먼저 보내고 그 뒤에 멈춤 여부를 건다 — 멈춘 뒤 요청하면 SDK 가 갱신을 다시 돌린다
        onRequested()
        created.load()
        applyRun()
        gate.reassert()
    }

    private fun detach() {
        val current = handle ?: return
        handle = null
        // 없앤 뷰에는 멈춤·재개를 다시 부르지 않는다
        gate = BannerRunGate()
        current.destroy()
        onHandle(null)
    }

    private fun applyRun() {
        if (handle == null) return
        gate.update(bannerShouldRun(resumed, online == true))
    }
}
