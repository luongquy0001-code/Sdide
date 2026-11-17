package com.example.sdide.engine

enum class TokenType {
  IDENT,
  INT_LIT,
  FLOAT_LIT,
  STR_LIT,
  BOOL_LIT,

  // Special prefix keywords with *
  IMP_STAR,   // imp*
  IMPT_STAR,  // impt*
  REF_STAR,   // ref*

  // Operators & Punctuation
  FAT_ARROW,   // =>
  ARROW,       // ->
  EQ,          // ==
  NEQ,         // !=
  LTE,         // <=
  GTE,         // >=
  INC,         // ++
  DEC,         // --
  ASSIGN,      // =
  PLUS,        // +
  MINUS,       // -
  STAR,        // *
  SLASH,       // /
  PERCENT,     // %
  LT,          // <
  GT,          // >
  AT,          // @
  DOT,         // .
  COLON,       // :
  COLON_AMP,   // :&
  SEMI,        // ;
  COMMA,       // ,
  QUOTE_SEP,   // '
  LPAREN,      // (
  RPAREN,      // )
  LBRACE,      // {
  RBRACE,      // }
  LBRACKET,    // [
  RBRACKET,    // ]

  // Sd++ flags & register delimiters
  DOLLAR,       // $
  DOUBLE_DOLLAR,// $$
  DONG,         // ₫
  EURO,         // €
  HASH,         // #
  DOUBLE_HASH,  // ##
  HASH_LBRACE,  // #{
  RBRACE_HASH,  // }#
  CUSTOM_FLAG,  // e.g. ¥, ¤

  EOF,
}

data class SdToken(
  val type: TokenType,
  val lexeme: String,
  val line: Int,
  val col: Int,
  val intVal: Long = 0L,
  val floatVal: Double = 0.0,
  val boolVal: Boolean = false,
  val hasLegacyFormat: Boolean = false,
  val precededByNewline: Boolean = false,
)

data class LexResult(
  val tokens: List<SdToken>,
  val warnings: List<SdDiagnostic>,
)

class SdLexer(
  private val source: String,
  private val fileName: String? = null,
) {
  private var pos = 0
  private var line = 1
  private var col = 1
  private val warnings = mutableListOf<SdDiagnostic>()
  private var sawNewlineSinceLastToken = true

  private fun peek(offset: Int = 0): Char? =
    if (pos + offset < source.length) source[pos + offset] else null

  private fun advance(): Char {
    val ch = source[pos++]
    if (ch == '\n') {
      line++
      col = 1
      sawNewlineSinceLastToken = true
    } else {
      col++
    }
    return ch
  }

  fun tokenize(): LexResult {
    val tokens = mutableListOf<SdToken>()
    while (pos < source.length) {
      val c = peek()!!
      if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
        advance()
        continue
      }

      // Comments: \\* ... *\\ (block) or \\ ... \\ (short)
      if (c == '\\' && peek(1) == '\\') {
        if (peek(2) == '*') {
          skipBlockComment()
        } else {
          skipShortComment()
        }
        continue
      }

      // Legacy single-line comment: */ ... \n
      if (c == '*' && peek(1) == '/') {
        val startLine = line
        val startCol = col
        warnings.add(
          SdDiagnostic(
            code = "W207",
            phase = 1,
            line = startLine,
            col = startCol,
            fileName = fileName,
            message = "ghi chú */ phụ thuộc xuống dòng; nên dùng \\\\ ... \\\\ hoặc \\\\* ... *\\\\",
            severity = DiagnosticSeverity.WARNING,
          )
        )
        advance(); advance()
        while (pos < source.length && peek() != '\n') {
          advance()
        }
        continue
      }

      val tokLine = line
      val tokCol = col
      val hadNewline = sawNewlineSinceLastToken
      sawNewlineSinceLastToken = false

      // String literal
      if (c == '"') {
        tokens.add(readString(tokLine, tokCol, hadNewline))
        continue
      }

      // Two-char tokens
      val c2 = peek(1)
      when {
        c == '=' && c2 == '>' -> { advance(); advance(); tokens.add(SdToken(TokenType.FAT_ARROW, "=>", tokLine, tokCol, precededByNewline = hadNewline)); continue }
        c == '-' && c2 == '>' -> { advance(); advance(); tokens.add(SdToken(TokenType.ARROW, "->", tokLine, tokCol, precededByNewline = hadNewline)); continue }
        c == '=' && c2 == '=' -> { advance(); advance(); tokens.add(SdToken(TokenType.EQ, "==", tokLine, tokCol, precededByNewline = hadNewline)); continue }
        c == '!' && c2 == '=' -> { advance(); advance(); tokens.add(SdToken(TokenType.NEQ, "!=", tokLine, tokCol, precededByNewline = hadNewline)); continue }
        c == '<' && c2 == '=' -> { advance(); advance(); tokens.add(SdToken(TokenType.LTE, "<=", tokLine, tokCol, precededByNewline = hadNewline)); continue }
        c == '>' && c2 == '=' -> { advance(); advance(); tokens.add(SdToken(TokenType.GTE, ">=", tokLine, tokCol, precededByNewline = hadNewline)); continue }
        c == '+' && c2 == '+' -> { advance(); advance(); tokens.add(SdToken(TokenType.INC, "++", tokLine, tokCol, precededByNewline = hadNewline)); continue }
        c == '-' && c2 == '-' -> { advance(); advance(); tokens.add(SdToken(TokenType.DEC, "--", tokLine, tokCol, precededByNewline = hadNewline)); continue }
        c == '$' && c2 == '$' -> { advance(); advance(); tokens.add(SdToken(TokenType.DOUBLE_DOLLAR, "$$", tokLine, tokCol, precededByNewline = hadNewline)); continue }
        c == '#' && c2 == '#' -> { advance(); advance(); tokens.add(SdToken(TokenType.DOUBLE_HASH, "##", tokLine, tokCol, precededByNewline = hadNewline)); continue }
        c == '#' && c2 == '{' -> { advance(); advance(); tokens.add(SdToken(TokenType.HASH_LBRACE, "#{", tokLine, tokCol, precededByNewline = hadNewline)); continue }
        c == '}' && c2 == '#' -> { advance(); advance(); tokens.add(SdToken(TokenType.RBRACE_HASH, "}#", tokLine, tokCol, precededByNewline = hadNewline)); continue }
        c == ':' && c2 == '&' -> { advance(); advance(); tokens.add(SdToken(TokenType.COLON_AMP, ":&", tokLine, tokCol, precededByNewline = hadNewline)); continue }
      }

      // Single-char tokens
      val singleType = when (c) {
        '=' -> TokenType.ASSIGN
        '+' -> TokenType.PLUS
        '-' -> TokenType.MINUS
        '*' -> TokenType.STAR
        '/' -> TokenType.SLASH
        '%' -> TokenType.PERCENT
        '<' -> TokenType.LT
        '>' -> TokenType.GT
        '@' -> TokenType.AT
        '.' -> TokenType.DOT
        ':' -> TokenType.COLON
        ';' -> TokenType.SEMI
        ',' -> TokenType.COMMA
        '\'' -> TokenType.QUOTE_SEP
        '(' -> TokenType.LPAREN
        ')' -> TokenType.RPAREN
        '{' -> TokenType.LBRACE
        '}' -> TokenType.RBRACE
        '[' -> TokenType.LBRACKET
        ']' -> TokenType.RBRACKET
        '$' -> TokenType.DOLLAR
        '₫' -> TokenType.DONG
        '€' -> TokenType.EURO
        '#' -> TokenType.HASH
        else -> null
      }
      if (singleType != null) {
        advance()
        tokens.add(SdToken(singleType, c.toString(), tokLine, tokCol, precededByNewline = hadNewline))
        continue
      }

      // Numbers & Registers (0Ax..0Dx)
      if (c.isDigit()) {
        tokens.add(readNumberOrRegister(tokLine, tokCol, hadNewline))
        continue
      }

      // Identifiers & imp* / impt* / ref* / bool literals
      if (c.isLetter() || c == '_') {
        tokens.add(readIdent(tokLine, tokCol, hadNewline))
        continue
      }

      // Non-ASCII symbol (potential custom defk flag like ¥, ¤)
      if (c.code > 127) {
        advance()
        tokens.add(SdToken(TokenType.CUSTOM_FLAG, c.toString(), tokLine, tokCol, precededByNewline = hadNewline))
        continue
      }

      // Unknown ASCII character -> E102
      val bad = advance()
      throw SdCompileException(
        SdDiagnostic(
          code = "E102",
          phase = 2,
          line = tokLine,
          col = tokCol,
          fileName = fileName,
          message = "ký tự lạ: $bad",
        )
      )
    }

    tokens.add(SdToken(TokenType.EOF, "", line, col, precededByNewline = sawNewlineSinceLastToken))
    return LexResult(tokens, warnings)
  }

  private fun skipShortComment() {
    val startLine = line
    val startCol = col
    advance(); advance() // consume \\
    while (pos < source.length) {
      if (peek() == '\\' && peek(1) == '\\') {
        advance(); advance()
        return
      }
      advance()
    }
    throw SdCompileException(
      SdDiagnostic(
        code = "E101",
        phase = 1,
        line = startLine,
        col = startCol,
        fileName = fileName,
        message = "ghi chú mở ở dòng $startLine không được đóng",
        hint = "thêm \\\\ để đóng ghi chú",
      )
    )
  }

  private fun skipBlockComment() {
    val startLine = line
    val startCol = col
    advance(); advance(); advance() // consume \\*
    while (pos < source.length) {
      if (peek() == '*' && peek(1) == '\\' && peek(2) == '\\') {
        advance(); advance(); advance()
        return
      }
      // Allow nested \\ ... \\ inside \\* ... *\\ (Ch 22.3)
      if (peek() == '\\' && peek(1) == '\\' && peek(2) != '*') {
        skipShortComment()
        continue
      }
      advance()
    }
    throw SdCompileException(
      SdDiagnostic(
        code = "E101",
        phase = 1,
        line = startLine,
        col = startCol,
        fileName = fileName,
        message = "ghi chú khối \\\\* mở ở dòng $startLine không được đóng",
        hint = "thêm *\\\\ để đóng ghi chú khối",
      )
    )
  }

  private fun readString(startLine: Int, startCol: Int, hadNewline: Boolean): SdToken {
    advance() // consume opening "
    val sb = StringBuilder()
    var hasLegacyFmt = false
    while (pos < source.length) {
      val c = peek()!!
      if (c == '\n' || c == '\r') {
        throw SdCompileException(
          SdDiagnostic(
            code = "E103",
            phase = 1,
            line = startLine,
            col = startCol,
            fileName = fileName,
            message = "chuỗi chưa đóng dấu nháy kép hoặc chứa xuống dòng vật lý",
            hint = "dùng //e để ngắt dòng trong chuỗi",
          )
        )
      }
      if (c == '"') {
        advance()
        return SdToken(
          type = TokenType.STR_LIT,
          lexeme = sb.toString(),
          line = startLine,
          col = startCol,
          hasLegacyFormat = hasLegacyFmt,
          precededByNewline = hadNewline,
        )
      }
      if (c == '/' && peek(1) == '/') {
        val next = peek(2)
        if (next == 'e') {
          advance(); advance(); advance()
          sb.append('\n')
          continue
        } else if (next != null && next.isLetter()) {
          val errCol = col
          throw SdCompileException(
            SdDiagnostic(
              code = "E104",
              phase = 1,
              line = line,
              col = errCol,
              fileName = fileName,
              message = "cụm //$next không hợp lệ trong chuỗi (chỉ hỗ trợ //e để xuống dòng)",
              hint = "sửa thành //e hoặc dùng // kèm ký tự không phải chữ cái",
            )
          )
        } else {
          // Ch 3: "//" -> literal '/'
          advance(); advance()
          sb.append('/')
          continue
        }
      }
      if (c == '/' && peek(1) in listOf('d', 's', 'f', 'b')) {
        val after = peek(2)
        if (after != '{' && !(peek(1) == 'f' && after == '3' && peek(3) == '{')) {
          hasLegacyFmt = true
        }
      }
      sb.append(advance())
    }
    throw SdCompileException(
      SdDiagnostic(
        code = "E103",
        phase = 1,
        line = startLine,
        col = startCol,
        fileName = fileName,
        message = "chuỗi chưa đóng dấu nháy kép",
      )
    )
  }

  private fun readNumberOrRegister(startLine: Int, startCol: Int, hadNewline: Boolean): SdToken {
    // Check hex 0x...
    if (peek() == '0' && (peek(1) == 'x' || peek(1) == 'X')) {
      val raw = StringBuilder()
      raw.append(advance())
      raw.append(advance())
      val digits = StringBuilder()
      while (pos < source.length) {
        val ch = peek()!!
        if (ch == '_') {
          raw.append(advance())
        } else if (ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F') {
          raw.append(ch)
          digits.append(advance())
        } else {
          break
        }
      }
      if (digits.isEmpty()) {
        throw SdCompileException(
          SdDiagnostic(
            code = "E102",
            phase = 2,
            line = startLine,
            col = startCol,
            fileName = fileName,
            message = "số thập lục phân không hợp lệ: $raw",
          )
        )
      }
      val value = digits.toString().toLongOrNull(16)
        ?: throw SdCompileException(
          SdDiagnostic(
            code = "E405",
            phase = 2,
            line = startLine,
            col = startCol,
            fileName = fileName,
            message = "hằng số thập lục phân quá lớn: $raw",
          )
        )
      return SdToken(TokenType.INT_LIT, raw.toString(), startLine, startCol, intVal = value, precededByNewline = hadNewline)
    }

    // Check simulated register like 0Ax, 0Bx, 0Cx, 0Dx (or invalid 0Ex)
    if (peek() == '0' && peek(1)?.isLetter() == true) {
      val sb = StringBuilder()
      while (pos < source.length && (peek()!!.isLetterOrDigit() || peek() == '_')) {
        sb.append(advance())
      }
      return SdToken(TokenType.IDENT, sb.toString(), startLine, startCol, precededByNewline = hadNewline)
    }

    val sb = StringBuilder()
    var isFloat = false
    while (pos < source.length && (peek()!!.isDigit() || peek() == '_')) {
      val ch = advance()
      if (ch != '_') sb.append(ch)
    }
    if (peek() == '.' && peek(1)?.isDigit() == true) {
      isFloat = true
      sb.append(advance())
      while (pos < source.length && (peek()!!.isDigit() || peek() == '_')) {
        val ch = advance()
        if (ch != '_') sb.append(ch)
      }
    }
    val raw = sb.toString()
    return if (isFloat) {
      SdToken(
        TokenType.FLOAT_LIT,
        raw,
        startLine,
        startCol,
        floatVal = raw.toDoubleOrNull() ?: 0.0,
        precededByNewline = hadNewline,
      )
    } else {
      val iv = raw.toLongOrNull() ?: Long.MAX_VALUE
      SdToken(TokenType.INT_LIT, raw, startLine, startCol, intVal = iv, precededByNewline = hadNewline)
    }
  }

  private fun readIdent(startLine: Int, startCol: Int, hadNewline: Boolean): SdToken {
    val sb = StringBuilder()
    while (pos < source.length && (peek()!!.isLetterOrDigit() || peek() == '_')) {
      sb.append(advance())
    }
    val word = sb.toString()
    if ((word == "imp" || word == "impt" || word == "ref") && peek() == '*') {
      advance()
      val type = when (word) {
        "imp" -> TokenType.IMP_STAR
        "impt" -> TokenType.IMPT_STAR
        else -> TokenType.REF_STAR
      }
      return SdToken(type, "$word*", startLine, startCol, precededByNewline = hadNewline)
    }
    if (word == "true") {
      return SdToken(TokenType.BOOL_LIT, word, startLine, startCol, boolVal = true, precededByNewline = hadNewline)
    }
    if (word == "false") {
      return SdToken(TokenType.BOOL_LIT, word, startLine, startCol, boolVal = false, precededByNewline = hadNewline)
    }
    return SdToken(TokenType.IDENT, word, startLine, startCol, precededByNewline = hadNewline)
  }
}
