package com.mocharealm.foundation.fabric.buffer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mocharealm.foundation.fabric.buffer.BufferApplication
import com.mocharealm.foundation.fabric.buffer.domain.*
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.*

@Composable internal fun HealthQuantCardPanel(card:BufferCard) {
    if(card.kind!="care_card" || !card.classificationConfirmed || card.state=="deleted" ||
        card.healthCategory !in setOf("nutrition","hydration","sleep","exercise","symptom"))return
    val repository=(LocalContext.current.applicationContext as BufferApplication).repository
    var view by remember(card.cardId){mutableStateOf<HealthQuantView?>(null)}
    var error by remember(card.cardId){mutableStateOf("")}
    var edit by remember(card.cardId){mutableStateOf(false)}
    var editView by remember(card.cardId){mutableStateOf<HealthQuantView?>(null)}
    val scope=rememberCoroutineScope()
    LaunchedEffect(card.cardId) {
        while(true){
            try {view=withContext(Dispatchers.IO){repository.healthQuantView(card.cardId)}}
            catch (_:Exception){error="健康记录暂不可用，请稍后重试"}
            delay(1500)
        }
    }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("健康数值",style=MaterialTheme.typography.titleMedium)
        val current=view?.record
        if(current==null)Text("确认分类后会尝试从原文整理数值。也可以手动填写；没有记录的数值保持未知。")
        else {
            Text(when(current.state){"confirmed"->"已确认 · ${current.data.date}";"retracted"->"已撤回，不计入汇总；已导出的健康平台记录需另行管理";else->"待核对的提案 · ${current.data.date}"})
            if(view?.local==false)Text("来自已同步设备的记录；修改或撤回后会保存你的新确认。")
            Text(current.data.description())
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            TextButton(onClick={editView=view;edit=true}){Text(if(current==null)"填写数值" else "核对 / 修改数值")}
            if(current!=null && current.state!="retracted")TextButton(onClick={val expected=view;scope.launch {
                try {withContext(Dispatchers.IO){repository.retractHealthQuant(card.cardId,expected?.localRevision ?: 0,expected?.eventId)};view=withContext(Dispatchers.IO){repository.healthQuantView(card.cardId)}}
                catch (_:Exception){error="撤回未完成，请重新查看记录后重试"}
            }}){Text("撤回数值")}
        }
        if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
    }
    if(edit) {
        val expected=editView
        val zone=ZoneId.systemDefault().id
        val initial=expected?.record?.data ?: HealthQuant(Instant.ofEpochMilli(card.createdAt).atZone(ZoneId.of(zone)).toLocalDate().toString(),zone,
            listOf(HealthQuant.Entry(card.title,null,"","reported","",emptyMap())))
        HealthQuantEditor(initial,saveError=error,onDismiss={edit=false},onSave={data ->
            scope.launch {
                try {withContext(Dispatchers.IO){repository.confirmHealthQuant(card.cardId,expected?.localRevision ?: 0,data,expected?.eventId.orEmpty())}
                    view=withContext(Dispatchers.IO){repository.healthQuantView(card.cardId)};edit=false;error=""}
                catch (e:Exception){error=e.message ?: "保存失败，请重试"}
            }
        })
    }
}

@Composable private fun HealthQuantEditor(initial:HealthQuant,saveError:String,onDismiss:()->Unit,onSave:(HealthQuant)->Unit) {
    var date by remember{mutableStateOf(initial.date)}
    var entries by remember{mutableStateOf(initial.entries)}
    var index by remember{mutableIntStateOf(0)}
    var error by remember{mutableStateOf("")}
    var name by remember(index,entries.size){mutableStateOf(entries[index].name)}
    var amount by remember(index,entries.size){mutableStateOf(entries[index].amount?.toString().orEmpty())}
    var unit by remember(index,entries.size){mutableStateOf(entries[index].unit)}
    var estimated by remember(index,entries.size){mutableStateOf(entries[index].basis=="estimated")}
    var note by remember(index,entries.size){mutableStateOf(entries[index].note)}
    var fields by remember(index,entries.size){mutableStateOf(HealthQuant.labels.keys.associateWith { entries[index].values[it]?.toString().orEmpty() })}
    fun snapshot():HealthQuant.Entry {
        fun number(text:String):Double? = if(text.isBlank())null else requireNotNull(text.toDoubleOrNull()){ "请输入有效数字" }
        return HealthQuant.Entry(name,number(amount),unit,if(estimated)"estimated" else "reported",note,
            fields.filterValues {it.isNotBlank()}.mapValues { requireNotNull(number(it.value)) })
    }
    fun commitEditor(){entries=entries.toMutableList().also {it[index]=snapshot()}}
    AlertDialog(onDismissRequest=onDismiss,title={Text("核对健康记录")},text={
        Column(Modifier.fillMaxWidth().heightIn(max=520.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("空白表示没有记录。估算值会单独汇总；请按原文核对份量和日期。")
            OutlinedTextField(date,{date=it},label={Text("记录日期 YYYY-MM-DD")},singleLine=true)
            Text("时区：${initial.zone}")
            Text("第 ${index+1} / ${entries.size} 项")
            Row {
                TextButton(enabled=index>0,onClick={try{commitEditor();index--;error=""}catch(e:Exception){error=e.message.orEmpty()}}){Text("上一项")}
                TextButton(enabled=index<entries.lastIndex,onClick={try{commitEditor();index++;error=""}catch(e:Exception){error=e.message.orEmpty()}}){Text("下一项")}
                TextButton(enabled=entries.size<16,onClick={try{commitEditor();entries=entries+HealthQuant.Entry("新食物或活动",null,"","reported","",emptyMap());index=entries.lastIndex;error=""}catch(e:Exception){error=e.message.orEmpty()}}){Text("添加一项")}
            }
            OutlinedTextField(name,{name=it},label={Text("食物 / 活动名称")})
            OutlinedTextField(amount,{amount=it},label={Text("份量（可空）")},singleLine=true)
            OutlinedTextField(unit,{unit=it},label={Text("份量单位，如 g、ml、碗、分钟")},singleLine=true)
            Row {Checkbox(estimated,{estimated=it});Text("这项数值包含估算")}
            HealthQuant.labels.forEach { (key,label)->OutlinedTextField(fields[key].orEmpty(),{value->fields=fields+(key to value)},
                label={Text("$label（${HealthQuant.units.getValue(key)}，可空）")},singleLine=true) }
            OutlinedTextField(note,{note=it},label={Text("估算依据 / 备注")})
            TextButton(enabled=entries.size>1,onClick={entries=entries.filterIndexed {i,_->i!=index};index=0}){Text("移除这一项")}
            if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
            if(saveError.isNotBlank())Text(saveError,color=MaterialTheme.colorScheme.error)
        }
    },confirmButton={TextButton(onClick={try {
        commitEditor();val result=HealthQuant.parse(HealthQuant(date,initial.zone,entries).json().toString());onSave(result)
    }catch(e:Exception){error=e.message ?: "请核对日期和数值"}}){Text("确认并计入汇总")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}

@Composable internal fun HealthQuantSummaryPanel() {
    val repository=(LocalContext.current.applicationContext as BufferApplication).repository
    var today by remember{mutableStateOf(emptyList<HealthQuantTotal>())}
    var week by remember{mutableStateOf(emptyList<HealthQuantTotal>())}
    var error by remember{mutableStateOf("")}
    LaunchedEffect(repository){while(true){
        try {
            val date=LocalDate.now()
            val result=withContext(Dispatchers.IO){repository.healthQuantTotals(date,date) to repository.healthQuantTotals(date.minusDays(6),date)}
            today=result.first;week=result.second;error=""
        }catch(_:Exception){error="数值汇总暂不可用"}
        delay(2000)
    }}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("健康数值汇总",style=MaterialTheme.typography.titleLarge)
        Text("汇总本机及已同步来源的确认记录，撤回的记录不计入；没有数值表示尚未记录。")
        listOf("今天" to today,"最近七天" to week).forEach { (label,totals)->
            Text(label,style=MaterialTheme.typography.titleMedium)
            if(totals.isEmpty())Text("暂无已确认数值")
            totals.forEach { total ->
                val unit=HealthQuant.units.getValue(total.metric)
                Text(HealthQuant.labels.getValue(total.metric)+(if(total.metric=="weight_kg")"（最近记录）" else "")+"："+
                    listOfNotNull(total.reported?.let{"原文或用户提供 $it$unit"},total.estimated?.let{"估算 $it$unit"}).joinToString(" · ")+
                    if(total.missingEntries>0)"（已记录小计，另有 ${total.missingEntries} 项缺少数值）" else "")
            }
        }
        if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
    }
}
