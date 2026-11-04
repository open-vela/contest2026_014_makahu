package com.mocharealm.foundation.fabric.buffer.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mocharealm.foundation.fabric.buffer.BufferApplication
import com.mocharealm.foundation.fabric.buffer.BufferResearchCalendar
import com.mocharealm.foundation.fabric.buffer.domain.ResearchWeeklyPlan
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

@Composable
internal fun ResearchWeeklyPanel() {
    val app=LocalContext.current.applicationContext as BufferApplication
    val repository=app.repository;val scope=rememberCoroutineScope()
    var plans by remember { mutableStateOf(emptyList<ResearchWeeklyPlan>()) }
    var cards by remember { mutableStateOf(emptyList<BufferCard>()) }
    var calendars by remember { mutableStateOf(emptyList<BufferResearchCalendar.Calendar>()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var day by remember { mutableStateOf(7) };var time by remember { mutableStateOf("14:00") }
    var minutes by remember { mutableStateOf(90) };var external by remember { mutableStateOf(false) }
    var calendar by remember { mutableStateOf<Long?>(null) };var editId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") };var busy by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf("") }
    var zone by remember { mutableStateOf(ZoneId.systemDefault().id) }
    suspend fun reload() {
        val data=withContext(Dispatchers.IO){repository.researchWeeklyPlans() to repository.cardsForMode("rabbit_hole",100)}
        plans=data.first;cards=data.second
    }
    suspend fun loadCalendars() {
        calendars=withContext(Dispatchers.IO){BufferResearchCalendar(app,repository).calendars()}
        if(calendars.none {it.id==calendar})calendar=calendars.firstOrNull()?.id
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if(result.values.all {it}) scope.launch { try {loadCalendars()}catch(_:Exception){message="日历暂不可用，请稍后重试"} }
        else {external=false;message="未授权日历；仍可保存 Buffer 内预约"}
    }
    LaunchedEffect(Unit){while(true){try{reload()}catch(_:Exception){message="预约暂不可读"};delay(2000)}}
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("每周研究预约",style=MaterialTheme.typography.titleLarge)
        Text("每次留 1–2 小时，到点提醒后再确认开始；专注或睡前模式中暂缓。")
        Box {
            OutlinedButton(onClick={menu="card"}){Text(cards.find {it.cardId==selected}?.title ?: "选择问题或灵感")}
            DropdownMenu(expanded=menu=="card",onDismissRequest={menu=""}) {
                cards.forEach { card -> DropdownMenuItem(text={Text(card.title)},onClick={selected=card.cardId;menu=""}) }
            }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Box {
                OutlinedButton(onClick={menu="day"}){Text(listOf("周一","周二","周三","周四","周五","周六","周日")[day-1])}
                DropdownMenu(expanded=menu=="day",onDismissRequest={menu=""}) {
                    (1..7).forEach { d -> DropdownMenuItem(text={Text(listOf("周一","周二","周三","周四","周五","周六","周日")[d-1])},onClick={day=d;menu=""}) }
                }
            }
            OutlinedTextField(value=time,onValueChange={time=it},label={Text("时间 HH:mm")},singleLine=true,modifier=Modifier.weight(1f))
            Box {
                OutlinedButton(onClick={menu="duration"}){Text("$minutes 分钟")}
                DropdownMenu(expanded=menu=="duration",onDismissRequest={menu=""}) {
                    listOf(60,90,120).forEach { m -> DropdownMenuItem(text={Text("$m 分钟")},onClick={minutes=m;menu=""}) }
                }
            }
        }
        Text("时区：$zone。夏令时不存在或重复的本地时刻跳过该次提醒。",style=MaterialTheme.typography.bodySmall)
        Row {
            Checkbox(checked=external,onCheckedChange={ checked ->
                external=checked
                if(checked) {
                    if(listOf(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR).all {ContextCompat.checkSelfPermission(app,it)==PackageManager.PERMISSION_GRANTED})
                        scope.launch { try{loadCalendars()}catch(_:Exception){message="日历读取失败"} }
                    else permission.launch(arrayOf(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR))
                }
            });Text("同时写入系统日历（每周重复，可撤销）")
        }
        if(external) Box {
            OutlinedButton(onClick={menu="calendar"}){Text(calendars.find {it.id==calendar}?.title ?: "选择可写日历")}
            DropdownMenu(expanded=menu=="calendar",onDismissRequest={menu=""}) {
                calendars.forEach { c -> DropdownMenuItem(text={Text(c.title)},onClick={calendar=c.id;menu=""}) }
            }
        }
        Button(enabled=!busy && selected!=null && (!external || calendar!=null),onClick={scope.launch {
            busy=true
            try {
                withContext(Dispatchers.IO){repository.saveResearchWeeklyPlan(requireNotNull(selected),day,time,minutes,zone,
                    if(external)requireNotNull(calendar) else null,editId)}
                editId=null;message="预约已保存；系统日历写入结果显示在下方";app.careScheduler.requestReconcile();reload()
            }catch(e:Exception){message=e.message ?: "保存失败"}finally{busy=false}
        }}) {Text(if(editId==null)"确认每周预约" else "确认修改预约")}
        plans.forEach { p ->
            OutlinedCard {
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(p.title,style=MaterialTheme.typography.titleMedium)
                    Text("每周${listOf("一","二","三","四","五","六","日")[p.weekday-1]} ${p.time} · ${p.minutes} 分钟 · ${p.zone}")
                    Text(if(!p.enabled)"已停用，等待清理日历" else when(p.calendarState){"synced"->"系统日历已写入";"local"->"Buffer 内预约";"failed"->p.problem;else->"等待同步系统日历"})
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        val current=System.currentTimeMillis()
                        if(p.enabled && p.startedAt==0L && current>=p.nextAt && current<p.endAt) TextButton(onClick={scope.launch {
                            try{withContext(Dispatchers.IO){repository.startResearchWeekly(p.id,p.revision,p.nextAt)};app.careScheduler.requestReconcile();reload()}
                            catch(e:Exception){message=e.message ?: "当前无法开始研究"}
                        }}){Text("开始研究")}
                        if(p.enabled) TextButton(onClick={
                            editId=p.id;selected=p.cardId;day=p.weekday;time=p.time;minutes=p.minutes
                            external=p.calendarId!=null;calendar=p.calendarId;zone=p.zone
                            if(external)scope.launch {try{loadCalendars()}catch(_:Exception){message="日历暂不可用"}}
                        }){Text("修改")}
                        TextButton(onClick={scope.launch {
                            try{withContext(Dispatchers.IO){repository.cancelResearchWeeklyPlan(p.id)};app.careScheduler.requestReconcile();reload()}
                            catch(_:Exception){message="撤销失败，请重试"}
                        }}){Text(if(p.enabled)"撤销预约" else "重试清理")}
                    }
                }
            }
        }
        if(message.isNotEmpty())Text(message,style=MaterialTheme.typography.bodySmall)
    }
}
