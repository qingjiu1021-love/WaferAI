package com.example.waferai.ui.account

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.waferai.ui.theme.*
import com.example.waferai.ui.theme.UiText as Text
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun AccountScreen(state: AccountState, model: AccountViewModel, onBack: () -> Unit,
                           onLegal: (String) -> Unit, modifier: Modifier = Modifier) {
    var mode by rememberSaveable { mutableStateOf("login") }
    var target by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var verification by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var agreed by rememberSaveable { mutableStateOf(false) }
    var endpoint by remember(state.endpoint) { mutableStateOf(state.endpoint) }
    var showServer by rememberSaveable { mutableStateOf(state.endpoint.isBlank()) }
    var showDelete by remember { mutableStateOf(false) }
    var showSwitch by remember { mutableStateOf(false) }
    var bindingKind by remember { mutableStateOf<String?>(null) }
    var bindingTarget by remember { mutableStateOf("") }
    var bindingPassword by remember { mutableStateOf("") }
    var bindingRepeat by remember { mutableStateOf("") }
    var bindingCode by remember { mutableStateOf("") }
    var remaining by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        if (!state.connected && state.endpoint.isNotBlank() && !state.busy && !state.polling) model.checkEndpoint(state.endpoint)
    }
    LaunchedEffect(state.user) { password = ""; verification = "" }
    LaunchedEffect(state.bindingComplete) { bindingKind = null; bindingPassword = ""; bindingRepeat = ""; bindingCode = "" }
    LaunchedEffect(state.codeUntil) {
        while (true) {
            remaining = ((state.codeUntil - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0)
            if (remaining == 0) break
            delay(1000)
        }
    }
    val busy = state.busy || state.polling
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()
        .padding(20.dp).padding(bottom = LocalFloatingNavigationPadding.current),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BackButton(onBack)
        StudioHeader(if (state.user == null) "账号登录" else "我的账号", "WAFERAI / ACCOUNT", StudioIcon.PERSON)
        state.message?.let { Text(it, color = if (state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.polling) {
            StudioPanel(Modifier.fillMaxWidth()) {
                Text("正在等待浏览器授权")
                TextButton(model::cancelSocial) { Text("取消等待") }
            }
        }
        if (state.user != null) {
            StudioPanel(Modifier.fillMaxWidth(), MaterialTheme.colorScheme.primaryContainer) {
                Text(state.user.name, style = MaterialTheme.typography.headlineSmall)
                state.user.identities.forEach { Text(it.label, style = MaterialTheme.typography.bodyMedium) }
                Text("当前账号用于身份认证与帮助中心。本机晶圆和聊天记录不随账号同步或切换。", style = MaterialTheme.typography.bodySmall)
            }
            GlassButton(model::help, Modifier.fillMaxWidth(), enabled = !busy) { Text("打开帮助中心") }
            OutlinedButton({ showSwitch = true }, Modifier.fillMaxWidth(), enabled = !busy) { Text("切换账号") }
            SectionHeading("登录与联系方式")
            listOf("phone" to "手机号", "email" to "邮箱").forEach { (kind, title) ->
                val bound = state.user.identities.firstOrNull { it.kind == kind }
                StudioAction(if (bound == null) "绑定$title" else "已绑定$title", bound?.label ?: "验证后可用来登录和找回密码", StudioIcon.PERSON, {
                    if (bound == null && !busy) { bindingTarget = ""; bindingPassword = ""; bindingRepeat = ""; bindingCode = ""; bindingKind = kind }
                })
            }
            OutlinedButton(model::logout, Modifier.fillMaxWidth(), enabled = !busy) { Text("退出登录") }
            TextButton(model::forgetLocalSession, enabled = !busy) { Text("仅移除此设备的登录状态") }
            TextButton({ showDelete = true }, enabled = !busy) { Text("注销账号", color = MaterialTheme.colorScheme.error) }
        } else {
            if (state.saved.isNotEmpty()) OutlinedButton({ showSwitch = true }, Modifier.fillMaxWidth(), enabled = !busy) { Text("选择已登录账号（${state.saved.size}）") }
            StudioPanel(Modifier.fillMaxWidth()) {
                Text("欢迎使用 WaferAI", style = MaterialTheme.typography.titleLarge)
                Text("登录账号即可访问帮助中心。晶圆分析等本机功能仍可直接使用。")
            }
            Row(verticalAlignment = Alignment.Top) {
                Checkbox(agreed, { agreed = it }, enabled = !busy)
                Column(Modifier.weight(1f)) {
                    Text("我已阅读并同意", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                    FlowRow {
                        TextButton({ onLegal("terms") }) { Text("使用条款") }
                        TextButton({ onLegal("privacy") }) { Text("隐私政策") }
                    }
                }
            }
            state.providers.firstOrNull { it.id == "chatgpt" && it.available }?.let { provider ->
                GlassButton({ model.social(provider.id, agreed) }, Modifier.fillMaxWidth(), enabled = !busy && agreed) { Text("使用 ChatGPT 授权登录") }
                Text("无需自行配置服务即可登录。请先勾选条款与隐私政策。", style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("login" to "登录", "register" to "注册", "reset" to "找回密码").forEach { (value, title) ->
                    FilterChip(selected = mode == value, onClick = { mode = value; password = ""; verification = "" }, enabled = !busy, label = { Text(title) })
                }
            }
            OutlinedTextField(target, { target = it.take(254) }, Modifier.fillMaxWidth(), label = { Text("邮箱或手机号") },
                placeholder = { Text("name@example.com / +8613800000000") }, singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            if (mode == "register") OutlinedTextField(nickname, { nickname = it.take(40) }, Modifier.fillMaxWidth(), label = { Text("昵称（可选）") }, singleLine = true, enabled = !busy)
            if (mode != "login") {
                OutlinedTextField(verification, { verification = it.filter(Char::isDigit).take(6) }, Modifier.fillMaxWidth(),
                    label = { Text("6 位验证码") }, singleLine = true, enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedButton({ model.sendCode(target, mode == "reset") }, enabled = !busy && remaining == 0 && target.isNotBlank()) {
                    Text(if (remaining > 0) "$remaining 秒后重试" else "获取验证码")
                }
            }
            OutlinedTextField(password, { password = it.take(128) }, Modifier.fillMaxWidth(),
                label = { Text(if (mode == "reset") "新密码" else "密码") }, singleLine = true, enabled = !busy,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            if (mode != "login") Text("密码需为 10–128 位，包含字母和数字。", style = MaterialTheme.typography.bodySmall)
            GlassButton({ model.submit(mode, target, password, verification, nickname, agreed) }, Modifier.fillMaxWidth(),
                enabled = !busy && agreed && target.isNotBlank() && password.isNotBlank() && (mode == "login" || verification.length == 6)) {
                Text(when(mode) { "register" -> "注册并登录"; "reset" -> "重置密码"; else -> "登录" })
            }
            SectionHeading("其他注册 / 登录方式")
            val providers = state.providers.ifEmpty { listOf(
                com.example.waferai.data.account.LoginProvider("wechat", "微信", false),
                com.example.waferai.data.account.LoginProvider("qq", "QQ", false),
                com.example.waferai.data.account.LoginProvider("facebook", "Facebook", false),
                com.example.waferai.data.account.LoginProvider("telegram", "Telegram", false)) }
            providers.filter { it.id != "chatgpt" }.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { provider ->
                        OutlinedButton({ model.social(provider.id, agreed) }, Modifier.weight(1f), enabled = !busy && agreed) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(provider.name)
                                Text(if (provider.available) "授权登录" else "尚未开通", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            Text("第三方首次授权后自动创建账号。邮箱与手机号需先完成验证码验证。", style = MaterialTheme.typography.bodySmall)
        }
        if (state.development) StudioPanel(Modifier.fillMaxWidth()) {
            Text("本机测试服务", color = MaterialTheme.colorScheme.primary)
            Text("测试验证码写入电脑的开发收件箱，不发送真实邮件或短信。请使用虚构测试信息。", style = MaterialTheme.typography.bodySmall)
        }
        TextButton({ showServer = !showServer }) { Text(if (showServer) "收起服务设置" else "账号服务设置") }
        if (showServer) {
            if (state.user == null && state.endpoint != com.example.waferai.BuildConfig.ACCOUNT_BASE_URL) {
                TextButton({ endpoint = com.example.waferai.BuildConfig.ACCOUNT_BASE_URL; model.checkEndpoint(endpoint) }, enabled = !busy) { Text("使用在线账号服务") }
            }
            OutlinedTextField(endpoint, { endpoint = it.take(250) }, Modifier.fillMaxWidth(), label = { Text("账号服务地址") },
                placeholder = { Text("https://account.example.com") }, singleLine = true, enabled = !busy && state.user == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            GlassButton({ model.checkEndpoint(endpoint) }, enabled = !busy && endpoint.isNotBlank()) { Text("保存并检查服务") }
            if (state.connected) Text("邮箱：${if (state.email) "可用" else "未开通"} · 手机号：${if (state.phone) "可用" else "未开通"}", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (showSwitch) ModalBottomSheet(onDismissRequest = { if (!busy) showSwitch = false }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("切换账号", style = MaterialTheme.typography.titleLarge)
            Text("切换时会联网验证登录。本机晶圆、聊天和模型配置由此设备共用。", style = MaterialTheme.typography.bodySmall)
            state.saved.forEach { saved ->
                val current = state.user?.id == saved.user.id && state.endpoint == saved.origin
                StudioPanel(Modifier.fillMaxWidth()) {
                    Text(saved.user.name, style = MaterialTheme.typography.titleMedium)
                    Text(saved.user.identities.joinToString(" · ") { it.label }, style = MaterialTheme.typography.bodySmall)
                    Text(saved.origin, style = MaterialTheme.typography.labelSmall)
                    Row {
                        TextButton({ showSwitch = false; model.switchAccount(saved.key) }, enabled = !busy && !current) { Text(if (current) "当前账号" else "切换到此账号") }
                        if (!current) TextButton({ model.removeSavedAccount(saved.key) }, enabled = !busy) { Text("移除") }
                    }
                }
            }
            GlassButton({ showSwitch = false; model.addAccount() }, Modifier.fillMaxWidth(), enabled = !busy) { Text("添加账号") }
            Spacer(Modifier.height(12.dp))
        }
    }
    bindingKind?.let { kind ->
        val title = if (kind == "phone") "手机号" else "邮箱"
        val available = state.development || if (kind == "phone") state.phone else state.email
        val validKind = if (kind == "email") bindingTarget.contains('@') else !bindingTarget.contains('@') && bindingTarget.count(Char::isDigit) >= 8
        AlertDialog(onDismissRequest = { if (!busy) bindingKind = null }, title = { Text("绑定$title") },
            text = {
                Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("需在登录后 15 分钟内验证。绑定后，此联系方式可用于登录同一账号及找回密码。")
                    if (!available) Text("$title 验证码服务尚未开通，维护者配置后即可使用。", color = MaterialTheme.colorScheme.error)
                    OutlinedTextField(bindingTarget, { bindingTarget = it.take(254) }, label = { Text(title) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = if (kind == "phone") KeyboardType.Phone else KeyboardType.Email), enabled = !busy)
                    OutlinedTextField(bindingCode, { bindingCode = it.filter(Char::isDigit).take(6) }, label = { Text("6 位验证码") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !busy)
                    TextButton({ model.sendBindingCode(bindingTarget) }, enabled = !busy && available && remaining == 0 && validKind) { Text(if (remaining > 0) "$remaining 秒后重试" else "获取验证码") }
                    Text("为此登录方式设置密码，已有登录方式的密码保持不变。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(bindingPassword, { bindingPassword = it.take(128) }, label = { Text("登录密码（10–128 位，含字母数字）") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), enabled = !busy)
                    OutlinedTextField(bindingRepeat, { bindingRepeat = it.take(128) }, label = { Text("确认密码") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), enabled = !busy)
                    state.message?.let { Text(it, color = if (state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton({ model.bind(bindingTarget, bindingPassword, bindingCode) }, enabled = !busy && available && validKind && bindingCode.length == 6 && bindingPassword.length >= 10 && bindingPassword == bindingRepeat) { Text("确认绑定") } },
            dismissButton = { TextButton({ bindingKind = null }, enabled = !busy) { Text("取消") } })
    }
    if (showDelete) AlertDialog(onDismissRequest = { showDelete = false }, title = { Text("注销账号？") },
        text = { Text("账号及账号服务中的身份、登录会话将被删除。此操作无法撤回。本机晶圆、聊天、模型配置和导出文件保留。") },
        confirmButton = { TextButton({ showDelete = false; model.deleteAccount() }) { Text("确认注销", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ showDelete = false }) { Text("取消") } })
}
