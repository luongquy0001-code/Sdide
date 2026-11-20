package com.example.sdide.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.sdide.engine.SdOptimizationResult
import com.example.sdide.engine.SdTargetLanguage
import com.example.ui.theme.JetBrainsMonoFamily

@Composable
fun SdCodeToolsDialog(
  state: IdeUiState,
  onDismiss: () -> Unit,
  onTargetChange: (SdTargetLanguage) -> Unit,
  onGenerate: (SdTargetLanguage) -> Unit,
  onPreviewOptimization: () -> Unit,
  onDismissOptimizationPreview: () -> Unit,
  onApplyOptimization: () -> Unit,
) {
  BackHandler(onBack = onDismiss)
  var languageMenuExpanded by remember { mutableStateOf(false) }
  val scrollState = rememberScrollState()
  val preview = state.optimizationPreview

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.9f),
      shape = RoundedCornerShape(16.dp),
      color = MaterialTheme.colorScheme.surface,
    ) {
      Column(modifier = Modifier.padding(16.dp)) {
        Text("Sinh mã và tối ưu ngoại tuyến", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
          "Backend sẽ báo rõ nếu gặp tính năng chưa hỗ trợ.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Box {
            OutlinedButton(onClick = { languageMenuExpanded = true }) {
              Text(state.targetLanguage.displayName)
            }
            DropdownMenu(
              expanded = languageMenuExpanded,
              onDismissRequest = { languageMenuExpanded = false },
            ) {
              SdTargetLanguage.entries.forEach { language ->
                DropdownMenuItem(
                  text = { Text(language.displayName) },
                  onClick = {
                    languageMenuExpanded = false
                    onTargetChange(language)
                  },
                )
              }
            }
          }
          Button(
            onClick = { onGenerate(state.targetLanguage) },
            enabled = !state.isGeneratingCode,
          ) {
            if (state.isGeneratingCode) {
              CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
              )
            } else {
              Text("Sinh mã")
            }
          }
          OutlinedButton(
            onClick = onPreviewOptimization,
            enabled = preview == null,
          ) {
            Text("Tối ưu")
          }
        }
        Spacer(Modifier.height(8.dp))
        Surface(
          modifier = Modifier.weight(1f).fillMaxWidth(),
          color = MaterialTheme.colorScheme.background,
          shape = RoundedCornerShape(10.dp),
        ) {
          SelectionContainer {
            Column(modifier = Modifier.verticalScroll(scrollState).padding(12.dp)) {
              when {
                preview != null -> OptimizationDiff(preview)
                state.codeGenerationError != null -> Text(
                  state.codeGenerationError,
                  color = MaterialTheme.colorScheme.error,
                  style = MaterialTheme.typography.bodyMedium,
                )
                state.generatedSource != null -> Text(
                  state.generatedSource,
                  fontFamily = JetBrainsMonoFamily,
                  fontSize = 12.sp,
                  lineHeight = 18.sp,
                )
                else -> Text(
                  "Chọn backend để sinh mã, hoặc mở tối ưu để xem các thay đổi an toàn trước khi áp dụng.",
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  style = MaterialTheme.typography.bodyMedium,
                )
              }
            }
          }
        }
        Spacer(Modifier.height(10.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End,
        ) {
          if (preview != null) {
            TextButton(onClick = onDismissOptimizationPreview) { Text("Hủy xem trước") }
            if (preview.changed) {
              Button(onClick = onApplyOptimization) { Text("Áp dụng (có thể hoàn tác)") }
            }
          } else {
            TextButton(onClick = onDismiss) { Text("Đóng") }
          }
        }
      }
    }
  }
}

@Composable
private fun OptimizationDiff(result: SdOptimizationResult) {
  if (!result.changed) {
    Text("Không tìm thấy phép biến đổi an toàn nào trong tệp đang mở.")
    return
  }
  Text(
    "Xem trước ${result.changes.size} thay đổi trong tệp đang mở:",
    style = MaterialTheme.typography.titleSmall,
    fontWeight = FontWeight.Bold,
  )
  result.changes.forEach { change ->
    Spacer(Modifier.height(10.dp))
    Text(
      "Dòng ${change.line}: ${change.explanation}",
      style = MaterialTheme.typography.labelMedium,
    )
    Text(
      "- ${change.before.trim()}",
      fontFamily = JetBrainsMonoFamily,
      fontSize = 12.sp,
      color = MaterialTheme.colorScheme.error,
      maxLines = 3,
      overflow = TextOverflow.Ellipsis,
    )
    Text(
      if (change.after.isEmpty()) "+ (xóa dòng)" else "+ ${change.after.trim()}",
      fontFamily = JetBrainsMonoFamily,
      fontSize = 12.sp,
      color = MaterialTheme.colorScheme.primary,
    )
  }
}
