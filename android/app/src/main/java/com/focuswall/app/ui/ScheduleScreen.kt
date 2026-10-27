package com.focuswall.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import com.focuswall.app.core.Schedule
import com.focuswall.app.data.Store
import com.focuswall.app.data.SyncManager
import java.util.UUID

private val DAY_NAMES = listOf("Вс", "Пн", "Вт", "Ср", "Чт", "Пт", "Сб")
private val DAY_ORDER = listOf(1, 2, 3, 4, 5, 6, 0)
private val TIME = Regex("^([01]?\\d|2[0-3]):[0-5]\\d$")

private class ScheduleDraft(s: Schedule) {
    val id = s.id
    var name by mutableStateOf(s.name)
    var start by mutableStateOf(s.start)
    var end by mutableStateOf(s.end)
    var enabled by mutableStateOf(s.enabled)
    var hard by mutableStateOf(s.hard)
    val days = mutableStateListOf<Int>().apply { addAll(s.days) }
    val listIds = mutableStateListOf<String>().apply { addAll(s.listIds) }

    fun build(): Schedule {
        require(TIME.matches(start) && TIME.matches(end)) { "Время в формате ЧЧ:ММ, например 21:00" }
        val pad = { t: String -> t.padStart(5, '0') }
        return Schedule(id, name.trim().ifEmpty { "Расписание" }, listIds.toList(), days.sorted(), pad(start), pad(end), enabled, hard)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleScreen() {
    val ctx = LocalContext.current
    val store = remember { Store.get(ctx) }
    val lists = remember { store.config.blocklists }
    val drafts = remember { mutableStateListOf<ScheduleDraft>().apply { addAll(store.config.schedules.map(::ScheduleDraft)) } }
    var message by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Muted("Расписание включается само на всех устройствах, где выполнен вход. В жёстком режиме его нельзя отключить, пока оно действует.")
        drafts.forEach { d ->
            SectionCard {
                OutlinedTextField(d.name, { d.name = it }, Modifier.fillMaxWidth(), label = { Text("Название") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(d.start, { d.start = it }, Modifier.width(110.dp), label = { Text("С") }, singleLine = true)
                    OutlinedTextField(d.end, { d.end = it }, Modifier.width(110.dp), label = { Text("До") }, singleLine = true)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DAY_ORDER.forEach { day ->
                        FilterChip(day in d.days, { if (day in d.days) d.days.remove(day) else d.days.add(day) }, label = { Text(DAY_NAMES[day]) })
                    }
                }
                Muted("Списки")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    lists.forEach { l ->
                        FilterChip(l.id in d.listIds, { if (l.id in d.listIds) d.listIds.remove(l.id) else d.listIds.add(l.id) }, label = { Text(l.name) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(d.enabled, { d.enabled = it })
                    Text("Включено")
                    Switch(d.hard, { d.hard = it })
                    Text("Жёсткий")
                }
                TextButton(onClick = { drafts.remove(d) }) { Text("Удалить расписание") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                drafts += ScheduleDraft(Schedule(UUID.randomUUID().toString(), "Рабочее время", emptyList(), listOf(1, 2, 3, 4, 5), "09:00", "18:00"))
            }) { Text("+ Расписание") }
            Button(onClick = {
                try {
                    store.saveUserConfig(store.config.copy(schedules = drafts.map { it.build() }))
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
}
