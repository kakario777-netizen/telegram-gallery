package com.kakario.tggallery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

@Composable
fun Loading(msg: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(msg)
    }
}

@Composable
fun FormPage(title: String, desc: String, content: @Composable ColumnScope.() -> Unit) {
    val err by Td.error.collectAsState()
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(desc, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        content()
        err?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun KeyScreen() {
    var id by remember { mutableStateOf("") }
    var hash by remember { mutableStateOf("") }
    FormPage("처음 설정", "my.telegram.org에서 받은 api_id와 api_hash를 넣어주세요. 이 폰 안에만 저장돼요.") {
        OutlinedTextField(id, { id = it.filter(Char::isDigit) }, label = { Text("api_id (숫자)") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(hash, { hash = it.trim() }, label = { Text("api_hash") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Button(onClick = { id.toIntOrNull()?.let { Td.saveKeys(it, hash) } },
            enabled = id.isNotEmpty() && hash.length >= 16, modifier = Modifier.fillMaxWidth()) { Text("다음") }
    }
}

@Composable
fun PhoneScreen() {
    var phone by remember { mutableStateOf("+82") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    FormPage("텔레그램 로그인", "전화번호를 국가번호와 함께 넣어주세요. 예: +821012345678") {
        OutlinedTextField(phone, { phone = it }, label = { Text("전화번호") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            busy = true; Td.error.value = null
            scope.launch {
                val p = phone.replace(" ", "").replace("-", "").let { if (it.startsWith("010")) "+82" + it.drop(1) else it }
                try { Td.send(TdApi.SetAuthenticationPhoneNumber(p, null)) } catch (e: Exception) { Td.error.value = e.message }
                busy = false
            }
        }, enabled = !busy && phone.length > 6, modifier = Modifier.fillMaxWidth()) { Text("인증번호 받기") }
    }
}

@Composable
fun CodeScreen() {
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    FormPage("인증번호", "텔레그램 앱(또는 문자)으로 온 숫자를 넣어주세요.") {
        OutlinedTextField(code, { code = it.filter(Char::isDigit) }, label = { Text("인증번호") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            busy = true; Td.error.value = null
            scope.launch {
                try { Td.send(TdApi.CheckAuthenticationCode(code)) } catch (e: Exception) { Td.error.value = e.message }
                busy = false
            }
        }, enabled = !busy && code.length >= 4, modifier = Modifier.fillMaxWidth()) { Text("확인") }
    }
}

@Composable
fun PasswordScreen(hint: String) {
    var pw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    FormPage("2단계 비밀번호", if (hint.isNotEmpty()) "힌트: $hint" else "텔레그램에 설정한 2단계 인증 비밀번호를 넣어주세요.") {
        OutlinedTextField(pw, { pw = it }, label = { Text("비밀번호") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            busy = true; Td.error.value = null
            scope.launch {
                try { Td.send(TdApi.CheckAuthenticationPassword(pw)) } catch (e: Exception) { Td.error.value = e.message }
                busy = false
            }
        }, enabled = !busy && pw.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("확인") }
    }
}

