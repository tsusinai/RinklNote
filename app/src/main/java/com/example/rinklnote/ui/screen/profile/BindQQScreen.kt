package com.example.rinklnote.ui.screen.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.rinklnote.ui.viewmodel.AuthEvent
import com.example.rinklnote.ui.viewmodel.AuthViewModel

@Composable
fun BindQQScreen(
    viewModel: AuthViewModel,
    onDismiss: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.isQQBound) {
        if (state.isQQBound) onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("绑定QQ号") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("绑定后即可通过QQ机器人快捷记账")
                OutlinedTextField(
                    value = state.qqNumber,
                    onValueChange = { viewModel.onEvent(AuthEvent.QQNumberChanged(it)) },
                    label = { Text("QQ号") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                if (state.error != null) {
                    Text(
                        text = state.error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (state.successMessage != null) {
                    Text(
                        text = state.successMessage!!,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Button(
                    onClick = { viewModel.onEvent(AuthEvent.BindQQ) },
                    enabled = !state.isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("绑定") }
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
