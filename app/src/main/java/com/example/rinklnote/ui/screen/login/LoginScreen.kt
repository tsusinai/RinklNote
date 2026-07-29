package com.example.rinklnote.ui.screen.login

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.rinklnote.ui.viewmodel.AuthEvent
import com.example.rinklnote.ui.viewmodel.AuthViewModel

@Composable
fun LoginScreen(
    viewModel: AuthViewModel,
    onDismiss: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.isLoggedIn) {
        if (state.isLoggedIn) onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("账号登录") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.phone,
                    onValueChange = { viewModel.onEvent(AuthEvent.PhoneChanged(it)) },
                    label = { Text("手机号") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                )
                OutlinedTextField(
                    value = state.password,
                    onValueChange = { viewModel.onEvent(AuthEvent.PasswordChanged(it)) },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                if (state.error != null) {
                    Text(
                        text = state.error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.onEvent(AuthEvent.Login) },
                        enabled = !state.isLoading,
                        modifier = Modifier.weight(1f)
                    ) { Text("登录") }
                    OutlinedButton(
                        onClick = { viewModel.onEvent(AuthEvent.Register) },
                        enabled = !state.isLoading,
                        modifier = Modifier.weight(1f)
                    ) { Text("注册") }
                }
                if (state.isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
