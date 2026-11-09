package net.plumt.ads.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import net.plumt.ads.AdFrequencyPolicy
import net.plumt.ads.AdUnit
import net.plumt.ads.compose.AdBanner
import net.plumt.ads.compose.ExitConfirmDialog
import net.plumt.ads.compose.NativeAdInline
import net.plumt.ads.compose.NativeAdListItem
import net.plumt.ads.compose.NativeAdRow
import net.plumt.ads.compose.ProvideAdsClient
import net.plumt.ads.compose.findActivity
import net.plumt.ads.compose.rememberExitAd
import net.plumt.ads.compose.rememberFullScreenAdController

/**
 * 실기기 확인 화면. 형식마다 공개 메서드를 버튼 하나로 부르고 결과를 화면과 logcat(`AdsSample`·`PlumtAds`)에 남긴다.
 * 전면형은 빈도 제한 없이(`AdFrequencyPolicy.Unlimited`) 불러 매번 보일 수 있게 한다.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SampleApp
        setContent {
            MaterialTheme {
                ProvideAdsClient(app.ads) { SampleScreen(app, onExit = ::finish) }
            }
        }
    }
}

@Composable
private fun SampleScreen(app: SampleApp, onExit: () -> Unit) {
    var showExit by remember { mutableStateOf(false) }
    val log by app.eventLog.collectAsState()
    // 종료 팝업이 뜨는 순간 광고가 있도록 미리 받아 둔다
    rememberExitAd(SampleUnits.exit)
    BackHandler { showExit = true }

    Scaffold(bottomBar = { AdBanner(SampleUnits.banner) }) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 16.dp, bottom = padding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StatusSection(app) }
            item { ConsentSection(app) }
            item { FullScreenSection("전면", SampleUnits.interstitial, app) }
            item { FullScreenSection("보상형", SampleUnits.rewarded, app) }
            item { FullScreenSection("앱 오프닝", SampleUnits.appOpen, app) }
            item { Text("네이티브: 행", style = MaterialTheme.typography.titleSmall) }
            item { NativeAdRow(SampleUnits.native, onLoadFailed = { app.record("행 실패: ${it.reason}") }) }
            item { Text("네이티브: 목록 카드(동영상 시험 단위)", style = MaterialTheme.typography.titleSmall) }
            item { NativeAdListItem(SampleUnits.nativeVideo, onLoadFailed = { app.record("목록 카드 실패: ${it.reason}") }) }
            item { Text("네이티브: 작은 카드", style = MaterialTheme.typography.titleSmall) }
            item { NativeAdInline(SampleUnits.native, onLoadFailed = { app.record("작은 카드 실패: ${it.reason}") }) }
            item { OutlinedButton(onClick = { showExit = true }) { Text("종료 팝업(뒤로 가기와 같다)") } }
            item { HorizontalDivider() }
            item { Text("최근 사건", style = MaterialTheme.typography.titleSmall) }
            items(log) { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (showExit) ExitConfirmDialog(SampleUnits.exit, onDismiss = { showExit = false }, onExit = onExit)
}

@Composable
private fun StatusSection(app: SampleApp) {
    val ads = app.ads
    val available by ads.adsAvailable.collectAsState()
    val enabled by ads.adsEnabled.collectAsState()
    val consent by ads.consent.snapshot.collectAsState()
    val initError by ads.initializationError.collectAsState()
    val configErrors by ads.configurationErrors.collectAsState()
    val first by app.firstConsent.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("모드 ${ads.mode} · 광고 사용 가능 $available", style = MaterialTheme.typography.titleMedium)
        Text("동의 ${consent.status} · 요청 허용 ${consent.canRequestAds} · 개인정보 옵션 필요 ${consent.privacyOptionsRequired}")
        Text("첫 화면 동의 결과: ${first ?: "대기 중"}")
        Text("초기화 오류: ${initError?.message ?: "없음"} · 설정 오류 ${configErrors.size}건")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("광고 켜기", modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { app.adsSwitch.set(it) })
        }
    }
}

@Composable
private fun ConsentSection(app: SampleApp) {
    val ads = app.ads
    val activity = LocalContext.current.findActivity() ?: return
    val consent by ads.consent.snapshot.collectAsState()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { ads.gatherConsent(activity) { app.record("동의 다시 받기: $it") } }) { Text("동의 다시 받기") }
        OutlinedButton(
            enabled = consent.privacyOptionsRequired,
            onClick = { ads.consent.showPrivacyOptions(activity) { app.record("개인정보 옵션: $it") } },
        ) { Text("개인정보 옵션") }
    }
}

@Composable
private fun FullScreenSection(label: String, unit: AdUnit, app: SampleApp) {
    val activity = LocalContext.current.findActivity() ?: return
    val controller = rememberFullScreenAdController(unit, policy = AdFrequencyPolicy.Unlimited)
    val state by controller.state.collectAsState()
    val error by controller.lastError.collectAsState()
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { app.record("$label 보이기: ${controller.show(activity)}") }) { Text("$label 보이기") }
            OutlinedButton(onClick = { controller.preload() }) { Text("불러오기") }
        }
        Text("$label 상태 $state · 마지막 오류 ${error?.reason ?: "없음"}", style = MaterialTheme.typography.bodySmall)
    }
}
