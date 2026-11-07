package net.plumt.ads.compose

import android.view.View
import net.plumt.ads.network.BannerHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class BannerSlotControllerTest {
    /** 가짜 배너 뷰. 자기 호출과 공용 기록에 함께 남긴다. */
    private class FakeHandle(val id: Int, private val log: MutableList<String>) : BannerHandle {
        val calls = mutableListOf<String>()
        var destroyed = false
            private set
        override val view: View get() = error("뷰는 쓰지 않는다")
        override val heightDp: Int = 50
        private fun record(name: String) {
            check(!destroyed) { "destroy 뒤 $name 호출: #$id" }
            calls += name
            log += "$name#$id"
        }
        override fun load() = record("load")
        override fun pause() = record("pause")
        override fun resume() = record("resume")
        override fun destroy() {
            record("destroy")
            destroyed = true
        }
    }

    private class Harness(failCreate: Boolean = false) {
        val log = mutableListOf<String>()
        val created = mutableListOf<FakeHandle>()
        val attached = mutableListOf<BannerHandle?>()
        var requests = 0
        val controller = BannerSlotController(
            create = {
                if (failCreate) null else FakeHandle(created.size + 1, log).also { created += it }
            },
            onRequested = { requests++ },
            onHandle = { attached += it },
        )
    }

    @Test fun notCreatedBeforeConnectivityIsKnown() {
        val h = Harness()
        h.controller.setResumed(true)
        assertEquals(0, h.created.size)
        assertNull(h.controller.handle)
        assertNull(h.controller.online)
    }

    @Test fun onlineCreatesOnceAndRequestsOnce() {
        val h = Harness()
        h.controller.setResumed(true)
        h.controller.setOnline(true)
        h.controller.setOnline(true)
        assertEquals(1, h.created.size)
        assertEquals(1, h.requests)
        assertSame(h.created[0], h.controller.handle)
        // 요청을 먼저 보내고 그 뒤에 돌릴지를 건다
        assertEquals(listOf("load", "resume"), h.created[0].calls)
    }

    @Test fun startingOfflineSendsNoRequest() {
        val h = Harness()
        h.controller.setResumed(true)
        h.controller.setOnline(false)
        assertEquals(0, h.created.size)
        assertEquals(0, h.requests)
        assertNull(h.controller.handle)
    }

    @Test fun goingOfflineDestroysAndDetaches() {
        val h = Harness()
        h.controller.setResumed(true)
        h.controller.setOnline(true)
        val first = h.created[0]
        h.controller.setOnline(false)
        assertEquals(true, first.destroyed)
        assertNull(h.controller.handle)
        assertEquals(listOf(first, null), h.attached)
        // 없앤 뷰는 더 이상 요청할 수 없다 — 재시도도 아무것도 하지 않는다
        h.controller.loadAgain()
        h.controller.reassert()
        assertEquals(1, h.requests)
    }

    @Test fun resumeWhileOfflineDoesNotRevive() {
        val h = Harness()
        h.controller.setResumed(true)
        h.controller.setOnline(true)
        h.controller.setOnline(false)
        // 수명주기가 몇 번 오가도 오프라인이면 뷰를 다시 만들거나 재개하지 않는다
        repeat(3) {
            h.controller.setResumed(false)
            h.controller.setResumed(true)
        }
        assertEquals(1, h.created.size)
        assertNull(h.controller.handle)
        assertEquals(listOf("load#1", "resume#1", "destroy#1"), h.log)
    }

    @Test fun reconnectRecreatesExactlyOnce() {
        val h = Harness()
        h.controller.setResumed(true)
        h.controller.setOnline(true)
        h.controller.setOnline(false)
        h.controller.setOnline(true)
        h.controller.setOnline(true)
        h.controller.setResumed(true)
        assertEquals(2, h.created.size)
        assertEquals(2, h.requests)
        val second = h.created[1]
        assertNotSame(h.created[0], second)
        assertSame(second, h.controller.handle)
        assertEquals(listOf("load", "resume"), second.calls)
        assertEquals(listOf("load#1", "resume#1", "destroy#1", "load#2", "resume#2"), h.log)
    }

    @Test fun repeatedFlapsKeepOneLiveView() {
        val h = Harness()
        h.controller.setResumed(true)
        repeat(4) {
            h.controller.setOnline(true)
            h.controller.setOnline(false)
        }
        h.controller.setOnline(true)
        assertEquals(5, h.created.size)
        assertEquals(4, h.created.count { it.destroyed })
        assertEquals(false, h.created.last().destroyed)
        assertEquals(5, h.requests)
    }

    @Test fun backgroundPausesAndForegroundResumesWhileOnline() {
        val h = Harness()
        h.controller.setResumed(true)
        h.controller.setOnline(true)
        h.controller.setResumed(false)
        h.controller.setResumed(false)
        h.controller.setResumed(true)
        assertEquals(listOf("load", "resume", "pause", "resume"), h.created[0].calls)
    }

    @Test fun reconnectInBackgroundStaysPausedUntilResumed() {
        val h = Harness()
        h.controller.setResumed(true)
        h.controller.setOnline(true)
        h.controller.setResumed(false)
        h.controller.setOnline(false)
        h.controller.setOnline(true)
        val second = h.created[1]
        // 요청 뒤 멈춤, 요청 직후 멈춤 재적용
        assertEquals(listOf("load", "pause", "pause"), second.calls)
        h.controller.setResumed(true)
        assertEquals(listOf("load", "pause", "pause", "resume"), second.calls)
    }

    @Test fun pausedViewIsPausedAgainAfterRetryAndResponse() {
        val h = Harness()
        h.controller.setOnline(true)
        h.controller.setResumed(false)
        val view = h.created[0]
        view.calls.clear()
        h.controller.loadAgain()
        h.controller.reassert()
        assertEquals(listOf("load", "pause", "pause"), view.calls)
        assertEquals(2, h.requests)
    }

    @Test fun retryOnRunningViewDoesNotPause() {
        val h = Harness()
        h.controller.setResumed(true)
        h.controller.setOnline(true)
        val view = h.created[0]
        view.calls.clear()
        h.controller.loadAgain()
        h.controller.reassert()
        assertEquals(listOf("load"), view.calls)
    }

    @Test fun disposeDestroysAndIgnoresLaterEvents() {
        val h = Harness()
        h.controller.setResumed(true)
        h.controller.setOnline(true)
        h.controller.dispose()
        h.controller.dispose()
        h.controller.setOnline(false)
        h.controller.setOnline(true)
        h.controller.setResumed(true)
        assertEquals(1, h.created.size)
        assertEquals(true, h.created[0].destroyed)
        assertNull(h.controller.handle)
    }

    @Test fun disposeWhileOfflineCreatesNothing() {
        val h = Harness()
        h.controller.setOnline(false)
        h.controller.dispose()
        h.controller.setOnline(true)
        assertEquals(0, h.created.size)
    }

    @Test fun failedCreationIsNotRetriedOnReconnect() {
        val h = Harness(failCreate = true)
        h.controller.setResumed(true)
        h.controller.setOnline(true)
        h.controller.setOnline(false)
        h.controller.setOnline(true)
        assertNull(h.controller.handle)
        assertEquals(0, h.requests)
        assertEquals(emptyList<BannerHandle?>(), h.attached)
    }
}
