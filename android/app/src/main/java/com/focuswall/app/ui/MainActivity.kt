package com.focuswall.app.ui

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.focuswall.app.BuildConfig
import com.focuswall.app.block.BlockerAccessibilityService
import com.focuswall.app.block.DnsVpnService
import com.focuswall.app.core.ListMode
import com.focuswall.app.core.Rules
import com.focuswall.app.core.Session
import com.focuswall.app.data.FirebaseRest
import com.focuswall.app.data.Store
import com.focuswall.app.data.SyncManager
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SyncManager.syncAsync(this, minIntervalMs = 0)
        setContent { FocusWallTheme { App() } }
    }
}

/** Меняется при любом изменении Store, чтобы экран перерисовывался. */
@Composable
fun rememberStoreVersion(store: Store): Int {
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(store) {
        val l: () -> Unit = { version++ }
        store.addListener(l)
        onDispose { store.removeListener(l) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            version++
        }
    }
    return version
}

@Composable
private fun App() {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                listOf("Главная", "Списки", "Расписание").forEachIndexed { i, title ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                }
            }
            when (tab) {
                0 -> HomeScreen()
                1 -> ListsScreen()
                else -> ScheduleScreen()
            }
        }
    }
}

@Composable
fun SectionCard(content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
fun Muted(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))

@Composable
fun ErrorText(text: String) {
    if (text.isNotEmpty()) Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun HomeScreen() {
    val ctx = LocalContext.current
    val store = remember { Store.get(ctx) }
    val version = rememberStoreVersion(store)
    var error by remember { mutableStateOf("") }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        key(version) {
            StatusCard(store) { error = it }
            if (!Rules.sessionActive(store.session, System.currentTimeMillis())) StartCard(store) { error = it }
            SetupCard(store)
            AccountCard(store) { error = it }
        }
        ErrorText(error)
    }
}

@Composable
private fun StatusCard(store: Store, onError: (String) -> Unit) {
    val ctx = LocalContext.current
    val config = store.config
    val session = store.session
    val now = System.currentTimeMillis()
    val until = store.blockedUntil()
    val names = { ids: List<String> -> ids.mapNotNull { id -> config.blocklists.find { it.id == id }?.name }.joinToString() }
    SectionCard {
        if (until != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Блокировка включена", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Text("ещё ${Rules.formatLeft(until - now)}", fontWeight = FontWeight.Bold)
            }
            if (Rules.sessionActive(session, now)) Muted("Сессия: ${names(session!!.listIds)}")
            config.schedules.filter { Rules.scheduleActive(it, LocalDateTime.now()) }
                .forEach { Muted("Расписание «${it.name}»: ${names(it.listIds)}") }
            if (store.hardActive()) {
                Muted("Жёсткий режим: отключить до конца нельзя.")
            } else if (Rules.sessionActive(session, now)) {
                OutlinedButton(onClick = {
                    store.setSession(null, System.currentTimeMillis())
                    SyncManager.syncAsync(ctx, 0)
                    onError("")
                }) { Text("Остановить сессию") }
            }
        } else {
            Text("Блокировка выключена", fontWeight = FontWeight.SemiBold)
            val next = config.schedules.filter { it.enabled }.joinToString { "${it.name} ${it.start}–${it.end}" }
            if (next.isNotEmpty()) Muted("Расписание: $next")
        }
    }
}

@Composable
private fun StartCard(store: Store, onError: (String) -> Unit) {
    val ctx = LocalContext.current
    val config = store.config
    val selected = remember { mutableStateListOf<String>().apply { addAll(config.blocklists.filter { it.mode == ListMode.BLOCK }.map { it.id }) } }
    var minutes by remember { mutableIntStateOf(50) }
    var hard by remember { mutableStateOf(false) }
    SectionCard {
        Text("Заблокировать сейчас", fontWeight = FontWeight.SemiBold)
        config.blocklists.forEach { l ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = l.id in selected, onCheckedChange = { if (it) selected += l.id else selected -= l.id })
                Text(l.name)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(25 to "25 мин", 50 to "50 мин", 90 to "1,5 ч", 180 to "3 ч").forEach { (m, label) ->
                FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text(label) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = hard, onCheckedChange = { hard = it })
            Spacer(Modifier.padding(4.dp))
            Text("Жёсткий режим")
        }
        Button(modifier = Modifier.fillMaxWidth(), onClick = {
            if (selected.isEmpty()) {
                onError("Выберите хотя бы один список")
                return@Button
            }
            val now = System.currentTimeMillis()
            store.setSession(Session(UUID.randomUUID().toString(), selected.toList(), now, now + minutes * 60_000L, hard, "android"), now)
            SyncManager.syncAsync(ctx, 0)
            onError("")
        }) { Text("Начать") }
    }
}

private fun accessibilityEnabled(ctx: Context): Boolean {
    val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    return enabled.split(':').any { it.equals("${ctx.packageName}/${BlockerAccessibilityService::class.java.name}", ignoreCase = true) }
}

@Composable
private fun SetupCard(store: Store) {
    val ctx = LocalContext.current
    val a11y = accessibilityEnabled(ctx)
    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (VpnService.prepare(ctx) == null) {
            store.vpnEnabled = true
            DnsVpnService.ensureRunning(ctx)
        }
    }
    SectionCard {
        Text("Разрешения", fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Блокировка приложений")
                Muted(if (a11y) "Включена" else "Нужно включить FocusWall в «Специальных возможностях»")
            }
            if (!a11y) OutlinedButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("Включить") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Фильтр сайтов")
                Muted("Локальный VPN только для DNS, трафик никуда не уходит")
            }
            Switch(
                checked = store.vpnEnabled,
                enabled = !(store.vpnEnabled && store.hardActive()),
                onCheckedChange = { on ->
                    if (on) {
                        val intent = VpnService.prepare(ctx)
                        if (intent != null) vpnLauncher.launch(intent) else {
                            store.vpnEnabled = true
                            DnsVpnService.ensureRunning(ctx)
                        }
                    } else {
                        store.vpnEnabled = false
                        DnsVpnService.stop(ctx)
                    }
                },
            )
        }
    }
}

@Composable
private fun AccountCard(store: Store, onError: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth = store.auth
    SectionCard {
        when {
            !FirebaseRest.configured -> Muted("Синхронизация не настроена в этой сборке. Всё хранится на телефоне.")
            auth != null -> {
                Text(auth.email, fontWeight = FontWeight.SemiBold)
                if (store.syncError.isNotEmpty()) ErrorText("Ошибка синхронизации: ${store.syncError}")
                else if (store.lastSync > 0) Muted("Синхронизировано ${java.text.DateFormat.getTimeInstance().format(store.lastSync)}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { SyncManager.syncAsync(ctx, 0) }) { Text("Синхронизировать") }
                    OutlinedButton(onClick = { store.auth = null }) { Text("Выйти") }
                }
            }
            else -> {
                Muted("Войдите, чтобы списки и расписание совпадали на телефоне и компьютере")
                Button(onClick = {
                    scope.launch {
                        try {
                            val option = GetGoogleIdOption.Builder()
                                .setFilterByAuthorizedAccounts(false)
                                .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                                .build()
                            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
                            val cred = CredentialManager.create(ctx).getCredential(ctx, request).credential
                            if (cred !is CustomCredential || cred.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                                throw IllegalStateException("Неожиданный тип входа")
                            }
                            val idToken = GoogleIdTokenCredential.createFrom(cred.data).idToken
                            val a = withContext(Dispatchers.IO) { FirebaseRest.signInWithGoogleIdToken(idToken) }
                            store.auth = a
                            withContext(Dispatchers.IO) { SyncManager.sync(ctx) }
                            onError("")
                        } catch (e: Exception) {
                            onError(e.message ?: e.toString())
                        }
                    }
                }) { Text("Войти через Google") }
            }
        }
    }
}
