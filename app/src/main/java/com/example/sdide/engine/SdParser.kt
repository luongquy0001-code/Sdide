package com.example.sdide.engine

class SdParser(
  private val tokens: List<SdToken>,
  private val fileName: String? = null,
  private val isSdAll: Boolean = false,
  private val isExplicitSdppExt: Boolean = false,
  private val externalFiles: Map<String, String> = emptyMap(),
  private val importStack: Set<String> = emptySet(),
  private val initialWarnings: MutableList<SdDiagnostic> = mutableListOf(),
) {
  companion object {
    val STD_LIBS = setOf("ioprt", "math", "mem", "str", "sys", "file", "draw")
    val RESERVED_FLAGS = setOf("$", "₫", "€", "$$", "#", "##")
    val VALID_REGISTERS = setOf("0Ax", "0Bx", "0Cx", "0Dx")
    val SWITCH_NAMES = setOf("start", "on", "pause", "off", "stop")
    val KNOWN_TYPES = setOf("int", "float", "str", "bool", "ptr")
  }

  val warnings: MutableList<SdDiagnostic> = initialWarnings
  val importedUnits: LinkedHashMap<String, ProgramNode> = LinkedHashMap()

  private var idx = 0

  private fun peek(offset: Int = 0): SdToken {
    val i = idx + offset
    return if (i < tokens.size) tokens[i] else tokens.last()
  }

  private fun advance(): SdToken {
    val t = peek()
    if (idx < tokens.size - 1) idx++
    return t
  }

  private fun check(type: TokenType, lexeme: String? = null): Boolean {
    val t = peek()
    if (t.type != type) return false
    return lexeme == null || t.lexeme == lexeme
  }

  private fun match(type: TokenType, lexeme: String? = null): Boolean {
    if (check(type, lexeme)) {
      advance()
      return true
    }
    return false
  }

  private fun err(code: String, tok: SdToken, msg: String, hint: String? = null): Nothing {
    throw SdCompileException(
      SdDiagnostic(code = code, phase = 2, line = tok.line, col = tok.col, fileName = fileName, message = msg, hint = hint)
    )
  }

  private fun expect(type: TokenType, code: String = "E201", msg: String? = null): SdToken {
    if (check(type)) return advance()
    val t = peek()
    err(code, t, msg ?: "kỳ vọng '$type' nhưng gặp '${t.lexeme}'")
  }

  fun parseCompilationUnit(): SdCompilationUnit {
    // Check if there is any SEMI token inside braces vs top-level
    val hasTopLevelSemi = hasTopLevelSemicolon()
    if (isSdAll || hasTopLevelSemi) {
      return SdCompilationUnit.SdAllProgram(parseSdAllCompartments())
    }
    val prog = parseSingleProgram(isPartOfSdAll = false)
    return SdCompilationUnit.SingleProgram(prog)
  }

  private fun hasTopLevelSemicolon(): Boolean {
    var depth = 0
    for (t in tokens) {
      when (t.type) {
        TokenType.LBRACE, TokenType.HASH_LBRACE -> depth++
        TokenType.RBRACE, TokenType.RBRACE_HASH -> if (depth > 0) depth--
        TokenType.SEMI -> if (depth == 0) return true
        else -> {}
      }
    }
    return false
  }

  private fun parseSdAllCompartments(): List<CompartmentNode> {
    // Split tokens by top-level SEMI
    val slices = mutableListOf<List<SdToken>>()
    var current = mutableListOf<SdToken>()
    var depth = 0
    for (t in tokens) {
      if (t.type == TokenType.EOF) break
      when (t.type) {
        TokenType.LBRACE, TokenType.HASH_LBRACE -> depth++
        TokenType.RBRACE, TokenType.RBRACE_HASH -> if (depth > 0) depth--
        TokenType.SEMI -> {
          if (depth == 0) {
            slices.add(current)
            current = mutableListOf()
            continue
          } else {
            err("E102", t, "dấu ';' chỉ dùng ở tầng ngoài cùng giữa các ngăn .sdall")
          }
        }
        else -> {}
      }
      current.add(t)
    }
    if (current.isNotEmpty() || slices.isEmpty()) {
      slices.add(current)
    }

    val compartments = mutableListOf<CompartmentNode>()
    var permanentAaC = false
    var stoppedChain = false

    slices.forEachIndexed { i, slice ->
      if (slice.isEmpty()) return@forEachIndexed
      val eofTok = SdToken(TokenType.EOF, "", slice.last().line, slice.last().col + 1)
      val subParser = SdParser(
        tokens = slice + eofTok,
        fileName = fileName,
        isSdAll = true,
        isExplicitSdppExt = isExplicitSdppExt,
        externalFiles = externalFiles,
        importStack = importStack,
        initialWarnings = warnings,
      )
      val comp = subParser.parseOneCompartment(i + 1, permanentAaC, stoppedChain)
      importedUnits.putAll(subParser.importedUnits)
      if (comp.switch == CompartmentSwitch.STOP) {
        stoppedChain = true
      }
      if (comp.program.aacMilestones.any { it.isPermanent }) {
        permanentAaC = true
      }
      compartments.add(comp)
    }
    return compartments
  }

  private fun parseOneCompartment(
    index: Int,
    inheritedAaC: Boolean,
    afterStop: Boolean,
  ): CompartmentNode {
    // Check optional impt*AaC.+bin$ before switch or compartment
    val preMilestones = mutableListOf<AaCMilestoneNode>()
    while (check(TokenType.IMPT_STAR)) {
      preMilestones.add(parseAaCMilestone())
    }

    var sw = CompartmentSwitch.START
    var sawSwitch = false
    while (check(TokenType.IDENT) && !isStartOfCompartment()) {
      val tok = advance()
      val parsedSw = when (tok.lexeme) {
        "start" -> CompartmentSwitch.START
        "on" -> CompartmentSwitch.ON
        "pause" -> CompartmentSwitch.PAUSE
        "off" -> CompartmentSwitch.OFF
        "stop" -> CompartmentSwitch.STOP
        else -> err("E222", tok, "công tắc ngăn không hợp lệ: '${tok.lexeme}'", "dùng start, on, pause, off hoặc stop")
      }
      if (sawSwitch) {
        err("E222", tok, "mỗi ngăn chỉ được có một công tắc")
      }
      sw = parsedSw
      sawSwitch = true
    }

    val startPos = SourcePos(peek().line, peek().col, fileName)
    if (afterStop) {
      warnings.add(
        SdDiagnostic(
          code = "W208",
          phase = 2,
          line = startPos.line,
          col = startPos.col,
          fileName = fileName,
          message = "ngăn #$index nằm dưới công tắc stop nên không chạy",
          severity = DiagnosticSeverity.WARNING,
        )
      )
      val dummy = ProgramNode(
        isSdpp = false,
        imports = emptyList(),
        aacMilestones = preMilestones,
        mainBody = emptyList(),
        mainPos = startPos,
      )
      return CompartmentNode(index, CompartmentSwitch.OFF, CompartmentKind.SD_CORE, null, dummy, startPos)
    }

    if (sw == CompartmentSwitch.OFF) {
      val dummy = ProgramNode(
        isSdpp = false,
        imports = emptyList(),
        aacMilestones = preMilestones,
        mainBody = emptyList(),
        mainPos = startPos,
      )
      return CompartmentNode(index, sw, CompartmentKind.SD_CORE, null, dummy, startPos)
    }

    // Determine kind
    val kind = when {
      check(TokenType.HASH_LBRACE) -> CompartmentKind.REG_ARENA
      isNextSsta() -> CompartmentKind.SDPP
      else -> CompartmentKind.SD_CORE
    }

    val prog = parseSingleProgram(
      isPartOfSdAll = true,
      inheritedAaC = inheritedAaC || preMilestones.isNotEmpty(),
      preMilestones = preMilestones,
    )
    return CompartmentNode(index, sw, kind, prog.arenaName, prog, startPos)
  }

  private fun isStartOfCompartment(): Boolean {
    val t = peek()
    if (t.type == TokenType.IMP_STAR || t.type == TokenType.IMPT_STAR || t.type == TokenType.HASH_LBRACE) return true
    if (t.type == TokenType.IDENT && (t.lexeme == "ssta" || t.lexeme == "sta" || t.lexeme == "iid")) return true
    return false
  }

  private fun isNextSsta(): Boolean {
    var k = 0
    while (peek(k).type == TokenType.IMP_STAR || peek(k).type == TokenType.IMPT_STAR) {
      while (peek(k).type != TokenType.EOF && peek(k).lexeme != "ed" && peek(k).type != TokenType.DOLLAR) k++
      k++
    }
    return peek(k).type == TokenType.IDENT && peek(k).lexeme == "ssta"
  }

  // --- Program Level ---
  private var customFlags = mutableSetOf<String>()
  private var activeFlags = mutableSetOf<String>()
  private var sealedFlags = mutableSetOf<String>()
  private var inMirror = false
  private var inAaC = false
  private var aacIsPermanent = false
  private var hasInitEndComand = false
  private var lastAutoClosePosIdx = -1
  private var printAlias = "ids.pri"
  private var mainAlias = "main"

  private fun parseSingleProgram(
    isPartOfSdAll: Boolean,
    inheritedAaC: Boolean = false,
    preMilestones: List<AaCMilestoneNode> = emptyList(),
  ): ProgramNode {
    if (inheritedAaC) {
      inAaC = true
      aacIsPermanent = true
    }

    // Check if standalone Register Arena #{ ... }# at top level (.sdall compartment)
    if (check(TokenType.HASH_LBRACE)) {
      val pos = SourcePos(peek().line, peek().col, fileName)
      val regStmts = parseRegLoadBlock()
      return ProgramNode(
        isSdpp = true,
        imports = emptyList(),
        aacMilestones = preMilestones,
        mainBody = regStmts,
        mainPos = pos,
        hasExplicitMain = false,
      )
    }

    // Check if Sd++ program (contains ssta or explicit .sdpp file)
    val startsWithSsta = check(TokenType.IDENT, "ssta")
    val containsSstaOrDoubleHash = tokens.any {
      (it.type == TokenType.IDENT && it.lexeme == "ssta") || it.type == TokenType.DOUBLE_HASH
    }

    if (startsWithSsta || isExplicitSdppExt || containsSstaOrDoubleHash) {
      return parseSdppProgram(isPartOfSdAll, preMilestones)
    }

    return parseSdCoreProgram(preMilestones)
  }

  private fun parseSdCoreProgram(preMilestones: List<AaCMilestoneNode>): ProgramNode {
    val imports = mutableListOf<ImportDecl>()
    val structs = mutableListOf<StructDecl>()
    val functions = mutableListOf<FnDecl>()
    val voids = mutableListOf<VoidDecl>()
    var mainBody: List<Stmt>? = null
    var mainPos = SourcePos(1, 1, fileName)

    while (!check(TokenType.EOF)) {
      val t = peek()
      if (t.type == TokenType.SEMI) {
        err("E102", t, "dấu ';' không hợp lệ trong chương trình Sd")
      }
      if (t.type == TokenType.IDENT && t.lexeme in SWITCH_NAMES) {
        err("E222", t, "công tắc '${t.lexeme}' chỉ dùng trong tệp .sdall ở ngoài khung")
      }
      if (t.type == TokenType.IMP_STAR) {
        val imp = parseImport()
        imports.add(imp)
        resolveUserImport(imp)
        continue
      }
      if (check(TokenType.IDENT, "sta") && peek(1).type == TokenType.LPAREN && peek(2).lexeme == "main") {
        if (mainBody != null) err("E302", t, "chương trình có nhiều hơn một sta(main)")
        mainPos = SourcePos(t.line, t.col, fileName)
        advance() // sta
        expect(TokenType.LPAREN, "E202")
        expect(TokenType.IDENT, "E202") // main
        expect(TokenType.RPAREN, "E202")
        expect(TokenType.LBRACE, "E203", "thiếu '{' mở khung main")
        val stmts = mutableListOf<Stmt>()
        while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
          stmts.add(parseStatement(inLoop = false, inSb = false))
        }
        if (!match(TokenType.RBRACE)) {
          err("E203", peek(), "thiếu '}' đóng khung sta(main)")
        }
        mainBody = stmts
        continue
      }
      if (check(TokenType.IDENT, "iid") && peek(1).type == TokenType.COLON) {
        // Could be struct, fn, or void
        val unitStart = idx
        advance(); advance() // iid :
        if (check(TokenType.IDENT, "sta") && peek(1).type == TokenType.LPAREN) {
          val kindTok = peek(2)
          when (kindTok.lexeme) {
            "struct" -> { structs.add(parseStructBlock(SourcePos(t.line, t.col, fileName))); continue }
            "fn" -> { functions.add(parseFnBlock(SourcePos(t.line, t.col, fileName))); continue }
            "void" -> { voids.add(parseVoidBlock(SourcePos(t.line, t.col, fileName))); continue }
          }
        }
        idx = unitStart
        err("E202", t, "khối ngoài sta(main) không hợp lệ: '${t.lexeme}'")
      }
      if (check(TokenType.RBRACE)) {
        err("E203", t, "thừa dấu '}'")
      }
      err("E202", t, "lệnh hoặc khai báo không nhận dạng ở cấp ngoài: '${t.lexeme}'")
    }

    if (mainBody == null && !isPartOfImportedLibrary()) {
      err("E204", peek(), "chương trình Sd thiếu khung sta(main){ ... }")
    }

    return ProgramNode(
      isSdpp = false,
      imports = imports,
      aacMilestones = preMilestones,
      structs = structs,
      functions = functions,
      voids = voids,
      mainBody = mainBody ?: emptyList(),
      mainPos = mainPos,
      hasExplicitMain = mainBody != null,
    )
  }

  private fun isPartOfImportedLibrary(): Boolean = importStack.isNotEmpty()

  private fun parseSdppProgram(
    isPartOfSdAll: Boolean,
    preMilestones: List<AaCMilestoneNode>,
  ): ProgramNode {
    // Allow pre-ssta imports in .sdpp if any? Textbook puts imp* inside ssta { ... }
    val preImports = mutableListOf<ImportDecl>()
    while (check(TokenType.IMP_STAR)) {
      val imp = parseImport()
      preImports.add(imp)
      resolveUserImport(imp)
    }

    if (!match(TokenType.IDENT, "ssta")) {
      err("E208", peek(), "chương trình Sd++ thiếu 'ssta {' ở đầu")
    }

    var arenaName: String? = null
    if (match(TokenType.LBRACKET)) {
      val nameTok = expect(TokenType.IDENT, "E202", "thiếu tên vùng ssta [...]")
      arenaName = nameTok.lexeme
      expect(TokenType.RBRACKET, "E202", "thiếu ']' sau tên vùng ssta")
    }

    expect(TokenType.LBRACE, "E203", "thiếu '{' sau ssta")

    // Scan for last top-level :& inside ssta (for init.endcomand mode)
    lastAutoClosePosIdx = findLastTopLevelColonAmp(idx)

    val imports = preImports
    val milestones = preMilestones.toMutableList()
    val defks = mutableListOf<DefkDecl>()
    val globals = mutableListOf<GlobalVarDecl>()
    val structs = mutableListOf<StructDecl>()
    val functions = mutableListOf<FnDecl>()
    val voids = mutableListOf<VoidDecl>()
    val defineRenames = LinkedHashMap<String, String>()
    var explicitMainBody: List<Stmt>? = null
    var explicitMainPos: SourcePos? = null
    val nakedMainBody = mutableListOf<Stmt>()
    var nakedMainPos: SourcePos? = null

    while (!check(TokenType.RBRACE) && !check(TokenType.EOF) && !check(TokenType.DOUBLE_HASH)) {
      val t = peek()

      if (t.type == TokenType.IDENT && t.lexeme in SWITCH_NAMES && peek(1).lexeme in listOf("ssta", "sta")) {
        err("E222", t, "công tắc '${t.lexeme}' phải đặt ngoài khung ssta")
      }

      // Check top-level :& (closes *m or init.endcomand region)
      if (t.type == TokenType.COLON_AMP) {
        if (inMirror) {
          inMirror = false
        }
        advance()
        continue
      }

      // Check *m standalone (Mirror region start)
      if (isStandaloneMirrorStart()) {
        if (inMirror) {
          err("E213", t, "không được lồng *m bên trong vùng *m chưa đóng")
        }
        if (inAaC) {
          warnings.add(
            SdDiagnostic(
              code = "W206",
              phase = 2,
              line = t.line,
              col = t.col,
              fileName = fileName,
              message = "vùng *m ... :& đặt trong vùng AaC là thừa",
              severity = DiagnosticSeverity.WARNING,
            )
          )
        }
        inMirror = true
        advance(); advance() // * m
        continue
      }

      // init.endcomand: ":&" $
      if (check(TokenType.IDENT, "init") && peek(1).type == TokenType.DOT && peek(2).lexeme == "endcomand") {
        advance(); advance(); advance()
        expect(TokenType.COLON, "E201")
        expect(TokenType.STR_LIT, "E201")
        consumeStatementEnd(requireExplicit = true)
        hasInitEndComand = true
        continue
      }

      // imp*
      if (check(TokenType.IMP_STAR)) {
        val imp = parseImport()
        imports.add(imp)
        resolveUserImport(imp)
        continue
      }

      // impt*AaC...
      if (check(TokenType.IMPT_STAR)) {
        if (inAaC) {
          err("E219", t, "hai cột mốc impt*AaC chồng nhau")
        }
        val m = parseAaCMilestone()
        milestones.add(m)
        inAaC = true
        aacIsPermanent = m.isPermanent
        nakedMainBody.add(Stmt.AaCEnterStmt(m.isPermanent, m.pos))
        continue
      }

      // defk<...> { ... }
      if (check(TokenType.IDENT, "defk")) {
        val d = parseDefkBlock()
        defks.add(d)
        continue
      }

      // define[...] { ... } €
      if (check(TokenType.IDENT, "define")) {
        val defResult = parseDefineBlock(
          structs = structs,
          functions = functions,
          voids = voids,
          renames = defineRenames,
        )
        if (defResult != null) {
          if (explicitMainBody != null) err("E302", t, "trùng khung main")
          explicitMainBody = defResult.first
          explicitMainPos = defResult.second
        }
        continue
      }

      // Naked globals: ids.g:, sta.arr.g:, ptr.g:, sta.iid:, sta.ed:
      if (isGlobalDeclStart()) {
        globals.add(parseGlobalDecl())
        continue
      }

      // Struct / fn / void
      if (check(TokenType.IDENT, "iid") && peek(1).type == TokenType.COLON &&
        peek(2).lexeme == "sta" && peek(3).type == TokenType.LPAREN &&
        peek(4).lexeme in listOf("struct", "fn", "void")
      ) {
        advance(); advance()
        when (peek(2).lexeme) {
          "struct" -> structs.add(parseStructBlock(SourcePos(t.line, t.col, fileName)))
          "fn" -> functions.add(parseFnBlock(SourcePos(t.line, t.col, fileName)))
          "void" -> voids.add(parseVoidBlock(SourcePos(t.line, t.col, fileName)))
        }
        continue
      }

      // Explicit main: { ... } $ or sta(main){ ... }
      if ((check(TokenType.IDENT, mainAlias) && peek(1).type == TokenType.COLON && peek(2).type == TokenType.LBRACE) ||
        (check(TokenType.IDENT, "sta") && peek(1).type == TokenType.LPAREN && peek(2).lexeme == "main")
      ) {
        if (nakedMainBody.isNotEmpty()) {
          err("E214", t, "vừa có khung main vừa có lệnh viết thẳng trong ssta")
        }
        if (explicitMainBody != null) err("E302", t, "trùng khung main")
        val mPos = SourcePos(t.line, t.col, fileName)
        if (check(TokenType.IDENT, "sta")) {
          advance(); advance(); advance(); advance() // sta ( main )
        } else {
          advance(); advance() // main :
        }
        expect(TokenType.LBRACE, "E203")
        val body = parseSdppBlockBody(inLoop = false, inSb = false)
        expect(TokenType.RBRACE, "E203", "thiếu '}' đóng khung main")
        if (check(TokenType.DOLLAR) || peek().lexeme in activeFlags) {
          advance()
        }
        explicitMainBody = body
        explicitMainPos = mPos
        continue
      }

      // Otherwise: naked statement in ssta (becomes implicit main!)
      if (explicitMainBody != null) {
        err("E214", t, "vừa có khung main vừa có lệnh viết thẳng trong ssta")
      }
      if (nakedMainPos == null) nakedMainPos = SourcePos(t.line, t.col, fileName)
      nakedMainBody.add(parseStatement(inLoop = false, inSb = false, isSdppCtx = true))
    }

    if (check(TokenType.DOUBLE_HASH)) {
      err("E203", peek(), "thiếu '}' đóng khung ssta trước ##")
    }
    if (!match(TokenType.RBRACE)) {
      err("E203", peek(), "thiếu '}' đóng khung ssta")
    }

    if (!match(TokenType.DOUBLE_HASH)) {
      err("E208", peek(), "thiếu '##' ở cuối chương trình Sd++")
    }

    // Nothing allowed after ## (except EOF, or SEMI in .sdall)
    if (!check(TokenType.EOF)) {
      val extra = peek()
      err("E208", extra, "không được có mã '${extra.lexeme}' phía sau ##")
    }

    val finalMain = explicitMainBody ?: nakedMainBody
    val finalMainPos = explicitMainPos ?: nakedMainPos ?: SourcePos(1, 1, fileName)

    return ProgramNode(
      isSdpp = true,
      arenaName = arenaName,
      imports = imports,
      aacMilestones = milestones,
      defks = defks,
      globals = globals,
      structs = structs,
      functions = functions,
      voids = voids,
      defineRenames = defineRenames,
      mainBody = finalMain,
      mainPos = finalMainPos,
      hasExplicitMain = explicitMainBody != null,
    )
  }

  private fun findLastTopLevelColonAmp(startIdx: Int): Int {
    var depth = 1 // inside ssta {
    var last = -1
    var k = startIdx
    while (k < tokens.size) {
      val t = tokens[k]
      when (t.type) {
        TokenType.LBRACE, TokenType.HASH_LBRACE -> depth++
        TokenType.RBRACE, TokenType.RBRACE_HASH -> {
          depth--
          if (depth <= 0) break
        }
        TokenType.COLON_AMP -> if (depth == 1) last = k
        else -> {}
      }
      k++
    }
    return last
  }

  private fun isInsideAutoCloseRegion(): Boolean {
    return hasInitEndComand && lastAutoClosePosIdx >= 0 && idx < lastAutoClosePosIdx
  }

  private fun isStandaloneMirrorStart(): Boolean {
    if (!check(TokenType.STAR) || peek(1).type != TokenType.IDENT || peek(1).lexeme != "m") return false
    val after = peek(2)
    // *m(...) is borrowed deref, *m = ... or *m + ... is pointer deref
    if (after.type in listOf(
        TokenType.LPAREN, TokenType.ASSIGN, TokenType.PLUS, TokenType.MINUS,
        TokenType.STAR, TokenType.SLASH, TokenType.PERCENT, TokenType.EQ, TokenType.NEQ,
        TokenType.LT, TokenType.GT, TokenType.LTE, TokenType.GTE, TokenType.DOT,
      )
    ) {
      return false
    }
    return true
  }

  private fun parseImport(): ImportDecl {
    val startTok = advance() // imp*
    val pos = SourcePos(startTok.line, startTok.col, fileName)
    val nameTok = expect(TokenType.IDENT, "E202", "thiếu tên thư viện sau imp*")
    var isSdppFlag = false
    if (match(TokenType.DOT)) {
      if (match(TokenType.DOLLAR)) {
        isSdppFlag = true
      } else if (match(TokenType.IDENT, "ed")) {
        isSdppFlag = false
      } else {
        err("E201", peek(), "dòng imp* phải kết thúc bằng .ed hoặc .$")
      }
    } else if (match(TokenType.DOLLAR)) {
      isSdppFlag = true
    } else {
      err("E201", peek(), "dòng imp* phải kết thúc bằng .ed hoặc .$")
    }
    return ImportDecl(nameTok.lexeme, isSdppFlag, pos)
  }

  private fun resolveUserImport(imp: ImportDecl) {
    if (imp.name in STD_LIBS) return
    val candidateSd = "${imp.name}.sd"
    val candidateSdpp = "${imp.name}.sdpp"
    val targetFile = when {
      externalFiles.containsKey(candidateSd) -> candidateSd
      externalFiles.containsKey(candidateSdpp) -> candidateSdpp
      externalFiles.containsKey(imp.name) -> imp.name
      else -> err("E215", SdToken(TokenType.IDENT, imp.name, imp.pos.line, imp.pos.col), "không tìm thấy thư viện hoặc tệp '${imp.name}.sd'")
    }
    if (targetFile in importStack) {
      err("E211", SdToken(TokenType.IDENT, imp.name, imp.pos.line, imp.pos.col), "nạp tệp vòng tròn: $targetFile")
    }
    if (importedUnits.containsKey(imp.name)) return

    val content = externalFiles[targetFile]!!
    val lexRes = SdLexer(content, targetFile).tokenize()
    warnings.addAll(lexRes.warnings)
    val subParser = SdParser(
      tokens = lexRes.tokens,
      fileName = targetFile,
      isSdAll = false,
      isExplicitSdppExt = targetFile.endsWith(".sdpp"),
      externalFiles = externalFiles,
      importStack = importStack + targetFile,
      initialWarnings = warnings,
    )
    val unit = subParser.parseCompilationUnit()
    importedUnits.putAll(subParser.importedUnits)
    if (unit is SdCompilationUnit.SingleProgram) {
      importedUnits[imp.name] = unit.program
    }
  }

  private fun parseAaCMilestone(): AaCMilestoneNode {
    val startTok = advance() // impt*
    val pos = SourcePos(startTok.line, startTok.col, fileName)
    expect(TokenType.IDENT, "E202") // AaC
    expect(TokenType.DOT, "E202")
    var isPermanent = false
    if (match(TokenType.PLUS)) {
      isPermanent = true
      expect(TokenType.IDENT, "E202") // bin
      expect(TokenType.DOLLAR, "E201", "cột mốc impt*AaC.+bin$ phải kết thúc bằng $")
    } else {
      expect(TokenType.IDENT, "E202") // bin
      expect(TokenType.DOT, "E202")
      expect(TokenType.IDENT, "E202") // ed
    }
    return AaCMilestoneNode(isPermanent, pos)
  }

  private fun parseDefkBlock(): DefkDecl {
    val tok = advance() // defk
    val pos = SourcePos(tok.line, tok.col, fileName)
    expect(TokenType.LT, "E202", "thiếu '<' sau defk")
    // <ids.end: text "¥">
    expect(TokenType.IDENT, "E202")
    if (match(TokenType.DOT)) expect(TokenType.IDENT, "E202")
    expect(TokenType.COLON, "E202")
    expect(TokenType.IDENT, "E202") // text
    val symTok = expect(TokenType.STR_LIT, "E202", "thiếu chuỗi ký hiệu cờ trong defk")
    val sym = symTok.lexeme
    if (sym in RESERVED_FLAGS || sym in sealedFlags) {
      err("E209", symTok, "không được định nghĩa lại cờ dành riêng hoặc đã niêm phong '$sym'")
    }
    expect(TokenType.GT, "E202", "thiếu '>' đóng khai báo defk")
    expect(TokenType.LBRACE, "E203", "thiếu '{' mở thân defk")

    var sawSeal = false
    var depth = 1
    while (!check(TokenType.EOF) && depth > 0) {
      val cur = advance()
      when (cur.type) {
        TokenType.LBRACE -> depth++
        TokenType.RBRACE -> depth--
        TokenType.HASH -> if (depth == 1) sawSeal = true
        else -> {}
      }
    }
    if (!sawSeal) {
      err("E210", tok, "khối defk cho cờ '$sym' thiếu dấu '#' niêm phong")
    }
    customFlags.add(sym)
    sealedFlags.add(sym)
    return DefkDecl(sym, pos)
  }

  private fun parseDefineBlock(
    structs: MutableList<StructDecl>,
    functions: MutableList<FnDecl>,
    voids: MutableList<VoidDecl>,
    renames: MutableMap<String, String>,
  ): Pair<List<Stmt>, SourcePos>? {
    val defTok = advance() // define
    expect(TokenType.LBRACKET, "E202", "thiếu '[' sau define")
    while (!check(TokenType.RBRACKET) && !check(TokenType.EOF)) {
      // e.g. ids.edt: text = "main"  OR  pri.edt: text = "print"
      val kindTok = expect(TokenType.IDENT, "E202")
      expect(TokenType.DOT, "E202")
      expect(TokenType.IDENT, "E202") // edt
      expect(TokenType.COLON, "E202")
      expect(TokenType.IDENT, "E202") // text
      expect(TokenType.ASSIGN, "E202")
      val valTok = expect(TokenType.STR_LIT, "E202")
      renames[kindTok.lexeme] = valTok.lexeme
      if (kindTok.lexeme == "ids") mainAlias = valTok.lexeme
      if (kindTok.lexeme == "pri") printAlias = valTok.lexeme
      match(TokenType.COMMA)
    }
    expect(TokenType.RBRACKET, "E202", "thiếu ']' sau danh sách define")
    expect(TokenType.LBRACE, "E203", "thiếu '{' mở thân define")

    var mainRes: Pair<List<Stmt>, SourcePos>? = null
    while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
      val t = peek()
      if (check(TokenType.IDENT, "iid") && peek(1).type == TokenType.COLON &&
        peek(2).lexeme == "sta" && peek(3).type == TokenType.LPAREN &&
        peek(4).lexeme in listOf("struct", "fn", "void")
      ) {
        advance(); advance()
        when (peek(2).lexeme) {
          "struct" -> structs.add(parseStructBlock(SourcePos(t.line, t.col, fileName)))
          "fn" -> functions.add(parseFnBlock(SourcePos(t.line, t.col, fileName)))
          "void" -> voids.add(parseVoidBlock(SourcePos(t.line, t.col, fileName)))
        }
        continue
      }
      if (check(TokenType.IDENT, "defk")) {
        parseDefkBlock()
        continue
      }
      if (check(TokenType.IDENT, mainAlias) && peek(1).type == TokenType.COLON) {
        val mPos = SourcePos(t.line, t.col, fileName)
        advance(); advance()
        expect(TokenType.LBRACE, "E203")
        val body = parseSdppBlockBody(inLoop = false, inSb = false)
        expect(TokenType.RBRACE, "E203", "thiếu '}' đóng khung $mainAlias")
        consumeOptionalBlockFlag()
        mainRes = body to mPos
        continue
      }
      err("E202", t, "thành phần không hợp lệ trong khối define: '${t.lexeme}'")
    }
    expect(TokenType.RBRACE, "E203", "thiếu '}' đóng khối define")
    if (!match(TokenType.EURO)) {
      err("E201", peek(), "khối define phải kết thúc bằng cờ '€'")
    }
    return mainRes
  }

  private fun consumeOptionalBlockFlag() {
    if (check(TokenType.DOLLAR) || peek().lexeme in activeFlags) {
      advance()
    }
  }

  private fun isGlobalDeclStart(): Boolean {
    val t0 = peek().lexeme
    if (t0 in listOf("ids", "ptr") && peek(1).type == TokenType.DOT && peek(2).lexeme == "g" && peek(3).type == TokenType.COLON) {
      return true
    }
    if (t0 == "sta" && peek(1).type == TokenType.DOT && peek(2).lexeme == "arr" && peek(3).type == TokenType.DOT && peek(4).lexeme == "g") {
      return true
    }
    if (t0 == "sta" && peek(1).type == TokenType.DOT && peek(2).lexeme in listOf("iid", "ed") && peek(3).type == TokenType.COLON) {
      return true
    }
    return false
  }

  private fun parseGlobalDecl(): GlobalVarDecl {
    val start = peek()
    val pos = SourcePos(start.line, start.col, fileName)
    val kind = advance().lexeme // ids / ptr / sta
    expect(TokenType.DOT, "E202")
    val sub = advance().lexeme
    if (sub == "arr") {
      expect(TokenType.DOT, "E202")
      expect(TokenType.IDENT, "E202") // g
    }
    expect(TokenType.COLON, "E202")
    val nameTok = expect(TokenType.IDENT, "E202", "thiếu tên biến toàn cục")
    var initExpr: Expr? = null
    if (match(TokenType.ASSIGN)) {
      initExpr = parseExpr(allowCommaInArray = sub == "arr")
    }
    var explicitColonFlag = false
    if (match(TokenType.COLON)) explicitColonFlag = true

    if (isInsideAutoCloseRegion() || inMirror || inAaC) {
      if (check(TokenType.DONG) || check(TokenType.DOLLAR) || peek().lexeme in activeFlags) advance()
    } else {
      if (kind == "sta" && sub == "iid") {
        if (!match(TokenType.DOLLAR) && !match(TokenType.DONG)) {
          err("E201", peek(), "khai báo sta.iid: phải chốt bằng '$'")
        }
      } else {
        if (!match(TokenType.DONG) && !(explicitColonFlag && match(TokenType.DOLLAR))) {
          err("E201", peek(), "biến toàn cục trần phải chốt bằng '₫'")
        }
      }
    }

    val declType: SdType? = when {
      kind == "ptr" -> SdType.PtrType(null)
      else -> null
    }
    return GlobalVarDecl(nameTok.lexeme, declType, null, initExpr, false, pos)
  }

  private fun parseStructBlock(pos: SourcePos): StructDecl {
    advance(); advance(); advance(); advance() // sta ( struct )
    expect(TokenType.FAT_ARROW, "E202")
    expect(TokenType.IDENT, "E202", "thiếu thẻ dta") // dta
    expect(TokenType.COLON, "E202")
    expect(TokenType.IDENT, "E202") // sta
    val nameTok = expect(TokenType.IDENT, "E202", "thiếu tên struct")
    expect(TokenType.LBRACE, "E203")
    val fields = mutableListOf<StructFieldDecl>()
    while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
      val fPos = SourcePos(peek().line, peek().col, fileName)
      val typeTok = expect(TokenType.IDENT, "E202", "thiếu kiểu trường struct")
      val fNameTok = expect(TokenType.IDENT, "E202", "thiếu tên trường struct")
      fields.add(StructFieldDecl(fNameTok.lexeme, typeTok.lexeme, fPos))
      if (!match(TokenType.QUOTE_SEP)) break
    }
    expect(TokenType.RBRACE, "E203", "thiếu '}' đóng struct")
    if (!match(TokenType.IDENT, "strt")) {
      err("E202", peek(), "khối struct phải khép bằng 'strt ed'")
    }
    expect(TokenType.IDENT, "E201", "thiếu 'ed' sau strt")
    return StructDecl(nameTok.lexeme, fields, pos)
  }

  private fun parseParamList(): List<ParamDecl> {
    val params = mutableListOf<ParamDecl>()
    while (!check(TokenType.RPAREN) && !check(TokenType.EOF)) {
      val pPos = SourcePos(peek().line, peek().col, fileName)
      val typeTok = expect(TokenType.IDENT, "E202", "thiếu kiểu tham số")
      var ptrElem: String? = null
      if (typeTok.lexeme == "ptr" && match(TokenType.LT)) {
        ptrElem = expect(TokenType.IDENT, "E202").lexeme
        expect(TokenType.GT, "E202")
      }
      val nameTok = expect(TokenType.IDENT, "E202", "thiếu tên tham số")
      var arrSize: Int? = null
      if (match(TokenType.LBRACKET)) {
        val szTok = expect(TokenType.INT_LIT, "E202", "thiếu kích thước mảng tham số")
        arrSize = szTok.intVal.toInt()
        expect(TokenType.RBRACKET, "E202")
      }
      params.add(ParamDecl(nameTok.lexeme, typeTok.lexeme, ptrElem, arrSize, pPos))
      if (!match(TokenType.QUOTE_SEP)) break
    }
    return params
  }

  private fun parseFnBlock(pos: SourcePos): FnDecl {
    advance(); advance(); advance(); advance() // sta ( fn )
    expect(TokenType.FAT_ARROW, "E202")
    expect(TokenType.IDENT, "E202") // rtn
    expect(TokenType.COLON, "E202")
    expect(TokenType.IDENT, "E202") // sta
    val nameTok = expect(TokenType.IDENT, "E202", "thiếu tên hàm")
    expect(TokenType.LPAREN, "E202")
    val params = parseParamList()
    expect(TokenType.RPAREN, "E202")
    expect(TokenType.ARROW, "E202", "thiếu '->' trước kiểu trả về")
    val retTypeTok = expect(TokenType.IDENT, "E202", "thiếu kiểu trả về của hàm")
    var retPtrElem: String? = null
    if (retTypeTok.lexeme == "ptr" && match(TokenType.LT)) {
      retPtrElem = expect(TokenType.IDENT, "E202").lexeme
      expect(TokenType.GT, "E202")
    }
    expect(TokenType.LBRACE, "E203")
    val body = mutableListOf<Stmt>()
    while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
      body.add(parseStatement(inLoop = false, inSb = false))
    }
    expect(TokenType.RBRACE, "E203", "thiếu '}' đóng thân hàm ${nameTok.lexeme}")
    if (!match(TokenType.IDENT, "fn")) {
      err("E202", peek(), "khối hàm '${nameTok.lexeme}' phải khép bằng 'fn ed'")
    }
    if (!match(TokenType.IDENT, "ed")) {
      err("E201", peek(), "thiếu 'ed' sau fn")
    }
    if (body.isEmpty() || body.last() !is Stmt.RefStmt) {
      err("E207", SdToken(TokenType.IDENT, nameTok.lexeme, pos.line, pos.col), "hàm '${nameTok.lexeme}' có kiểu trả về ${retTypeTok.lexeme} nhưng câu lệnh cuối không phải ref*")
    }
    return FnDecl(nameTok.lexeme, params, retTypeTok.lexeme, retPtrElem, body, pos)
  }

  private fun parseVoidBlock(pos: SourcePos): VoidDecl {
    advance(); advance(); advance(); advance() // sta ( void )
    expect(TokenType.FAT_ARROW, "E202")
    expect(TokenType.IDENT, "E202") // unkw
    expect(TokenType.COLON, "E202")
    expect(TokenType.IDENT, "E202") // sta
    val nameTok = expect(TokenType.IDENT, "E202", "thiếu tên thủ tục")
    expect(TokenType.LPAREN, "E202")
    val params = parseParamList()
    expect(TokenType.RPAREN, "E202")
    expect(TokenType.LBRACE, "E203")
    val body = mutableListOf<Stmt>()
    while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
      body.add(parseStatement(inLoop = false, inSb = false))
    }
    expect(TokenType.RBRACE, "E203", "thiếu '}' đóng thủ tục ${nameTok.lexeme}")
    if (!match(TokenType.IDENT, "void")) {
      err("E202", peek(), "khối thủ tục '${nameTok.lexeme}' phải khép bằng 'void ed'")
    }
    if (!match(TokenType.IDENT, "ed")) {
      err("E201", peek(), "thiếu 'ed' sau void")
    }
    return VoidDecl(nameTok.lexeme, params, body, pos)
  }

  private fun parseSdppBlockBody(inLoop: Boolean, inSb: Boolean): List<Stmt> {
    val stmts = mutableListOf<Stmt>()
    while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
      if (check(TokenType.COLON_AMP)) {
        err("E213", peek(), "không được đặt ranh giới :& bên trong khối")
      }
      stmts.add(parseStatement(inLoop, inSb, isSdppCtx = true))
    }
    return stmts
  }

  private fun parseBlockStatements(inLoop: Boolean, inSb: Boolean, isSdppCtx: Boolean): List<Stmt> {
    val stmts = mutableListOf<Stmt>()
    while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
      if (check(TokenType.COLON_AMP)) {
        err("E213", peek(), "không được đặt ranh giới :& bên trong khối")
      }
      stmts.add(parseStatement(inLoop, inSb, isSdppCtx))
    }
    return stmts
  }

  private fun parseStatement(inLoop: Boolean, inSb: Boolean, isSdppCtx: Boolean = false): Stmt {
    val t = peek()
    val pos = SourcePos(t.line, t.col, fileName)

    if (t.type == TokenType.COLON_AMP) {
      err("E213", t, "ranh giới :& đặt sai vị trí")
    }

    // impt*AaC inside block
    if (check(TokenType.IMPT_STAR)) {
      if (inAaC) {
        err("E219", t, "hai cột mốc impt*AaC chồng nhau")
      }
      val m = parseAaCMilestone()
      inAaC = true
      aacIsPermanent = m.isPermanent
      return Stmt.AaCEnterStmt(m.isPermanent, m.pos)
    }

    // Hard Halt $$ outside ids sb
    if (check(TokenType.DOUBLE_DOLLAR)) {
      advance()
      return Stmt.HardHaltStmt(pos)
    }

    // init <customFlag>
    if (check(TokenType.IDENT, "init") && peek(1).type != TokenType.DOT) {
      advance()
      val flagTok = advance()
      if (flagTok.lexeme !in customFlags) {
        err("E202", flagTok, "cờ '${flagTok.lexeme}' chưa được khai báo bằng defk")
      }
      activeFlags.add(flagTok.lexeme)
      return Stmt.InitFlagStmt(flagTok.lexeme, pos)
    }

    // Register load block #{ #sdbin: ... # }#
    if (check(TokenType.HASH_LBRACE)) {
      val list = parseRegLoadBlock()
      return if (list.size == 1) list[0] else Stmt.BlockScopeStmt(list, pos)
    }

    // Atomic register push & nullify: :p > sdppbin: ... nx{ ... } $
    if (check(TokenType.COLON) && peek(1).lexeme == "p" && peek(2).type == TokenType.GT) {
      return parseRegPushNullify(pos)
    }

    // Standard Sd statement: iid: ...
    if (check(TokenType.IDENT, "iid") && peek(1).type == TokenType.COLON) {
      return parseIidStatement(inLoop, inSb, isSdppCtx)
    }

    // Sd++ Selection Block: ids sb(var) { ... } $ OR ids sb : { reback_flush() } : €
    if (check(TokenType.IDENT, "ids") && peek(1).lexeme == "sb") {
      return parseSbStatement(pos)
    }

    // Sd++ flat print: ids.pri: ... $ OR <printAlias>: ... $ OR print: ...
    if ((check(TokenType.IDENT, "ids") && peek(1).type == TokenType.DOT && peek(2).lexeme == "pri") ||
      (check(TokenType.IDENT, printAlias) && peek(1).type == TokenType.COLON) ||
      (check(TokenType.IDENT, "print") && peek(1).type == TokenType.COLON)
    ) {
      if (check(TokenType.IDENT, "ids")) {
        advance(); advance(); advance()
      } else {
        advance()
      }
      expect(TokenType.COLON, "E201")
      val expr = parseExpr()
      consumeStatementEnd(requireExplicit = !(inMirror || inAaC || isInsideAutoCloseRegion()))
      return Stmt.PriStmt(expr, pos)
    }

    // Sd++ flat decl: iptedt.<type> ...
    if (check(TokenType.IDENT, "iptedt") && peek(1).type == TokenType.DOT) {
      return parseIptEdtDecl(pos)
    }

    // ref* ...
    if (check(TokenType.REF_STAR)) {
      return parseRefStarFlat(pos)
    }

    // sta.free(...) $
    if (check(TokenType.IDENT, "sta") && peek(1).type == TokenType.DOT && peek(2).lexeme == "free") {
      advance(); advance(); advance()
      expect(TokenType.LPAREN, "E202")
      val arg = parseExpr()
      expect(TokenType.RPAREN, "E202")
      consumeStatementEnd(requireExplicit = !(inMirror || inAaC || isInsideAutoCloseRegion()))
      return Stmt.FreeStmt(arg, pos)
    }

    // call ...
    if (check(TokenType.IDENT, "call")) {
      advance()
      val call = parseCallOrExpr(pos)
      if (call !is Expr.CallExpr) err("E202", t, "sau call phải là lời gọi hàm/thủ tục")
      consumeStatementEnd(requireExplicit = !(inMirror || inAaC || isInsideAutoCloseRegion()))
      return Stmt.CallStmt(call, pos)
    }

    // brk / nxt in Sd++ flat mode
    if (check(TokenType.IDENT, "brk")) {
      advance()
      consumeStatementEnd(requireExplicit = !(inMirror || inAaC || isInsideAutoCloseRegion()))
      return Stmt.BrkStmt(pos)
    }
    if (check(TokenType.IDENT, "nxt")) {
      advance()
      consumeStatementEnd(requireExplicit = !(inMirror || inAaC || isInsideAutoCloseRegion()))
      return Stmt.NxtStmt(pos)
    }

    // Assignment, inc/dec, borrowed var decl (name:& = expr), or module call (file.write(...))
    if (t.type == TokenType.IDENT && peek(1).type == TokenType.COLON_AMP) {
      // Borrowed var in Mirror / AaC: y:& = 0xBB
      val vName = advance().lexeme
      advance() // :&
      expect(TokenType.ASSIGN, "E201")
      val initExpr = parseExpr()
      consumeStatementEnd(requireExplicit = !(inMirror || inAaC || isInsideAutoCloseRegion()))
      return Stmt.VarDeclStmt(vName, "int", isBorrowed = true, initExpr = initExpr, pos = pos)
    }

    // Naked assignment / incdec / standalone call (including *p = ... or *m(x) = ...)
    val lhs = parseAssignableOrPostfix()
    if (match(TokenType.ASSIGN)) {
      val rhs = parseExpr()
      consumeStatementEnd(requireExplicit = !(inMirror || inAaC || isInsideAutoCloseRegion()))
      return Stmt.AssignStmt(lhs, rhs, pos, allowImplicitDecl = (inMirror || inAaC))
    }
    if (match(TokenType.INC)) {
      consumeStatementEnd(requireExplicit = !(inMirror || inAaC || isInsideAutoCloseRegion()))
      return Stmt.IncDecStmt(lhs, true, pos)
    }
    if (match(TokenType.DEC)) {
      consumeStatementEnd(requireExplicit = !(inMirror || inAaC || isInsideAutoCloseRegion()))
      return Stmt.IncDecStmt(lhs, false, pos)
    }
    if (lhs is Expr.CallExpr) {
      consumeStatementEnd(requireExplicit = !(inMirror || inAaC || isInsideAutoCloseRegion()))
      return Stmt.CallStmt(lhs, pos)
    }

    err("E202", t, "lệnh lạ hoặc thiếu cú pháp: '${t.lexeme}'")
  }

  private fun parseIidStatement(inLoop: Boolean, inSb: Boolean, isSdppCtx: Boolean): Stmt {
    val iidTok = advance() // iid
    val pos = SourcePos(iidTok.line, iidTok.col, fileName)
    expect(TokenType.COLON, "E202")
    val next = peek()

    // iid: brk ed / iid: nxt ed
    if (match(TokenType.IDENT, "brk")) {
      consumeStatementEnd(requireExplicit = true, allowEd = true)
      return Stmt.BrkStmt(pos)
    }
    if (match(TokenType.IDENT, "nxt")) {
      consumeStatementEnd(requireExplicit = true, allowEd = true)
      return Stmt.NxtStmt(pos)
    }
    // iid: ref* expr ed
    if (match(TokenType.REF_STAR)) {
      val expr = parseExpr()
      consumeStatementEnd(requireExplicit = true, allowEd = true)
      return Stmt.RefStmt(expr, pos)
    }
    // iid: call fn(...) ed
    if (match(TokenType.IDENT, "call")) {
      val expr = parsePostfix()
      if (expr !is Expr.CallExpr) err("E202", next, "sau call phải là lời gọi hàm")
      consumeStatementEnd(requireExplicit = true, allowEd = true)
      return Stmt.CallStmt(expr, pos)
    }

    if (!match(TokenType.IDENT, "sta")) {
      err("E202", next, "lệnh lạ: ${next.lexeme}")
    }
    expect(TokenType.LPAREN, "E202")
    val head = peek()

    // Block headers: bolean, for, while
    if (head.lexeme == "bolean" && peek(1).type == TokenType.RPAREN) {
      advance(); advance() // bolean )
      return parseBoleanBlock(pos, inLoop, inSb, isSdppCtx)
    }
    if (head.lexeme == "for" && peek(1).type == TokenType.RPAREN) {
      advance(); advance() // for )
      return parseForBlock(pos, inSb, isSdppCtx)
    }
    if (head.lexeme == "while" && peek(1).type == TokenType.RPAREN) {
      advance(); advance() // while )
      return parseWhileBlock(pos, inSb, isSdppCtx)
    }

    // pri: iid: sta(pri) => expr ed
    if (head.lexeme == "pri" && peek(1).type == TokenType.RPAREN) {
      advance(); advance()
      expect(TokenType.FAT_ARROW, "E202", "thiếu '=>' sau sta(pri)")
      val expr = parseExpr()
      consumeStatementEnd(requireExplicit = true, allowEd = true)
      return Stmt.PriStmt(expr, pos)
    }

    // ipt: iid: sta(ipt) => edt: sta(target) ed
    if (head.lexeme == "ipt" && peek(1).type == TokenType.RPAREN) {
      advance(); advance()
      expect(TokenType.FAT_ARROW, "E202")
      expect(TokenType.IDENT, "E202") // edt
      expect(TokenType.COLON, "E202")
      expect(TokenType.IDENT, "E202") // sta
      expect(TokenType.LPAREN, "E202")
      val target = parsePostfix()
      expect(TokenType.RPAREN, "E202")
      consumeStatementEnd(requireExplicit = true, allowEd = true)
      return Stmt.IptStmt(target, pos)
    }

    // Variable declaration: sta(type name) or sta(type name[N]) or sta(ptr<T> name)
    if (isTypeDeclInsideSta()) {
      val typeName = advance().lexeme
      var ptrElem: String? = null
      if (typeName == "ptr" && match(TokenType.LT)) {
        ptrElem = expect(TokenType.IDENT, "E202").lexeme
        expect(TokenType.GT, "E202")
      }
      val varName = expect(TokenType.IDENT, "E202", "thiếu tên biến").lexeme
      var arrSize: Int? = null
      if (match(TokenType.LBRACKET)) {
        val sz = expect(TokenType.INT_LIT, "E202", "kích thước mảng phải là số nguyên dương")
        arrSize = sz.intVal.toInt()
        expect(TokenType.RBRACKET, "E202")
      }
      var initExpr: Expr? = null
      if (match(TokenType.ASSIGN)) {
        initExpr = parseExpr()
      }
      expect(TokenType.RPAREN, "E202")
      if (match(TokenType.FAT_ARROW)) {
        expect(TokenType.IDENT, "E202") // edt
        expect(TokenType.COLON, "E202")
        expect(TokenType.IDENT, "E202") // sta
        expect(TokenType.LPAREN, "E202")
        expect(TokenType.IDENT, "E202", "thiếu tên biến trong phần edt")
        expect(TokenType.ASSIGN, "E202")
        initExpr = parseExpr()
        expect(TokenType.RPAREN, "E202")
      }
      consumeStatementEnd(requireExplicit = true, allowEd = true)
      return Stmt.VarDeclStmt(varName, typeName, ptrElem, arrSize, initExpr, pos = pos)
    }

    // Assignment or Inc/Dec inside sta(...) (including *p = ...)
    val lhs = parseAssignableOrPostfix()
    if (match(TokenType.ASSIGN)) {
      val rhs = parseExpr()
      expect(TokenType.RPAREN, "E202")
      consumeStatementEnd(requireExplicit = true, allowEd = true)
      return Stmt.AssignStmt(lhs, rhs, pos, allowImplicitDecl = (inMirror || inAaC))
    }
    if (match(TokenType.INC)) {
      expect(TokenType.RPAREN, "E202")
      consumeStatementEnd(requireExplicit = true, allowEd = true)
      return Stmt.IncDecStmt(lhs, true, pos)
    }
    if (match(TokenType.DEC)) {
      expect(TokenType.RPAREN, "E202")
      consumeStatementEnd(requireExplicit = true, allowEd = true)
      return Stmt.IncDecStmt(lhs, false, pos)
    }
    if (match(TokenType.RPAREN)) {
      if (match(TokenType.FAT_ARROW)) {
        // Full form: iid: sta(a) => edt: sta(a = expr) ed
        expect(TokenType.IDENT, "E202") // edt
        expect(TokenType.COLON, "E202")
        expect(TokenType.IDENT, "E202") // sta
        expect(TokenType.LPAREN, "E202")
        val target2 = parsePostfix()
        expect(TokenType.ASSIGN, "E202")
        val rhs = parseExpr()
        expect(TokenType.RPAREN, "E202")
        consumeStatementEnd(requireExplicit = true, allowEd = true)
        return Stmt.AssignStmt(target2, rhs, pos)
      }
    }

    err("E202", head, "câu lệnh sta(...) không hợp lệ")
  }

  private fun isTypeDeclInsideSta(): Boolean {
    val t0 = peek()
    val t1 = peek(1)
    if (t0.type != TokenType.IDENT) return false
    if (t0.lexeme == "ptr" && t1.type == TokenType.LT) return true
    if (t0.lexeme in KNOWN_TYPES && t1.type == TokenType.IDENT) return true
    // Custom struct type: <StructName> <varName>
    if (t1.type == TokenType.IDENT) return true
    return false
  }

  private fun parseBoleanBlock(pos: SourcePos, inLoop: Boolean, inSb: Boolean, isSdppCtx: Boolean): Stmt.BoleanStmt {
    expect(TokenType.FAT_ARROW, "E202")
    expect(TokenType.IDENT, "E202") // cdn
    expect(TokenType.COMMA, "E202")
    expect(TokenType.IDENT, "E202") // tf
    expect(TokenType.COLON, "E202")

    val branches = mutableListOf<Stmt.BoleanBranch>()
    while (check(TokenType.IDENT, "sta")) {
      val bTok = advance()
      val bPos = SourcePos(bTok.line, bTok.col, fileName)
      expect(TokenType.LPAREN, "E202")
      val cond: Expr? = if (match(TokenType.IDENT, "else")) {
        null
      } else {
        // Check if user wrote = instead of == inside condition -> E303!
        val e = parseExprOrDetectAssignInCond()
        e
      }
      expect(TokenType.RPAREN, "E202")
      expect(TokenType.LBRACE, "E203")
      val body = parseBlockStatements(inLoop, inSb, isSdppCtx)
      expect(TokenType.RBRACE, "E203", "thiếu '}' đóng nhánh bolean")
      branches.add(Stmt.BoleanBranch(cond, body, bPos))
    }

    if (!match(TokenType.IDENT, "bln")) {
      val bad = peek()
      err("E202", bad, "khối bolean chưa khép hoặc khép sai loại (cần 'bln ed', gặp '${bad.lexeme}')")
    }
    if (!match(TokenType.IDENT, "ed")) {
      err("E201", peek(), "thiếu 'ed' sau bln")
    }
    return Stmt.BoleanStmt(branches, pos)
  }

  private fun parseForBlock(pos: SourcePos, inSb: Boolean, isSdppCtx: Boolean): Stmt.ForStmt {
    expect(TokenType.FAT_ARROW, "E202")
    expect(TokenType.IDENT, "E202") // cdn
    expect(TokenType.COMMA, "E202")
    expect(TokenType.IDENT, "E202") // chck
    expect(TokenType.COLON, "E202")

    if (!match(TokenType.IDENT, "dbl")) {
      err("E202", peek(), "vòng for phải mở thân bằng 'dbl sta(...)'")
    }
    expect(TokenType.IDENT, "E202") // sta
    expect(TokenType.LPAREN, "E202")

    // Part 1: [type] name = expr
    if (check(TokenType.QUOTE_SEP)) {
      err("E206", peek(), "đầu vòng for thiếu mục khởi tạo trước dấu '")
    }
    var initType: String? = null
    if (peek().type == TokenType.IDENT && peek(1).type == TokenType.IDENT) {
      initType = advance().lexeme
    }
    val varName = expect(TokenType.IDENT, "E206", "đầu vòng for thiếu tên biến khởi tạo").lexeme
    expect(TokenType.ASSIGN, "E206", "đầu vòng for thiếu '=' khởi tạo")
    val initExpr = parseExpr()

    if (!match(TokenType.QUOTE_SEP)) {
      err("E206", peek(), "đầu vòng for phải có 3 mục ngăn bằng dấu nháy đơn '")
    }
    if (check(TokenType.QUOTE_SEP)) {
      err("E206", peek(), "đầu vòng for thiếu điều kiện")
    }
    val cond = parseExprOrDetectAssignInCond()

    if (!match(TokenType.QUOTE_SEP)) {
      err("E206", peek(), "đầu vòng for thiếu mục bước lặp sau dấu '")
    }
    if (check(TokenType.RPAREN)) {
      err("E206", peek(), "đầu vòng for thiếu bước lặp")
    }

    val stepPos = SourcePos(peek().line, peek().col, fileName)
    val stepLhs = parsePostfix()
    val stepStmt: Stmt = when {
      match(TokenType.INC) -> Stmt.IncDecStmt(stepLhs, true, stepPos)
      match(TokenType.DEC) -> Stmt.IncDecStmt(stepLhs, false, stepPos)
      match(TokenType.ASSIGN) -> Stmt.AssignStmt(stepLhs, parseExpr(), stepPos)
      else -> err("E206", peek(), "bước lặp của for phải là ++, -- hoặc phép gán")
    }
    expect(TokenType.RPAREN, "E202")
    expect(TokenType.LBRACE, "E203")
    val body = parseBlockStatements(inLoop = true, inSb = inSb, isSdppCtx = isSdppCtx)
    expect(TokenType.RBRACE, "E203", "thiếu '}' đóng thân vòng for")
    if (!match(TokenType.IDENT, "dbl")) {
      err("E202", peek(), "vòng for phải khép bằng 'dbl ed'")
    }
    if (!match(TokenType.IDENT, "ed")) {
      err("E201", peek(), "thiếu 'ed' sau dbl")
    }
    return Stmt.ForStmt(initType, varName, initExpr, cond, stepStmt, body, pos)
  }

  private fun parseWhileBlock(pos: SourcePos, inSb: Boolean, isSdppCtx: Boolean): Stmt.WhileStmt {
    expect(TokenType.FAT_ARROW, "E202")
    expect(TokenType.IDENT, "E202") // cdn
    expect(TokenType.COMMA, "E202")
    expect(TokenType.IDENT, "E202") // edt
    expect(TokenType.COLON, "E202")

    if (!match(TokenType.IDENT, "dbl")) {
      err("E202", peek(), "vòng while phải mở thân bằng 'dbl sta(...)'")
    }
    expect(TokenType.IDENT, "E202") // sta
    expect(TokenType.LPAREN, "E202")
    val cond = parseExprOrDetectAssignInCond()
    expect(TokenType.RPAREN, "E202")
    expect(TokenType.LBRACE, "E203")
    val body = parseBlockStatements(inLoop = true, inSb = inSb, isSdppCtx = isSdppCtx)
    expect(TokenType.RBRACE, "E203", "thiếu '}' đóng thân vòng while")
    if (!match(TokenType.IDENT, "dbl")) {
      err("E202", peek(), "vòng while phải khép bằng 'dbl ed'")
    }
    if (!match(TokenType.IDENT, "ed")) {
      err("E201", peek(), "thiếu 'ed' sau dbl")
    }
    return Stmt.WhileStmt(cond, body, pos)
  }

  private fun parseSbStatement(pos: SourcePos): Stmt {
    advance(); advance() // ids sb
    // Ch 14.5: ids sb : { reback_flush() } : €
    if (match(TokenType.COLON)) {
      expect(TokenType.LBRACE, "E203")
      while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) advance()
      expect(TokenType.RBRACE, "E203")
      match(TokenType.COLON)
      if (!match(TokenType.EURO) && !match(TokenType.DOLLAR)) {
        err("E201", peek(), "khối ids sb : { ... } thiếu cờ kết thúc")
      }
      return Stmt.SbFlushStmt(pos)
    }

    expect(TokenType.LPAREN, "E202")
    val targetVar = expect(TokenType.IDENT, "E202", "thiếu tên biến trong ids sb(...)").lexeme
    expect(TokenType.RPAREN, "E202")
    expect(TokenType.LBRACE, "E203")

    val branches = mutableListOf<Stmt.SbBranch>()
    while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
      val bTok = peek()
      val bPos = SourcePos(bTok.line, bTok.col, fileName)
      val cond: Expr? = when {
        match(TokenType.IDENT, "block") -> {
          val e = parseExprOrDetectAssignInCond()
          expect(TokenType.COLON, "E202", "thiếu ':' sau điều kiện block")
          e
        }
        match(TokenType.IDENT, "remaining") -> {
          expect(TokenType.COLON, "E202", "thiếu ':' sau remaining")
          null
        }
        else -> err("E202", bTok, "trong ids sb chỉ có nhánh 'block <đk>:' hoặc 'remaining:'")
      }

      val body = mutableListOf<Stmt>()
      var action = Stmt.SbAction.NONE
      while (!check(TokenType.RBRACE) && !check(TokenType.EOF) &&
        !check(TokenType.IDENT, "block") && !check(TokenType.IDENT, "remaining")
      ) {
        if (match(TokenType.DOUBLE_DOLLAR)) {
          action = Stmt.SbAction.REBACK
          break
        }
        if (match(TokenType.IDENT, "transf")) {
          if (match(TokenType.DOLLAR)) {
            if (match(TokenType.DOLLAR)) {
              warnings.add(
                SdDiagnostic("W202", 2, peek().line, peek().col, fileName, "dùng dạng cũ 'transf $ $'; nên dùng 'transf $'", severity = DiagnosticSeverity.WARNING)
              )
            }
          } else {
            consumeStatementEnd(requireExplicit = !(inMirror || inAaC))
          }
          action = Stmt.SbAction.TRANSF
          break
        }
        if (match(TokenType.IDENT, "break")) {
          if (match(TokenType.DOUBLE_DOLLAR)) {
            warnings.add(
              SdDiagnostic("W202", 2, peek().line, peek().col, fileName, "dùng dạng cũ 'break $$'; nên dùng 'break $'", severity = DiagnosticSeverity.WARNING)
            )
          } else {
            consumeStatementEnd(requireExplicit = !(inMirror || inAaC))
          }
          action = Stmt.SbAction.BREAK
          break
        }
        body.add(parseStatement(inLoop = false, inSb = true, isSdppCtx = true))
      }
      branches.add(Stmt.SbBranch(cond, body, action, bPos))
    }

    expect(TokenType.RBRACE, "E203", "thiếu '}' đóng khối ids sb")
    consumeStatementEnd(requireExplicit = !(inMirror || inAaC || isInsideAutoCloseRegion()))
    return Stmt.SbStmt(targetVar, branches, pos)
  }

  private fun parseIptEdtDecl(pos: SourcePos): Stmt.VarDeclStmt {
    advance(); advance() // iptedt .
    val typeTok = expect(TokenType.IDENT, "E202")
    var ptrElem: String? = null
    if (typeTok.lexeme == "ptr" && match(TokenType.LT)) {
      ptrElem = expect(TokenType.IDENT, "E202").lexeme
      expect(TokenType.GT, "E202")
    }
    val nameTok = expect(TokenType.IDENT, "E202", "thiếu tên biến")
    var isBorrowed = false
    if (match(TokenType.COLON_AMP)) {
      isBorrowed = true
    }
    var arrSize: Int? = null
    if (match(TokenType.LBRACKET)) {
      arrSize = expect(TokenType.INT_LIT, "E202").intVal.toInt()
      expect(TokenType.RBRACKET, "E202")
    }
    var initExpr: Expr? = null
    if (match(TokenType.ASSIGN)) {
      initExpr = parseExpr()
    }
    consumeStatementEnd(requireExplicit = !(inMirror || inAaC || isInsideAutoCloseRegion()))
    return Stmt.VarDeclStmt(nameTok.lexeme, typeTok.lexeme, ptrElem, arrSize, initExpr, isBorrowed = isBorrowed, pos = pos)
  }

  private fun parseRefStarFlat(pos: SourcePos): Stmt {
    advance() // ref*
    // Check simulated fixed address: ref* ptr_mem : 0x7FFF_A000 : $
    if (peek().type == TokenType.IDENT && peek(1).type == TokenType.COLON && peek(2).type == TokenType.INT_LIT) {
      val nameTok = advance()
      advance() // :
      val addrTok = advance()
      warnings.add(
        SdDiagnostic(
          code = "W204",
          phase = 2,
          line = pos.line,
          col = pos.col,
          fileName = fileName,
          message = "địa chỉ cố định (${nameTok.lexeme} : ${addrTok.lexeme}) chỉ được mô phỏng bằng biến số nguyên",
          severity = DiagnosticSeverity.WARNING,
        )
      )
      match(TokenType.COLON)
      consumeStatementEnd(requireExplicit = !(inMirror || inAaC || isInsideAutoCloseRegion()))
      return Stmt.FixedAddrSimStmt(nameTok.lexeme, addrTok.intVal, pos)
    }

    val expr = parseExpr()
    match(TokenType.COLON) // optional ':' before flag
    consumeStatementEnd(requireExplicit = !(inMirror || inAaC || isInsideAutoCloseRegion()), allowEd = true)
    match(TokenType.COLON) // supports ref*0 $:
    return Stmt.RefStmt(expr, pos)
  }

  private fun parseRegLoadBlock(): List<Stmt> {
    val startTok = advance() // #{
    val pos = SourcePos(startTok.line, startTok.col, fileName)
    warnings.add(
      SdDiagnostic("W205", 2, pos.line, pos.col, fileName, "dùng thanh ghi mô phỏng (0Ax–0Dx)", severity = DiagnosticSeverity.WARNING)
    )
    if (!match(TokenType.HASH) || !match(TokenType.IDENT, "sdbin")) {
      err("E216", peek(), "trong #{ ... }# phải bắt đầu bằng #sdbin:")
    }
    expect(TokenType.COLON, "E216")
    val loads = mutableListOf<Stmt.RegLoadStmt>()
    while (!check(TokenType.HASH) && !check(TokenType.RBRACE_HASH) && !check(TokenType.EOF)) {
      val regTok = expect(TokenType.IDENT, "E217", "thiếu tên thanh ghi trong #sdbin:")
      if (regTok.lexeme !in VALID_REGISTERS) {
        err("E217", regTok, "tên thanh ghi không hợp lệ '${regTok.lexeme}' (chỉ hỗ trợ 0Ax, 0Bx, 0Cx, 0Dx)")
      }
      expect(TokenType.LBRACKET, "E216", "thiếu '[' sau tên thanh ghi ${regTok.lexeme}")
      val valExpr = parseExpr()
      expect(TokenType.RBRACKET, "E216", "thiếu ']' đóng dữ liệu thanh ghi")
      loads.add(Stmt.RegLoadStmt(regTok.lexeme, valExpr, SourcePos(regTok.line, regTok.col, fileName)))
      if (loads.size > 4) {
        err("E221", regTok, "một lệnh nạp quá 4 thanh ghi cùng lúc")
      }
      match(TokenType.COMMA)
    }
    if (!match(TokenType.HASH)) {
      err("E216", startTok, "#sdbin: mở ở dòng ${startTok.line} thiếu '#' đóng")
    }
    if (!match(TokenType.RBRACE_HASH)) {
      err("E216", startTok, "khối #{ mở ở dòng ${startTok.line} thiếu '}#' đóng")
    }
    return loads
  }

  private fun parseRegPushNullify(pos: SourcePos): Stmt.RegPushNullifyStmt {
    advance(); advance(); advance() // : p >
    expect(TokenType.IDENT, "E202", "thiếu sdppbin sau :p >") // sdppbin
    expect(TokenType.COLON, "E202")
    var destType: String? = null
    var destName: String? = null
    if (peek().type == TokenType.IDENT && peek().lexeme != "nx") {
      destType = advance().lexeme
      destName = expect(TokenType.IDENT, "E202", "thiếu tên biến nhận từ sdppbin").lexeme
      expect(TokenType.ASSIGN, "E202")
    }
    expect(TokenType.IDENT, "E202", "thiếu nx{ ... }") // nx
    expect(TokenType.LBRACE, "E203")
    val regs = mutableListOf<String>()
    while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
      val rTok = expect(TokenType.IDENT, "E217")
      if (rTok.lexeme !in VALID_REGISTERS) {
        err("E217", rTok, "tên thanh ghi không hợp lệ '${rTok.lexeme}'")
      }
      regs.add(rTok.lexeme)
      if (regs.size > 4) {
        err("E221", rTok, "một lệnh nx{ } vượt quá 4 thanh ghi")
      }
      if (!match(TokenType.COMMA) && !match(TokenType.QUOTE_SEP)) break
    }
    expect(TokenType.RBRACE, "E203")
    consumeStatementEnd(requireExplicit = !(inMirror || inAaC || isInsideAutoCloseRegion()))
    return Stmt.RegPushNullifyStmt(destName, destType, regs, pos)
  }

  private fun consumeStatementEnd(requireExplicit: Boolean, allowEd: Boolean = false) {
    // Optional ':' before flag (Ch 14.5: `: $`)
    val hasColonBefore = check(TokenType.COLON) && (peek(1).type == TokenType.DOLLAR || peek(1).type == TokenType.DONG || peek(1).lexeme in activeFlags)
    if (hasColonBefore) advance()

    val next = peek()
    if (next.type == TokenType.DOLLAR) {
      advance()
      return
    }
    if (next.type == TokenType.IDENT && next.lexeme == "ed" && (allowEd || !requireExplicit || true)) {
      // Sd++ Ch 10: ed works as $ at statement level too
      advance()
      return
    }
    if (next.lexeme in activeFlags) {
      advance()
      return
    }
    if (!requireExplicit) {
      return
    }
    val expectedMsg = if (allowEd) "thiếu chốt 'ed' ở cuối lệnh" else "thiếu cờ chốt '$' ở cuối lệnh"
    err("E201", next, expectedMsg)
  }

  // --- Expressions ---
  private fun parseExprOrDetectAssignInCond(): Expr {
    val startPos = SourcePos(peek().line, peek().col, fileName)
    val e = parseExpr()
    if (check(TokenType.ASSIGN)) {
      val bad = advance()
      throw SdCompileException(
        SdDiagnostic(
          code = "E303",
          phase = 3,
          line = bad.line,
          col = bad.col,
          fileName = fileName,
          message = "không được dùng phép gán '=' trong điều kiện (hãy dùng '==')",
        )
      )
    }
    return e
  }

  private fun parseExpr(allowCommaInArray: Boolean = false): Expr = parseOr(allowCommaInArray)

  private fun parseOr(allowComma: Boolean): Expr {
    var left = parseAnd(allowComma)
    while (check(TokenType.IDENT, "or")) {
      val op = advance()
      val right = parseAnd(allowComma)
      left = Expr.BinaryExpr("or", left, right, SourcePos(op.line, op.col, fileName))
    }
    return left
  }

  private fun parseAnd(allowComma: Boolean): Expr {
    var left = parseEquality(allowComma)
    while (check(TokenType.IDENT, "and")) {
      val op = advance()
      val right = parseEquality(allowComma)
      left = Expr.BinaryExpr("and", left, right, SourcePos(op.line, op.col, fileName))
    }
    return left
  }

  private fun parseEquality(allowComma: Boolean): Expr {
    var left = parseRelational(allowComma)
    while (check(TokenType.EQ) || check(TokenType.NEQ)) {
      val op = advance()
      val right = parseRelational(allowComma)
      left = Expr.BinaryExpr(op.lexeme, left, right, SourcePos(op.line, op.col, fileName))
    }
    return left
  }

  private fun parseRelational(allowComma: Boolean): Expr {
    var left = parseAdditive(allowComma)
    while (check(TokenType.LT) || check(TokenType.LTE) || check(TokenType.GT) || check(TokenType.GTE)) {
      val op = advance()
      val right = parseAdditive(allowComma)
      left = Expr.BinaryExpr(op.lexeme, left, right, SourcePos(op.line, op.col, fileName))
    }
    return left
  }

  private fun parseAdditive(allowComma: Boolean): Expr {
    var left = parseMultiplicative(allowComma)
    while (check(TokenType.PLUS) || check(TokenType.MINUS)) {
      val op = advance()
      val right = parseMultiplicative(allowComma)
      left = Expr.BinaryExpr(op.lexeme, left, right, SourcePos(op.line, op.col, fileName))
    }
    return left
  }

  private fun parseMultiplicative(allowComma: Boolean): Expr {
    var left = parseUnary(allowComma)
    while (check(TokenType.STAR) || check(TokenType.SLASH) || check(TokenType.PERCENT)) {
      val op = advance()
      val right = parseUnary(allowComma)
      left = Expr.BinaryExpr(op.lexeme, left, right, SourcePos(op.line, op.col, fileName))
    }
    return left
  }

  private fun parseUnary(allowComma: Boolean): Expr {
    val t = peek()
    val pos = SourcePos(t.line, t.col, fileName)
    if (match(TokenType.IDENT, "not")) {
      return Expr.UnaryExpr("not", parseUnary(allowComma), pos)
    }
    if (match(TokenType.MINUS)) {
      return Expr.UnaryExpr("-", parseUnary(allowComma), pos)
    }
    if (match(TokenType.AT)) {
      return Expr.AddrOfExpr(parseUnary(allowComma), pos)
    }
    if (check(TokenType.STAR) && peek(1).lexeme == "m" && peek(2).type == TokenType.LPAREN) {
      advance(); advance(); advance() // * m (
      val vTok = expect(TokenType.IDENT, "E202", "thiếu tên biến trong *m(...)")
      expect(TokenType.RPAREN, "E202")
      return Expr.BorrowDerefExpr(vTok.lexeme, pos)
    }
    if (match(TokenType.STAR)) {
      return Expr.DerefExpr(parseUnary(allowComma), pos)
    }
    return parsePostfix(allowComma)
  }

  private fun parseAssignableOrPostfix(): Expr {
    val t = peek()
    val pos = SourcePos(t.line, t.col, fileName)
    if (check(TokenType.STAR) && peek(1).lexeme == "m" && peek(2).type == TokenType.LPAREN) {
      advance(); advance(); advance() // * m (
      val vTok = expect(TokenType.IDENT, "E202", "thiếu tên biến trong *m(...)")
      expect(TokenType.RPAREN, "E202")
      return Expr.BorrowDerefExpr(vTok.lexeme, pos)
    }
    if (match(TokenType.STAR)) {
      return Expr.DerefExpr(parseUnary(false), pos)
    }
    return parsePostfix()
  }

  private fun parsePostfix(allowComma: Boolean = false): Expr {
    var expr = parsePrimary(allowComma)
    while (true) {
      val t = peek()
      val pos = SourcePos(t.line, t.col, fileName)
      if (match(TokenType.LBRACKET)) {
        val indexExpr = parseExpr()
        expect(TokenType.RBRACKET, "E202", "thiếu ']' đóng chỉ số mảng")
        expr = Expr.IndexExpr(expr, indexExpr, pos)
      } else if (match(TokenType.DOT)) {
        val memberTok = expect(TokenType.IDENT, "E202", "thiếu tên trường hoặc hàm sau '.'")
        if (match(TokenType.LPAREN)) {
          val args = parseArgList()
          val modName = (expr as? Expr.VarExpr)?.name
            ?: err("E202", memberTok, "tên mô-đun gọi hàm không hợp lệ")
          expr = Expr.CallExpr(modName, memberTok.lexeme, args, pos)
        } else {
          expr = Expr.MemberExpr(expr, memberTok.lexeme, pos)
        }
      } else if (match(TokenType.LPAREN)) {
        val fnName = (expr as? Expr.VarExpr)?.name
          ?: err("E202", t, "biểu thức gọi hàm không hợp lệ")
        val args = parseArgList()
        expr = Expr.CallExpr(null, fnName, args, pos)
      } else {
        break
      }
    }
    return expr
  }

  private fun parseArgList(): List<Expr> {
    val args = mutableListOf<Expr>()
    while (!check(TokenType.RPAREN) && !check(TokenType.EOF)) {
      args.add(parseExpr())
      if (!match(TokenType.QUOTE_SEP)) {
        // Check if user mistakenly used comma between arguments
        if (check(TokenType.COMMA)) {
          err("E202", peek(), "đối số hàm phải ngăn bằng dấu nháy đơn ' (không dùng dấu phẩy)")
        }
        break
      }
    }
    expect(TokenType.RPAREN, "E202", "thiếu ')' đóng danh sách đối số")
    return args
  }

  private fun parseCallOrExpr(pos: SourcePos): Expr = parsePostfix()

  private fun parsePrimary(allowComma: Boolean = false): Expr {
    val t = peek()
    val pos = SourcePos(t.line, t.col, fileName)
    when (t.type) {
      TokenType.INT_LIT -> {
        advance()
        return Expr.IntLit(t.intVal, pos)
      }
      TokenType.FLOAT_LIT -> {
        advance()
        return Expr.FloatLit(t.floatVal, pos)
      }
      TokenType.STR_LIT -> {
        advance()
        if (t.hasLegacyFormat) {
          warnings.add(
            SdDiagnostic("W202", 2, t.line, t.col, fileName, "dùng định dạng chuỗi cũ không ngoặc nhọn (nên dùng /d{...})", severity = DiagnosticSeverity.WARNING)
          )
        }
        return Expr.StrLit(t.lexeme, t.hasLegacyFormat, pos)
      }
      TokenType.BOOL_LIT -> {
        advance()
        return Expr.BoolLit(t.boolVal, pos)
      }
      TokenType.LPAREN -> {
        advance()
        val inner = parseExpr()
        expect(TokenType.RPAREN, "E202", "thiếu ')' đóng biểu thức")
        return inner
      }
      TokenType.LBRACKET -> {
        advance()
        val elements = mutableListOf<Expr>()
        while (!check(TokenType.RBRACKET) && !check(TokenType.EOF)) {
          elements.add(parseExpr())
          if (match(TokenType.QUOTE_SEP)) continue
          if (match(TokenType.COMMA)) {
            warnings.add(
              SdDiagnostic("W202", 2, peek().line, peek().col, fileName, "mảng ngăn bằng dấu phẩy ',' là cú pháp cũ; nên dùng dấu nháy đơn '", severity = DiagnosticSeverity.WARNING)
            )
            continue
          }
          break
        }
        expect(TokenType.RBRACKET, "E202", "thiếu ']' đóng mảng")
        return Expr.ArrayLit(elements, pos)
      }
      TokenType.IDENT -> {
        if (t.lexeme in VALID_REGISTERS) {
          advance()
          return Expr.RegExpr(t.lexeme, pos)
        }
        if (t.lexeme.startsWith("0") && t.lexeme.length >= 2 && t.lexeme[1].isLetter()) {
          err("E217", t, "tên thanh ghi không hợp lệ '${t.lexeme}' (chỉ hỗ trợ 0Ax, 0Bx, 0Cx, 0Dx)")
        }
        // ids(ten).g global variable reference
        if (t.lexeme == "ids" && peek(1).type == TokenType.LPAREN) {
          advance(); advance() // ids (
          val gName = expect(TokenType.IDENT, "E202", "thiếu tên biến toàn cục trong ids(...)").lexeme
          expect(TokenType.RPAREN, "E202")
          expect(TokenType.DOT, "E202")
          expect(TokenType.IDENT, "E202") // g
          return Expr.GlobalVarExpr(gName, pos)
        }
        advance()
        return Expr.VarExpr(t.lexeme, pos)
      }
      else -> err("E202", t, "biểu thức không hợp lệ tại '${t.lexeme}'")
    }
  }
}
