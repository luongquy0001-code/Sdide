package com.example.sdide.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.example.ui.theme.SyntaxColors

object SdSyntaxHighlighter {
  private val keywords = setOf(
    "ssta", "sta", "iid", "edt", "ed", "bolean", "for", "while", "dbl", "bln",
    "strt", "fn", "void", "struct", "dta", "rtn", "unkw", "cdn", "tf", "chck",
    "else", "brk", "nxt", "define", "defk", "init", "ids", "sb", "block",
    "remaining", "transf", "break", "call", "main", "start", "on", "pause",
    "off", "stop", "and", "or", "not", "nx", "true", "false",
  )

  private val types = setOf("int", "float", "str", "bool", "ptr", "iptedt")

  private val functionsAndLibs = setOf(
    "ioprt", "math", "mem", "str", "sys", "file", "draw",
    "pri", "ipt", "alloc", "free", "reback_flush", "sdbin", "sdppbin",
    "AaC", "endcomand", "abs", "pow", "sqrt", "max", "min", "len",
    "upper", "lower", "concat", "memset", "time", "sleep", "write", "read", "rect",
  )

  private val registers = setOf("0Ax", "0Bx", "0Cx", "0Dx")

  fun highlight(text: String, colors: SyntaxColors): AnnotatedString {
    return buildAnnotatedString {
      append(text)
      addStyle(SpanStyle(color = colors.normal), 0, text.length)

      var i = 0
      val n = text.length
      while (i < n) {
        val c = text[i]

        // 1. Block comments \\* ... *\\ or short comments \\ ... \\
        if (c == '\\' && i + 1 < n && text[i + 1] == '\\') {
          val start = i
          if (i + 2 < n && text[i + 2] == '*') {
            i += 3
            while (i + 2 < n && !(text[i] == '*' && text[i + 1] == '\\' && text[i + 2] == '\\')) {
              i++
            }
            i = if (i + 2 < n) i + 3 else n
          } else {
            i += 2
            while (i + 1 < n && !(text[i] == '\\' && text[i + 1] == '\\')) {
              i++
            }
            i = if (i + 1 < n) i + 2 else n
          }
          addStyle(SpanStyle(color = colors.comment), start, i)
          continue
        }

        // 2. Legacy */ comment
        if (c == '*' && i + 1 < n && text[i + 1] == '/') {
          val start = i
          while (i < n && text[i] != '\n') i++
          addStyle(SpanStyle(color = colors.comment), start, i)
          continue
        }

        // 3. Strings "..." with /d{} & //e highlights inside
        if (c == '"') {
          val start = i
          i++
          while (i < n && text[i] != '"' && text[i] != '\n') {
            i++
          }
          if (i < n && text[i] == '"') i++
          addStyle(SpanStyle(color = colors.string), start, i)

          // Style /d{...}, /s{...}, /f{...}, /f3{...}, /b{...}, //e inside the string
          var k = start + 1
          val end = i - 1
          while (k < end) {
            if (text[k] == '/') {
              if (k + 2 < end && text[k + 1] == '/' && text[k + 2] == 'e') {
                addStyle(SpanStyle(color = colors.formatSpec, fontWeight = FontWeight.Bold), k, k + 3)
                k += 3
                continue
              }
              if (text.startsWith("/f3{", k) || text.startsWith("/d{", k) ||
                text.startsWith("/s{", k) || text.startsWith("/f{", k) || text.startsWith("/b{", k)
              ) {
                val close = text.indexOf('}', k).takeIf { it in (k + 1) until end }
                if (close != null) {
                  addStyle(SpanStyle(color = colors.formatSpec, fontWeight = FontWeight.Bold), k, close + 1)
                  k = close + 1
                  continue
                }
              }
            }
            k++
          }
          continue
        }

        // 4. Two-char flags & delimiters
        if (i + 1 < n) {
          val two = text.substring(i, i + 2)
          if (two in setOf("##", "$$", "#{", "}#", ":&", "*m", "=>", "->")) {
            addStyle(SpanStyle(color = colors.flag, fontWeight = FontWeight.Bold), i, i + 2)
            i += 2
            continue
          }
        }

        // 5. Single-char flags & non-ASCII custom defk flags
        if (c in setOf('$', '₫', '€', '#', '@', '\'') || c.code > 127) {
          addStyle(SpanStyle(color = colors.flag, fontWeight = FontWeight.Bold), i, i + 1)
          i++
          continue
        }

        // 6. Numbers, hex, & registers 0Ax..0Dx
        if (c.isDigit()) {
          val start = i
          if (c == '0' && i + 1 < n && (text[i + 1] == 'x' || text[i + 1] == 'X')) {
            i += 2
            while (i < n && (text[i].isDigit() || text[i] in 'a'..'f' || text[i] in 'A'..'F' || text[i] == '_')) i++
          } else if (c == '0' && i + 1 < n && text[i + 1].isLetter()) {
            while (i < n && (text[i].isLetterOrDigit() || text[i] == '_')) i++
            val tok = text.substring(start, i)
            val color = if (tok in registers) colors.number else colors.normal
            addStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold), start, i)
            continue
          } else {
            while (i < n && (text[i].isDigit() || text[i] == '_' || (text[i] == '.' && i + 1 < n && text[i + 1].isDigit()))) i++
          }
          addStyle(SpanStyle(color = colors.number), start, i)
          continue
        }

        // 7. Identifiers & imp* / impt* / ref*
        if (c.isLetter() || c == '_') {
          val start = i
          while (i < n && (text[i].isLetterOrDigit() || text[i] == '_')) i++
          if (i < n && text[i] == '*' && text.substring(start, i) in setOf("imp", "impt", "ref")) {
            i++
            addStyle(SpanStyle(color = colors.keyword, fontWeight = FontWeight.Bold), start, i)
            continue
          }
          val word = text.substring(start, i)
          when {
            word in keywords -> addStyle(SpanStyle(color = colors.keyword, fontWeight = FontWeight.SemiBold), start, i)
            word in types -> addStyle(SpanStyle(color = colors.type, fontWeight = FontWeight.SemiBold), start, i)
            word in functionsAndLibs -> addStyle(SpanStyle(color = colors.function), start, i)
          }
          continue
        }

        i++
      }
    }
  }
}

class SdVisualTransformation(private val colors: SyntaxColors) : VisualTransformation {
  override fun filter(text: AnnotatedString): TransformedText {
    return TransformedText(
      SdSyntaxHighlighter.highlight(text.text, colors),
      OffsetMapping.Identity,
    )
  }
}
