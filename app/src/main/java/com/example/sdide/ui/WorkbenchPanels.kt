package com.example.sdide.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SubdirectoryArrowRight
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sdide.engine.DebugSnapshot
import com.example.sdide.engine.DiagnosticSeverity
import com.example.sdide.engine.DrawRectCmd
import com.example.sdide.engine.SdDiagnostic
import com.example.ui.theme.ErrorRose
import com.example.ui.theme.InfoCyan
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.Part3VioletBright
import com.example.ui.theme.SdOrangeLight
import com.example.ui.theme.SdppLimeBright
import com.example.ui.theme.SuccessEmerald
import com.example.ui.theme.WarnAmber

@Composable
fun WorkbenchPanel(
  state: IdeUiState,
  onSelectTab: (WorkbenchTab) -> Unit,
  onJumpToProblem: (SdDiagnostic) -> Unit,
  onClearConsole: () -> Unit,
  onUpdateStdin: (String) -> Unit,
  onSubmitInteractiveStdin: (String) -> Unit,
  onStepInto: () -> Unit,
  onStepOver: () -> Unit,
  onContinue: () -> Unit,
  onStop: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val errorCount = state.diagnostics.count { it.severity == DiagnosticSeverity.ERROR }
  val warnCount = state.diagnostics.count { it.severity == DiagnosticSeverity.WARNING }

  val tabs = buildList {
    add(WorkbenchTab.CONSOLE to "Kết quả")
    val probLabel = when {
      errorCount > 0 -> "Vấn đề ($errorCount!)"
      warnCount > 0 -> "Vấn đề ($warnCount)"
      else -> "Vấn đề"
    }
    add(WorkbenchTab.PROBLEMS to probLabel)
    add(WorkbenchTab.DEBUGGER to if (state.isPausedInDebug) "Gỡ lỗi ●" else "Gỡ lỗi & RAM")
    add(WorkbenchTab.STDIN to "Nhập liệu")
    if (state.drawCommands.isNotEmpty()) {
      add(WorkbenchTab.DRAW to "Vẽ (${state.drawCommands.size})")
    }
  }

  val selectedIdx = tabs.indexOfFirst { it.first == state.activeTab }.coerceAtLeast(0)

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.surface),
  ) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    ScrollableTabRow(
      selectedTabIndex = selectedIdx,
      edgePadding = 8.dp,
      containerColor = MaterialTheme.colorScheme.surfaceVariant,
      contentColor = MaterialTheme.colorScheme.primary,
      divider = {},
    ) {
      tabs.forEach { (tab, title) ->
        Tab(
          selected = state.activeTab == tab,
          onClick = { onSelectTab(tab) },
          modifier = Modifier.testTag("workbench_tab_${tab.name.lowercase()}"),
          text = {
            Text(
              text = title,
              style = MaterialTheme.typography.labelMedium,
              fontWeight = if (state.activeTab == tab) FontWeight.Bold else FontWeight.Medium,
              color = if (tab == WorkbenchTab.PROBLEMS && errorCount > 0) ErrorRose
              else if (state.activeTab == tab) MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          },
        )
      }
    }

    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
      when (state.activeTab) {
        WorkbenchTab.CONSOLE -> ConsoleTabContent(
          output = state.consoleOutput,
          exitCode = state.lastExitCode,
          succeeded = state.lastRunSucceeded,
          isRunning = state.isRunning,
          waitingPrompt = state.waitingForStdinPrompt,
          onClear = onClearConsole,
          onSubmitStdin = onSubmitInteractiveStdin,
        )
        WorkbenchTab.PROBLEMS -> ProblemsTabContent(
          diagnostics = state.diagnostics,
          onJumpToProblem = onJumpToProblem,
        )
        WorkbenchTab.DEBUGGER -> DebuggerTabContent(
          isRunning = state.isRunning,
          isPaused = state.isPausedInDebug,
          debugLine = state.debugLine,
          snapshot = state.debugSnapshot,
          onStepInto = onStepInto,
          onStepOver = onStepOver,
          onContinue = onContinue,
          onStop = onStop,
        )
        WorkbenchTab.STDIN -> StdinTabContent(
          stdinText = state.stdinText,
          onUpdateStdin = onUpdateStdin,
        )
        WorkbenchTab.DRAW -> DrawPreviewContent(commands = state.drawCommands)
      }
    }
  }
}

@Composable
private fun ConsoleTabContent(
  output: String,
  exitCode: Long?,
  succeeded: Boolean?,
  isRunning: Boolean,
  waitingPrompt: String?,
  onClear: () -> Unit,
  onSubmitStdin: (String) -> Unit,
) {
  var inlineInput by remember { mutableStateOf("") }
  val scrollState = rememberScrollState()

  Column(modifier = Modifier.fillMaxSize()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (succeeded == true && exitCode != null) {
          Surface(
            color = SuccessEmerald.copy(alpha = 0.16f),
            shape = RoundedCornerShape(6.dp),
          ) {
            Text(
              text = "Thoát: ref* $exitCode",
              style = MaterialTheme.typography.labelSmall,
              color = SuccessEmerald,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
          }
        } else if (succeeded == false) {
          Surface(
            color = ErrorRose.copy(alpha = 0.16f),
            shape = RoundedCornerShape(6.dp),
          ) {
            Text(
              text = "Dừng do lỗi",
              style = MaterialTheme.typography.labelSmall,
              color = ErrorRose,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
          }
        } else if (isRunning) {
          Text(
            text = if (waitingPrompt != null) "Đang chờ nhập '$waitingPrompt'..." else "Đang chạy...",
            style = MaterialTheme.typography.labelSmall,
            color = WarnAmber,
          )
        }
      }

      IconButton(
        onClick = onClear,
        modifier = Modifier.size(36.dp).testTag("clear_console_button"),
      ) {
        Icon(Icons.Default.DeleteSweep, contentDescription = "Xóa màn hình kết quả", tint = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }

    if (waitingPrompt != null) {
      Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedTextField(
            value = inlineInput,
            onValueChange = { inlineInput = it },
            label = { Text("Nhập giá trị cho $waitingPrompt") },
            singleLine = true,
            modifier = Modifier.weight(1f).testTag("interactive_stdin_input"),
          )
          Button(
            onClick = {
              onSubmitStdin(inlineInput)
              inlineInput = ""
            },
            modifier = Modifier.testTag("interactive_stdin_submit"),
          ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardReturn, contentDescription = "Gửi dữ liệu nhập")
          }
        }
      }
    }

    SelectionContainer {
      Text(
        text = output.ifEmpty { "(Chưa có kết quả in ra)" },
        fontFamily = JetBrainsMonoFamily,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
          .fillMaxSize()
          .verticalScroll(scrollState)
          .padding(horizontal = 12.dp, vertical = 6.dp)
          .testTag("console_output_text"),
      )
    }
  }
}

@Composable
private fun ProblemsTabContent(
  diagnostics: List<SdDiagnostic>,
  onJumpToProblem: (SdDiagnostic) -> Unit,
) {
  if (diagnostics.isEmpty()) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SuccessEmerald)
        Text("Không có lỗi hay cảnh báo nào.", style = MaterialTheme.typography.bodyMedium)
      }
    }
    return
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("problems_list"),
    contentPadding = PaddingValues(12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    items(diagnostics) { d ->
      val isError = d.severity == DiagnosticSeverity.ERROR
      val accent = if (isError) ErrorRose else WarnAmber
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .clickable { onJumpToProblem(d) }
          .testTag("problem_item_${d.code}_${d.line}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(10.dp),
      ) {
        Row(
          modifier = Modifier.padding(12.dp),
          verticalAlignment = Alignment.Top,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Icon(
            imageVector = if (isError) Icons.Default.ErrorOutline else Icons.Default.WarningAmber,
            contentDescription = d.code,
            tint = accent,
            modifier = Modifier.size(20.dp),
          )
          Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Surface(color = accent.copy(alpha = 0.18f), shape = RoundedCornerShape(4.dp)) {
                Text(
                  text = d.code,
                  style = MaterialTheme.typography.labelSmall,
                  color = accent,
                  fontWeight = FontWeight.Bold,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
              }
              Text(
                text = "${d.fileName ?: "main"} : dòng ${d.line}, cột ${d.col}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Text(text = d.message, style = MaterialTheme.typography.bodyMedium)
            if (!d.hint.isNullOrBlank()) {
              Text(
                text = "Gợi ý: ${d.hint}",
                style = MaterialTheme.typography.labelMedium,
                color = InfoCyan,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun DebuggerTabContent(
  isRunning: Boolean,
  isPaused: Boolean,
  debugLine: Int?,
  snapshot: DebugSnapshot?,
  onStepInto: () -> Unit,
  onStepOver: () -> Unit,
  onContinue: () -> Unit,
  onStop: () -> Unit,
) {
  Column(modifier = Modifier.fillMaxSize()) {
    // Debug controls toolbar
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      FilledTonalButton(
        onClick = onStepOver,
        enabled = isPaused,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = Modifier.testTag("debug_step_over"),
      ) {
        Icon(Icons.Default.SkipNext, contentDescription = "Bước qua", modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text("Bước qua", style = MaterialTheme.typography.labelMedium)
      }
      FilledTonalButton(
        onClick = onStepInto,
        enabled = isPaused,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = Modifier.testTag("debug_step_into"),
      ) {
        Icon(Icons.Default.SubdirectoryArrowRight, contentDescription = "Bước vào", modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text("Vào hàm", style = MaterialTheme.typography.labelMedium)
      }
      FilledTonalButton(
        onClick = onContinue,
        enabled = isPaused,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = Modifier.testTag("debug_continue"),
      ) {
        Icon(Icons.Default.PlayArrow, contentDescription = "Tiếp tục", modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text("Tiếp tục", style = MaterialTheme.typography.labelMedium)
      }
      if (isRunning) {
        IconButton(onClick = onStop, modifier = Modifier.size(40.dp).testTag("debug_stop")) {
          Icon(Icons.Default.Stop, contentDescription = "Dừng gỡ lỗi", tint = ErrorRose)
        }
      }
    }

    if (debugLine != null && isPaused) {
      Text(
        text = "Đang tạm dừng tại dòng $debugLine (${snapshot?.fileName ?: ""})",
        style = MaterialTheme.typography.labelMedium,
        color = WarnAmber,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
      )
    }

    if (snapshot == null) {
      Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Text(
          text = "Chạm vào cột số dòng để đặt điểm dừng (chấm đỏ), sau đó nhấn 🐞 để theo dõi biến, Heap 8-byte và 4 thanh ghi 0Ax–0Dx.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      return
    }

    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(12.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      // 1. Virtual Registers 0Ax..0Dx
      item {
        Text("Thanh ghi mô phỏng (0Ax–0Dx)", style = MaterialTheme.typography.titleSmall, color = Part3VioletBright)
        Spacer(Modifier.height(6.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          snapshot.registers.forEach { reg ->
            Surface(
              modifier = Modifier.weight(1f),
              shape = RoundedCornerShape(8.dp),
              color = MaterialTheme.colorScheme.surfaceVariant,
              border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (reg.active) Part3VioletBright else MaterialTheme.colorScheme.outline,
              ),
            ) {
              Column(modifier = Modifier.padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                  Text(reg.name, fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = SdOrangeLight)
                  if (reg.managedByAaC) {
                    Text("AaC", fontSize = 10.sp, color = SdppLimeBright, fontWeight = FontWeight.Bold)
                  }
                }
                Text(
                  text = reg.value,
                  fontFamily = JetBrainsMonoFamily,
                  fontSize = 11.sp,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }
          }
        }
      }

      // 2. Heap 8-byte blocks
      if (snapshot.heapBlocks.isNotEmpty()) {
        item {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(16.dp), tint = InfoCyan)
            Text("Bộ nhớ Heap (sta.alloc / 8-byte)", style = MaterialTheme.typography.titleSmall, color = InfoCyan)
          }
          Spacer(Modifier.height(6.dp))
          snapshot.heapBlocks.forEach { hb ->
            Surface(
              modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
              shape = RoundedCornerShape(8.dp),
              color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
              Column(modifier = Modifier.padding(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                  Text(
                    text = "${hb.addressHex} (${hb.sizeSlots} ô × 8B)",
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                  )
                  Text(
                    text = if (hb.freed) "ĐÃ FREE" else "ĐANG CẤP",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hb.freed) ErrorRose else SuccessEmerald,
                  )
                }
                Text(
                  text = "[${hb.preview}]",
                  fontFamily = JetBrainsMonoFamily,
                  fontSize = 12.sp,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }

      // 3. Globals
      if (snapshot.globals.isNotEmpty()) {
        item {
          Text("Biến toàn cục (₫ / ids.g)", style = MaterialTheme.typography.titleSmall, color = SdppLimeBright)
          Spacer(Modifier.height(4.dp))
          snapshot.globals.forEach { g ->
            VarRow(name = g.name, type = g.type, value = g.value, isBorrowed = g.isBorrowed)
          }
        }
      }

      // 4. Call Stack & Local Variables
      item {
        Text("Ngăn xếp gọi & Biến cục bộ", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
      }
      items(snapshot.callStack.reversed()) { frame ->
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(8.dp),
          color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
          Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
              text = "${frame.name}() @ dòng ${frame.line}",
              fontFamily = JetBrainsMonoFamily,
              fontWeight = FontWeight.Bold,
              fontSize = 12.sp,
              color = MaterialTheme.colorScheme.primary,
            )
            if (frame.locals.isEmpty()) {
              Text("(Không có biến cục bộ)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
              frame.locals.forEach { v ->
                VarRow(name = v.name, type = v.type, value = v.value, isBorrowed = v.isBorrowed)
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun VarRow(name: String, type: String, value: String, isBorrowed: Boolean) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 2.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = if (isBorrowed) "$name:&" else name,
        fontFamily = JetBrainsMonoFamily,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
      )
      Text(
        text = ": $type",
        fontFamily = JetBrainsMonoFamily,
        fontSize = 11.sp,
        color = InfoCyan,
      )
    }
    Text(
      text = value,
      fontFamily = JetBrainsMonoFamily,
      fontSize = 12.sp,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun StdinTabContent(
  stdinText: String,
  onUpdateStdin: (String) -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxSize().padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      text = "Nhập sẵn dữ liệu cho các lệnh ipt (mỗi giá trị một dòng). Nếu để trống, IDE sẽ hỏi trực tiếp khi chạy tới ipt.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
      value = stdinText,
      onValueChange = onUpdateStdin,
      placeholder = { Text("Ví dụ:\n10\n25\nNguyen Van A") },
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f)
        .testTag("stdin_queue_field"),
      textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily),
    )
  }
}

@Composable
private fun DrawPreviewContent(commands: List<DrawRectCmd>) {
  val rectColor = MaterialTheme.colorScheme.primary
  Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
    Text("Khung vẽ draw.rect (${commands.size} hình)", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    Box(
      modifier = Modifier
        .fillMaxSize()
        .clip(RoundedCornerShape(10.dp))
        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
        .background(MaterialTheme.colorScheme.background),
    ) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        val scale = 8f
        commands.forEach { r ->
          drawRect(
            color = rectColor,
            topLeft = Offset(r.x * scale + 16f, r.y * scale + 16f),
            size = Size(r.width * scale, r.height * scale),
            style = Stroke(width = 3f),
          )
        }
      }
    }
  }
}
