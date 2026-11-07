package net.plumt.ads.compose

import android.view.View
import net.plumt.ads.network.BannerHandle
import org.junit.Assert.assertEquals
import org.junit.Test

class BannerRunGateTest {
    private class RecordingHandle : BannerHandle {
        val calls = mutableListOf<String>()
        override val view: View get() = error("뷰는 쓰지 않는다")
        override val heightDp: Int = 50
        override fun load() { calls += "load" }
        override fun pause() { calls += "pause" }
        override fun resume() { calls += "resume" }
        override fun destroy() { calls += "destroy" }
    }

    @Test fun pausesOnlyOnChange() {
        val handle = RecordingHandle()
        val gate = BannerRunGate().also { it.attach(handle) }
        gate.update(true)
        gate.update(true)
        gate.update(false)
        gate.update(false)
        gate.update(true)
        assertEquals(listOf("resume", "pause", "resume"), handle.calls)
    }

    @Test fun offlineBannerIsPausedAgainAfterRequestAndResponse() {
        // 오프라인으로 멈춘 뒤 요청·응답이 갱신 타이머를 다시 걸어도 멈춤을 되건다
        val handle = RecordingHandle()
        val gate = BannerRunGate().also { it.attach(handle) }
        gate.update(false)
        handle.load()
        gate.reassert()
        gate.reassert()
        assertEquals(listOf("pause", "load", "pause", "pause"), handle.calls)
    }

    @Test fun runningBannerIsNotPausedByReassert() {
        val handle = RecordingHandle()
        val gate = BannerRunGate().also { it.attach(handle) }
        gate.reassert()
        gate.update(true)
        gate.reassert()
        assertEquals(listOf("resume"), handle.calls)
    }

    @Test fun stateDecidedBeforeAttachIsApplied() {
        val handle = RecordingHandle()
        val gate = BannerRunGate()
        gate.update(false)
        gate.reassert()
        gate.attach(handle)
        assertEquals(listOf("pause"), handle.calls)
    }

    @Test fun shouldRunOnlyWhenResumedAndOnline() {
        assertEquals(true, bannerShouldRun(resumed = true, online = true))
        assertEquals(false, bannerShouldRun(resumed = true, online = false))
        assertEquals(false, bannerShouldRun(resumed = false, online = true))
    }
}
