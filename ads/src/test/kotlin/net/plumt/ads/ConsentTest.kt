package net.plumt.ads

import android.app.Activity
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.plumt.ads.consent.ConsentFormResult
import net.plumt.ads.consent.ConsentSnapshot
import net.plumt.ads.consent.ConsentStatus
import net.plumt.ads.consent.DefaultConsentManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class ConsentStatusMappingTest {
    @Test fun mapsEveryKnownUmpValue() {
        assertEquals(ConsentStatus.Unknown, net.plumt.ads.consent.consentStatusFromUmp(0))
        assertEquals(ConsentStatus.NotRequired, net.plumt.ads.consent.consentStatusFromUmp(1))
        assertEquals(ConsentStatus.Required, net.plumt.ads.consent.consentStatusFromUmp(2))
        assertEquals(ConsentStatus.Obtained, net.plumt.ads.consent.consentStatusFromUmp(3))
    }

    @Test fun unknownValuesAreUnknown() {
        listOf(-1, 4, 99, Int.MAX_VALUE, Int.MIN_VALUE).forEach {
            assertEquals("값 $it", ConsentStatus.Unknown, net.plumt.ads.consent.consentStatusFromUmp(it))
        }
    }

    @Test fun initialSnapshotRequestsNothing() {
        assertEquals(ConsentSnapshot(ConsentStatus.Unknown, false, false), ConsentSnapshot.Initial)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33, 35])
class DefaultConsentManagerTest {
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).get()

    @Test fun startsFromStoredState() {
        val m = DefaultConsentManager(FakeGateway(ALLOW))
        assertEquals(ALLOW, m.snapshot.value)
    }

    @Test fun gatherSuccessPublishesNewSnapshot() = runTest {
        val gw = FakeGateway(DENY)
        gw.onGather = { done -> gw.snapshot = ALLOW; done(null) }
        val m = DefaultConsentManager(gw)
        m.gather(activity)
        assertEquals(ALLOW, m.snapshot.value)
        assertEquals(1, gw.gatherCalls)
    }

    @Test fun gatherFailureReturnsSameExceptionAndStillRefreshes() = runTest {
        val gw = FakeGateway(DENY)
        val err = AdsException(AdsErrorKind.Consent, 2, "네트워크")
        gw.onGather = { done -> gw.snapshot = ALLOW; done(err) }
        val m = DefaultConsentManager(gw)
        val result = m.gather(activity)
        assertSame(err, (result as ConsentFormResult.Failed).error)
        assertEquals(ConsentFormResult.Failed(err), result)
        assertEquals(result.hashCode(), ConsentFormResult.Failed(err).hashCode())
        assertTrue(result.toString().contains("네트워크"))
        assertEquals("지난 실행 동의가 허용이면 실패해도 허용", ALLOW, m.snapshot.value)
    }

    @Test fun secondGatherWhileRunningReturnsImmediately() = runTest(UnconfinedTestDispatcher()) {
        val gw = FakeGateway(DENY)
        var finish: ((AdsException?) -> Unit)? = null
        gw.onGather = { done -> finish = done }
        val m = DefaultConsentManager(gw)
        val first = launch { m.gather(activity) }
        m.gather(activity) // 두 번째는 대기하지 않는다
        assertEquals(1, gw.gatherCalls)
        assertTrue(first.isActive)
        finish!!(null)
        first.join()
        // 끝난 뒤에는 다시 시작할 수 있다
        gw.onGather = { done -> done(null) }
        m.gather(activity)
        assertEquals(2, gw.gatherCalls)
    }

    @Test fun cancellationReleasesGuard() = runTest(UnconfinedTestDispatcher()) {
        val gw = FakeGateway(DENY)
        gw.onGather = { }
        val m = DefaultConsentManager(gw)
        val job = launch { m.gather(activity) }
        job.cancel()
        job.join()
        gw.onGather = { done -> done(null) }
        m.gather(activity)
        assertEquals(2, gw.gatherCalls)
    }

    @Test fun lateCallbackAfterCancellationIsIgnored() = runTest(UnconfinedTestDispatcher()) {
        val gw = FakeGateway(DENY)
        var finish: ((AdsException?) -> Unit)? = null
        gw.onGather = { done -> finish = done }
        val m = DefaultConsentManager(gw)
        val job = launch { m.gather(activity) }
        job.cancel(); job.join()
        finish!!(AdsException(AdsErrorKind.Consent, 1, "늦은 실패")) // 던지지 않는다
    }

    @Test fun privacyOptionsSuccessAndFailure() = runTest {
        val gw = FakeGateway(ALLOW)
        val m = DefaultConsentManager(gw)
        gw.onPrivacy = { done -> gw.snapshot = DENY; done(null) }
        assertEquals(ConsentFormResult.Completed, m.showPrivacyOptions(activity))
        assertEquals(DENY, m.snapshot.value)
        val err = AdsException(AdsErrorKind.Consent, 7, "폼")
        gw.onPrivacy = { done -> done(err) }
        assertSame(err, (m.showPrivacyOptions(activity) as ConsentFormResult.Failed).error)
        assertEquals(2, gw.privacyCalls)
    }

    @Test fun refreshFromStorageReadsGateway() {
        val gw = FakeGateway(DENY)
        val m = DefaultConsentManager(gw)
        assertFalse(m.snapshot.value.canRequestAds)
        gw.snapshot = ALLOW
        m.refreshFromStorage()
        assertTrue(m.snapshot.value.canRequestAds)
    }

    @Test fun contextAvailable() {
        // Robolectric 컨텍스트가 AdClock.Default 에서 쓰이는 SystemClock 을 지원하는지 확인
        ApplicationProvider.getApplicationContext<android.content.Context>()
        val a = AdClock.Default.nowMillis()
        val b = AdClock.Default.nowMillis()
        assertTrue(b >= a)
    }

    @Suppress("unused")
    private val gate = CompletableDeferred<Unit>()
}
