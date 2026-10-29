package com.focuswall.app.block

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focuswall.app.R
import com.focuswall.app.core.Rules
import com.focuswall.app.data.Store
import com.focuswall.app.ui.FocusWallTheme
import kotlinx.coroutines.delay

/** Экран, который показывается вместо заблокированного приложения или сайта. */
class BlockedActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render()
    }

    private fun render() {
        val what = intent.getStringExtra(EXTRA_WHAT) ?: ""
        val store = Store.get(this)
        setContent {
            FocusWallTheme {
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(15_000)
                        now = System.currentTimeMillis()
                    }
                }
                val until = remember(now) { store.blockedUntil() }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Image(painterResource(R.drawable.ic_wall), null, Modifier.size(72.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("Не сейчас", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        if (what.isNotEmpty()) Text("$what заблокировано FocusWall.", textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (until != null) "Блокировка закончится через ${Rules.formatLeft(until - now)}." else "Блокировка закончилась.",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { goHome() }) { Text("На главный экран") }
                        if (store.passAvailable()) {
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = {
                                // Разблокировка одна за ночь: после нажатия вернуть её нельзя.
                                if (store.startPass()) finish()
                            }) { Text("Разблокировать на ${Store.PASS_MINUTES} минуты (один раз за ночь)") }
                        }
                    }
                }
            }
        }
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    companion object {
        const val EXTRA_WHAT = "what"
        const val EXTRA_PACKAGE = "package"
    }
}
