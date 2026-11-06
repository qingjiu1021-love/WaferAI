package com.example.waferai.ui.settings

import com.example.waferai.ui.theme.GlassTextButton as TextButton
import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.waferai.data.account.*
import com.example.waferai.ui.theme.*
import com.example.waferai.ui.theme.UiText as Text
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class UserProfileState(val key: String = "", val profile: LocalUserProfile = LocalUserProfile(),
    val busy: Boolean = true, val error: String? = null, val message: String? = null)

internal class UserProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val store = LocalUserProfileStore(application)
    private val mutable = MutableStateFlow(UserProfileState())
    val state = mutable.asStateFlow()
    fun select(origin: String, id: String?) {
        val key = store.key(origin, id)
        if (mutable.value.key == key) return
        mutable.value = UserProfileState(key = key)
        viewModelScope.launch {
            try { val profile = withContext(Dispatchers.IO) { store.read(key) }; if (mutable.value.key == key) mutable.value = UserProfileState(key, profile, false) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (mutable.value.key == key) mutable.value = UserProfileState(key, busy = false, error = "无法读取本机个人资料。") }
        }
    }
    fun save(name: String, avatar: String?) {
        val old = mutable.value
        if (old.busy) return
        val profile = LocalUserProfile(name.trim(), avatar)
        mutable.value = old.copy(busy = true, error = null)
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { store.write(old.key, profile) }; if (mutable.value.key == old.key) mutable.value = old.copy(profile = profile, message = "个人资料已保存。") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (mutable.value.key == old.key) mutable.value = old.copy(error = error.message ?: "保存失败，请重试。") }
        }
    }
    suspend fun readAvatar(uri: Uri): String = withContext(Dispatchers.IO) { store.avatar(uri) }
}

@Composable internal fun UserAvatar(profile: LocalUserProfile, size: Dp = 64.dp) {
    val bitmap = remember(profile.avatar) { runCatching { profile.avatar?.let { Base64.decode(it, Base64.NO_WRAP) }?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }.getOrNull() }
    if (bitmap == null) StudioBadge(StudioIcon.PERSON, size.value.toInt())
    else Image(bitmap, "用户头像", Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
}

@Composable internal fun UserProfileScreen(state: UserProfileState, model: UserProfileViewModel, fallback: String,
    onBack: () -> Unit, modifier: Modifier = Modifier) {
    var name by remember(state.key, state.profile.name) { mutableStateOf(state.profile.name.ifBlank { fallback }) }
    var avatar by remember(state.key, state.profile.avatar) { mutableStateOf(state.profile.avatar) }
    var photoError by remember { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { scope.launch {
            reading = true; photoError = null
            try { avatar = model.readAvatar(it) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { photoError = error.message ?: "无法读取照片。" }
            finally { reading = false }
        } }
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(20.dp)
        .padding(bottom = LocalFloatingNavigationPadding.current), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        BackButton(onBack)
        StudioHeader("个人资料", "WAFERAI / PROFILE", StudioIcon.PERSON)
        StudioPanel(Modifier.fillMaxWidth()) {
            UserAvatar(LocalUserProfile(name, avatar), 88.dp)
            Row {
                TextButton({ picker.launch(arrayOf("image/*")) }, enabled = !state.busy && !reading) { Text("选择头像") }
                if (avatar != null) TextButton({ avatar = null }, enabled = !state.busy && !reading) { Text("移除头像") }
            }
            Text("照片会裁成方形头像，仅保存在本机。", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth(), label = { Text("昵称") },
            supportingText = { Text("${name.length}/40") }, singleLine = true, enabled = !state.busy && !reading)
        Text("头像和昵称按当前账号分别保存在这台设备，未登录时使用访客资料。不会修改 QQ、微信等平台的原始资料。", style = MaterialTheme.typography.bodySmall)
        if (state.busy || reading) LinearProgressIndicator(Modifier.fillMaxWidth())
        (photoError ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        GlassButton({ model.save(name, avatar) }, Modifier.fillMaxWidth(), enabled = !state.busy && !reading && name.isNotBlank()) { Text("保存个人资料") }
    }
}
