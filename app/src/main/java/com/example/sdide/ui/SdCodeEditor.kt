package com.example.sdide.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sdide.engine.DiagnosticSeverity
import com.example.sdide.engine.SdDiagnostic
import com.example.ui.theme.ErrorRose
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalSyntaxColors
import com.example.ui.theme.WarnAmber

data class SymbolSnippet(
  val label: String,
  val insertText: String,
  val cursorOffsetFromEnd: Int = 0,
)

private val QUICK_SYMBOLS = listOf(
  SymbolSnippet("iid: sta(", "iid: sta() ed", 4),
  SymbolSnippet("=> edt:", "=> edt: sta() ", 2),
  SymbolSnippet("ed", "ed"),
  SymbolSnippet("$", "$"),
  SymbolSnippet("₫", "₫"),
  SymbolSnippet("€", "€"),
  SymbolSnippet("$$", "$$"),
  SymbolSnippet("##", "##"),
  SymbolSnippet(":&", ":&"),
  SymbolSnippet("*m", "*m"),
  SymbolSnippet("'", " ' "),
  SymbolSnippet("\\\\", "\\\\  \\\\", 3),
  SymbolSnippet("/d{}", "/d{}", 1),
  SymbolSnippet("//e", "//e"),
  SymbolSnippet("0Ax", "0Ax"),
  SymbolSnippet("{}", "{\n    \n}", 2),
  SymbolSnippet("()", "()", 1),
  SymbolSnippet("[]", "[]", 1),
)

@Composable
fun SdCodeEditor(
  value: TextFieldValue,
  onValueChange: (TextFieldValue) -> Unit,
  fontSizeSp: Int,
  showLineNumbers: Boolean,
  diagnostics: List<SdDiagnostic>,
  breakpoints: Set<Int>,
  debugLine: Int?,
  scrollToLineRequest: Pair<Int, Long>?,
  onToggleBreakpoint: (Int) -> Unit,
  modifier: Modifier = Modifier,
  verticalScrollState: ScrollState = rememberScrollState(),
) {
  val syntaxColors = LocalSyntaxColors.current
  val lineHeightSp = (fontSizeSp * 1.5f)
  val lineHeightDp = (lineHeightSp * 1.0f).dp
  val lineCount = remember(value.text) {
    maxOf(1, value.text.count { it == '\n' } + 1)
  }
  val cursorLine = remember(value.text, value.selection) {
    val safeEnd = value.selection.start.coerceIn(0, value.text.length)
    value.text.substring(0, safeEnd).count { it == '\n' } + 1
  }

  val errorLines = remember(diagnostics) {
    diagnostics.groupBy { it.line }
  }

  LaunchedEffect(scrollToLineRequest) {
    val req = scrollToLineRequest ?: return@LaunchedEffect
    val targetLine = (req.first - 1).coerceAtLeast(0)
    // Approximate scroll based on maxScroll and total lines
    if (lineCount > 1 && verticalScrollState.maxValue > 0) {
      val fraction = targetLine.toFloat() / lineCount.toFloat()
      verticalScrollState.animateScrollTo((verticalScrollState.maxValue * fraction).toInt())
    }
  }

  val editorTextStyle = TextStyle(
    fontFamily = JetBrainsMonoFamily,
    fontSize = fontSizeSp.sp,
    lineHeight = lineHeightSp.sp,
    color = syntaxColors.normal,
  )
  val visualTransformation = remember(syntaxColors) { SdVisualTransformation(syntaxColors) }
  val horizontalScrollState = rememberScrollState()

  Row(
    modifier = modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .verticalScroll(verticalScrollState),
  ) {
    if (showLineNumbers) {
      Column(
        modifier = Modifier
          .width(52.dp)
          .background(syntaxColors.gutterBg)
          .padding(vertical = 8.dp),
      ) {
        for (lineNum in 1..lineCount) {
          val isBp = lineNum in breakpoints
          val isDebug = debugLine == lineNum
          val lineDiags = errorLines[lineNum]
          val hasErr = lineDiags?.any { it.severity == DiagnosticSeverity.ERROR } == true
          val hasWarn = !hasErr && lineDiags?.isNotEmpty() == true

          val bgColor = when {
            isDebug -> syntaxColors.debugLineBg
            lineNum == cursorLine -> syntaxColors.activeLineBg
            else -> syntaxColors.gutterBg
          }

          Row(
            modifier = Modifier
              .fillMaxWidth()
              .height(lineHeightDp)
              .background(bgColor)
              .clickable { onToggleBreakpoint(lineNum) }
              .testTag("gutter_line_$lineNum")
              .semantics { contentDescription = "Dòng $lineNum, chạm để bật tắt điểm dừng" }
              .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Box(
              modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(
                  when {
                    isBp -> syntaxColors.breakpointDot
                    hasErr -> ErrorRose.copy(alpha = 0.7f)
                    hasWarn -> WarnAmber.copy(alpha = 0.7f)
                    else -> androidx.compose.ui.graphics.Color.Transparent
                  }
                ),
            )
            Text(
              text = lineNum.toString(),
              style = TextStyle(
                fontFamily = JetBrainsMonoFamily,
                fontSize = (fontSizeSp - 2).coerceAtLeast(10).sp,
                color = when {
                  isDebug -> WarnAmber
                  lineNum == cursorLine -> MaterialTheme.colorScheme.primary
                  else -> syntaxColors.gutterText
                },
                fontWeight = if (isDebug || lineNum == cursorLine) FontWeight.Bold else FontWeight.Normal,
              ),
            )
          }
        }
      }
    }

    Box(
      modifier = Modifier
        .weight(1f)
        .fillMaxHeight()
        .horizontalScroll(horizontalScrollState)
        .padding(vertical = 8.dp, horizontal = 8.dp),
    ) {
      // Subtle highlight band for debug/cursor line
      Column(modifier = Modifier.matchParentSize()) {
        for (lineNum in 1..lineCount) {
          val bg = when {
            debugLine == lineNum -> syntaxColors.debugLineBg
            lineNum == cursorLine -> syntaxColors.activeLineBg.copy(alpha = 0.45f)
            else -> androidx.compose.ui.graphics.Color.Transparent
          }
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(lineHeightDp)
              .background(bg),
          )
        }
      }

      BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = editorTextStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        visualTransformation = visualTransformation,
        modifier = Modifier
          .fillMaxSize()
          .testTag("code_editor_field")
          .semantics { contentDescription = "Trình soạn thảo mã nguồn Sd và Sd++" },
      )
    }
  }
}

@Composable
fun QuickSymbolAccessoryBar(
  canUndo: Boolean,
  canRedo: Boolean,
  onUndo: () -> Unit,
  onRedo: () -> Unit,
  onInsertSnippet: (SymbolSnippet) -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    color = MaterialTheme.colorScheme.surfaceVariant,
    tonalElevation = 2.dp,
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .height(48.dp)
        .padding(horizontal = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(
        onClick = onUndo,
        enabled = canUndo,
        modifier = Modifier
          .size(48.dp)
          .testTag("editor_undo_button"),
      ) {
        Icon(
          Icons.AutoMirrored.Filled.Undo,
          contentDescription = "Hoàn tác",
          tint = if (canUndo) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        )
      }
      IconButton(
        onClick = onRedo,
        enabled = canRedo,
        modifier = Modifier
          .size(48.dp)
          .testTag("editor_redo_button"),
      ) {
        Icon(
          Icons.AutoMirrored.Filled.Redo,
          contentDescription = "Làm lại",
          tint = if (canRedo) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        )
      }

      LazyRow(
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        items(QUICK_SYMBOLS) { snippet ->
          Box(
            modifier = Modifier
              .height(36.dp)
              .clip(RoundedCornerShape(8.dp))
              .background(MaterialTheme.colorScheme.surface)
              .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
              .clickable { onInsertSnippet(snippet) }
              .padding(horizontal = 10.dp)
              .testTag("symbol_chip_${snippet.label}"),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = snippet.label,
              fontFamily = JetBrainsMonoFamily,
              fontSize = 12.sp,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }
      }
    }
  }
}

fun insertSymbolIntoTextField(current: TextFieldValue, snippet: SymbolSnippet): TextFieldValue {
  val text = current.text
  val start = current.selection.start.coerceIn(0, text.length)
  val end = current.selection.end.coerceIn(0, text.length)
  val minIdx = minOf(start, end)
  val maxIdx = maxOf(start, end)
  val newText = text.substring(0, minIdx) + snippet.insertText + text.substring(maxIdx)
  val newCursor = (minIdx + snippet.insertText.length - snippet.cursorOffsetFromEnd).coerceIn(0, newText.length)
  return TextFieldValue(text = newText, selection = TextRange(newCursor))
}
