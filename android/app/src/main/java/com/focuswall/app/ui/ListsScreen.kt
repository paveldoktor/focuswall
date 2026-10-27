package com.focuswall.app.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.focuswall.app.core.BlockList
import com.focuswall.app.core.ListMode
import com.focuswall.app.core.Rules
import com.focuswall.app.data.Store
import com.focuswall.app.data.SyncManager
import java.util.UUID

private class ListDraft(l: BlockList) {
    val id = l.id
    var name by mutableStateOf(l.name)
    var mode by mutableStateOf(l.mode)
    var domains by mutableStateOf(l.domains.joinToString("\n"))
    val apps = mutableStateListOf<String>().apply { addAll(l.androidApps) }
    val desktopApps = l.desktopApps

    fun build() = BlockList(
        id = id,
        name = name.trim().ifEmpty { "Без названия" },
        mode = mode,
        domains = domains.split('\n', ',').map { Rules.normalizeDomain(it) }.filter { it.isNotEmpty() }.distinct(),
        androidApps = apps.distinct(),
        desktopApps = desktopApps,
    )
}

@Composable
fun ListsScreen() {
    val ctx = LocalContext.current
    val store = remember { Store.get(ctx) }
    val drafts = remember { mutableStateListOf<ListDraft>().apply { addAll(store.config.blocklists.map(::ListDraft)) } }
    var message by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<ListDraft?>(null) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Muted("«Блокировать перечисленное» — обычный чёрный список. «Всё, кроме перечисленного» оставляет доступными только выбранное.")
        drafts.forEach { d ->
            SectionCard {
                OutlinedTextField(d.name, { d.name = it }, Modifier.fillMaxWidth(), label = { Text("Название") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(d.mode == ListMode.BLOCK, { d.mode = ListMode.BLOCK }, label = { Text("Блокировать перечисленное") })
                    FilterChip(d.mode == ListMode.ALLOW, { d.mode = ListMode.ALLOW }, label = { Text("Всё, кроме") })
                }
                OutlinedTextField(
                    d.domains, { d.domains = it }, Modifier.fillMaxWidth().heightIn(min = 90.dp),
                    label = { Text("Сайты, по одному в строке") },
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { picking = d }) { Text("Приложения: ${d.apps.size}") }
                    TextButton(onClick = { drafts.remove(d) }) { Text("Удалить список") }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                drafts += ListDraft(BlockList(UUID.randomUUID().toString(), "Новый список"))
            }) { Text("+ Список") }
            Button(onClick = {
                try {
                    val newLists = drafts.map { it.build() }
                    val ids = newLists.map { it.id }.toSet()
                    val c = store.config
                    store.saveUserConfig(
                        c.copy(blocklists = newLists, schedules = c.schedules.map { s -> s.copy(listIds = s.listIds.filter { it in ids }) }),
                    )
                    SyncManager.syncAsync(ctx, 0)
                    message = "Сохранено"
                    isError = false
                } catch (e: Exception) {
                    message = e.message ?: e.toString()
                    isError = true
                }
            }) { Text("Сохранить") }
        }
        if (isError) ErrorText(message) else Muted(message)
    }

    picking?.let { d -> AppPicker(d.apps) { picking = null } }
}

private data class AppInfo(val pkg: String, val label: String)

@Composable
private fun AppPicker(selected: MutableList<String>, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val apps = remember {
        val pm = ctx.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.pkg != ctx.packageName }
            .distinctBy { it.pkg }
            .sortedWith(compareByDescending<AppInfo> { it.pkg in selected }.thenBy { it.label.lowercase() })
    }
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDone,
        confirmButton = { TextButton(onClick = onDone) { Text("Готово") } },
        title = { Text("Приложения") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Поиск") }, singleLine = true)
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(apps.filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }) { app ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (app.pkg in selected) selected.remove(app.pkg) else selected.add(app.pkg)
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(app.pkg in selected, null)
                            Column {
                                Text(app.label)
                                Muted(app.pkg)
                            }
                        }
                    }
                }
            }
        },
    )
}
