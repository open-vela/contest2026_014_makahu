package com.mocharealm.foundation.fabric.buffer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

import com.mocharealm.foundation.fabric.buffer.BufferApplication
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferLlmClient
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferLlmConfig
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferMiMo
@Composable
internal fun BufferLlmSettingsPanel() {
    val repository = (LocalContext.current.applicationContext as BufferApplication).repository
    val initial = remember { runCatching { repository.llmConfig() }.getOrElse { BufferLlmConfig("", "", "") } }
    var url by rememberSaveable { mutableStateOf(initial.baseUrl) }
    var model by rememberSaveable { mutableStateOf(initial.model) }
    var key by remember { mutableStateOf("") }
    var hasSavedKey by remember { mutableStateOf(initial.apiKey.isNotBlank()) }
    var status by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("AI 服务", style = MaterialTheme.typography.headlineSmall)
        Text("MiMo 2.5 负责分类、回答与周总结；MiMo 2.5 ASR 负责语音转文字。共用下方服务地址和密钥。", style = MaterialTheme.typography.bodyMedium)
        TextButton(enabled = !testing, onClick = {
            url = BufferMiMo.baseUrl; model = BufferMiMo.model
            key = ""; hasSavedKey = false
            status = "已选择 MiMo 2.5，请填写对应密钥后保存"
        }) { Text("使用 MiMo 2.5") }
        OutlinedTextField(url, { url = it }, label = { Text("Base URL") },
            placeholder = { Text("https://api.xiaomimimo.com/v1") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(model, { model = it }, label = { Text("大模型名称") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(key, { key = it }, label = { Text("API Key") },
            supportingText = { Text(if (hasSavedKey) "已保存密钥，留空可保留" else "请输入 MiMo 平台 API Key") },
            singleLine = true, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Button(enabled = !testing, onClick = {
            try {
                repository.saveLlmConfig(url, model, if (hasSavedKey && key.isBlank()) null else key)
                hasSavedKey = repository.llmConfig().apiKey.isNotBlank()
                key = ""; status = "已保存，待处理任务会自动继续"
            } catch (_: Exception) { status = "无法保存，请检查接口地址、模型名称和密钥" }
        }, modifier = Modifier.fillMaxWidth()) { Text("保存服务配置") }
        OutlinedButton(enabled = !testing, onClick = {
            testing = true
            scope.launch {
                status = withContext(Dispatchers.IO) {
                    try {
                        val savedKey = if (hasSavedKey && key.isBlank()) repository.llmConfig().apiKey else key
                        val result = BufferLlmClient.complete(BufferLlmConfig(url, model, savedKey),
                            "连接检查，只输出 {\"ok\":true}。", JSONObject().put("task", "connection_check"))
                        if (result.opt("ok") == true) "大模型连接成功；语音识别会在转写录音时验证" else "已收到响应，但结果格式不符合约定"
                    } catch (_: Exception) { "连接失败，请检查服务地址、模型和密钥" }
                }
                testing = false
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(if (testing) "正在测试…" else "测试连接") }
        if (testing) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodyMedium)
        TextButton(enabled = !testing, onClick = {
            repository.clearLlmConfig(); url = ""; model = ""; key = ""; hasSavedKey = false
            status = "已移除配置，尚未完成的任务会保留"
        }) { Text("移除服务配置") }
    }
}
