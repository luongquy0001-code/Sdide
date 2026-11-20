package com.example.sdide.engine

class SdSemanticAnalyzer(
  private val fileName: String? = null,
  private val externalPrograms: Map<String, ProgramNode> = emptyMap(),
) {
  val warnings = mutableListOf<SdDiagnostic>()

  private data class VarSymbol(
    val name: String,
    val type: SdType,
    val pos: SourcePos,
    val isBorrowed: Boolean = false,
    var used: Boolean = false,
  )

  private data class FnSignature(
    val name: String,
    val params: List<Pair<String, SdType>>,
    val returnType: SdType,
    val pos: SourcePos,
    var used: Boolean = false,
  )

  private data class VoidSignature(
    val name: String,
    val params: List<Pair<String, SdType>>,
    val pos: SourcePos,
    var used: Boolean = false,
  )

  private class Scope(val parent: Scope? = null) {
    val vars = LinkedHashMap<String, VarSymbol>()

    fun declare(sym: VarSymbol, errOnDup: Boolean = true): VarSymbol {
      if (vars.containsKey(sym.name) && errOnDup) {
        throw SdCompileException(
          SdDiagnostic("E302", 3, sym.pos.line, sym.pos.col, sym.pos.fileName, "trùng tên biến '${sym.name}' trong cùng phạm vi")
        )
      }
      vars[sym.name] = sym
      return sym
    }

    fun resolve(name: String): VarSymbol? {
      return vars[name] ?: parent?.resolve(name)
    }
  }

  fun analyze(unit: SdCompilationUnit): List<SdDiagnostic> {
    when (unit) {
      is SdCompilationUnit.SingleProgram -> {
        val loadedRegs = LinkedHashMap<String, SourcePos>()
        analyzeProgram(unit.program, isMainUnit = true, loadedRegs = loadedRegs, sharedGlobals = null)
        // Check E220 before ## (unless covered by AaC)
        if (loadedRegs.isNotEmpty() && unit.program.aacMilestones.isEmpty()) {
          val (reg, pos) = loadedRegs.entries.first()
          throw SdCompileException(
            SdDiagnostic(
              code = "E220",
              phase = 3,
              line = pos.line,
              col = pos.col,
              fileName = pos.fileName,
              message = "thanh ghi '$reg' đã nạp bằng #sdbin: nhưng chưa được triệt tiêu bằng :p > sdppbin: nx{...} trước ##",
              hint = "thêm :p > sdppbin: nx{$reg} $ trước ##",
            )
          )
        }
      }
      is SdCompilationUnit.SdAllProgram -> {
        val sharedGlobals = Scope(null)
        val loadedRegs = LinkedHashMap<String, SourcePos>()
        var anyAaC = false
        for (comp in unit.compartments) {
          if (comp.switch == CompartmentSwitch.OFF || comp.switch == CompartmentSwitch.PAUSE) continue
          if (comp.program.aacMilestones.isNotEmpty()) anyAaC = true
          analyzeProgram(
            program = comp.program,
            isMainUnit = true,
            loadedRegs = loadedRegs,
            sharedGlobals = sharedGlobals,
          )
        }
        if (loadedRegs.isNotEmpty() && !anyAaC) {
          val (reg, pos) = loadedRegs.entries.first()
          throw SdCompileException(
            SdDiagnostic(
              code = "E220",
              phase = 3,
              line = pos.line,
              col = pos.col,
              fileName = pos.fileName,
              message = "thanh ghi '$reg' chưa được triệt tiêu bằng :p > sdppbin: nx{...} ở cuối dây chuyền .sdall",
            )
          )
        }
      }
    }
    return warnings
  }

  private fun analyzeProgram(
    program: ProgramNode,
    isMainUnit: Boolean,
    loadedRegs: MutableMap<String, SourcePos>,
    sharedGlobals: Scope?,
  ) {
    // Sd++ has console I/O and heap operations as built-ins; other stdlibs stay explicit.
    val importedLibs = program.imports.map { it.name }.toSet() +
      if (program.isSdpp) setOf("ioprt", "mem") else emptySet()
    val structs = LinkedHashMap<String, SdType.StructType>()
    val functions = LinkedHashMap<String, FnSignature>()
    val voids = LinkedHashMap<String, VoidSignature>()
    val importedModulesFns = LinkedHashMap<String, Map<String, FnSignature>>()
    val importedModulesVoids = LinkedHashMap<String, Map<String, VoidSignature>>()
    val globalScope = sharedGlobals ?: Scope(null)

    // Register structs first (local + imported)
    for (imp in program.imports) {
      val ext = externalPrograms[imp.name] ?: continue
      for (st in ext.structs) {
        registerStruct(st, structs)
      }
    }
    for (st in program.structs) {
      if (structs.containsKey(st.name)) {
        throw SdCompileException(SdDiagnostic("E302", 3, st.pos.line, st.pos.col, fileName, "trùng tên kiểu dữ liệu '${st.name}'"))
      }
      registerStruct(st, structs)
    }

    // Collect imported library functions/voids
    val shortNameOrigins = LinkedHashMap<String, MutableList<String>>()
    for (imp in program.imports) {
      val ext = externalPrograms[imp.name] ?: continue
      val modFns = LinkedHashMap<String, FnSignature>()
      val modVoids = LinkedHashMap<String, VoidSignature>()
      for (fn in ext.functions) {
        val sig = buildFnSig(fn, structs)
        modFns[fn.name] = sig
        shortNameOrigins.getOrPut(fn.name) { mutableListOf() }.add(imp.name)
      }
      for (vd in ext.voids) {
        val sig = buildVoidSig(vd, structs)
        modVoids[vd.name] = sig
        shortNameOrigins.getOrPut(vd.name) { mutableListOf() }.add(imp.name)
      }
      importedModulesFns[imp.name] = modFns
      importedModulesVoids[imp.name] = modVoids
    }

    // Register local functions & voids
    for (fn in program.functions) {
      if (functions.containsKey(fn.name) || voids.containsKey(fn.name)) {
        throw SdCompileException(SdDiagnostic("E302", 3, fn.pos.line, fn.pos.col, fileName, "trùng tên hàm '${fn.name}'"))
      }
      functions[fn.name] = buildFnSig(fn, structs)
    }
    for (vd in program.voids) {
      if (functions.containsKey(vd.name) || voids.containsKey(vd.name)) {
        throw SdCompileException(SdDiagnostic("E302", 3, vd.pos.line, vd.pos.col, fileName, "trùng tên thủ tục '${vd.name}'"))
      }
      voids[vd.name] = buildVoidSig(vd, structs)
    }

    val ctx = AnalysisCtx(
      program = program,
      importedLibs = importedLibs,
      structs = structs,
      functions = functions,
      voids = voids,
      importedFns = importedModulesFns,
      importedVoids = importedModulesVoids,
      shortNameOrigins = shortNameOrigins,
      globalScope = globalScope,
      loadedRegs = loadedRegs,
      inAaC = program.aacMilestones.isNotEmpty(),
    )

    // Register globals
    for (g in program.globals) {
      val gType = when {
        g.type != null -> g.type
        g.initExpr != null -> inferExprType(g.initExpr, globalScope, ctx)
        else -> SdType.IntType
      }
      globalScope.declare(VarSymbol(g.name, gType, g.pos, isBorrowed = g.isBorrowed, used = false))
    }

    // Analyze functions
    for (fn in program.functions) {
      val sig = functions[fn.name]!!
      val fnScope = Scope(globalScope)
      for ((pName, pType) in sig.params) {
        fnScope.declare(VarSymbol(pName, pType, fn.pos, used = false))
      }
      analyzeStatements(
        stmts = fn.body,
        scope = fnScope,
        ctx = ctx.copy(inLoop = false, inSb = false, expectedReturnType = sig.returnType),
      )
      warnUnusedVars(fnScope)
    }

    // Analyze voids
    for (vd in program.voids) {
      val sig = voids[vd.name]!!
      val vdScope = Scope(globalScope)
      for ((pName, pType) in sig.params) {
        vdScope.declare(VarSymbol(pName, pType, vd.pos, used = false))
      }
      analyzeStatements(
        stmts = vd.body,
        scope = vdScope,
        ctx = ctx.copy(inLoop = false, inSb = false, expectedReturnType = SdType.VoidType),
      )
      warnUnusedVars(vdScope)
    }

    // Analyze main
    if (isMainUnit) {
      val mainScope = Scope(globalScope)
      analyzeStatements(
        stmts = program.mainBody,
        scope = mainScope,
        ctx = ctx.copy(inLoop = false, inSb = false, expectedReturnType = SdType.IntType),
      )
      warnUnusedVars(mainScope)
    }

    // Check W301 unused globals & local functions (only for standalone program)
    if (sharedGlobals == null && isMainUnit) {
      warnUnusedVars(globalScope)
      for ((_, f) in functions) {
        if (!f.used && !f.name.startsWith("_")) {
          warnings.add(
            SdDiagnostic("W301", 3, f.pos.line, f.pos.col, fileName, "hàm '${f.name}' được khai báo nhưng chưa sử dụng", severity = DiagnosticSeverity.WARNING)
          )
        }
      }
      for ((_, v) in voids) {
        if (!v.used && !v.name.startsWith("_")) {
          warnings.add(
            SdDiagnostic("W301", 3, v.pos.line, v.pos.col, fileName, "thủ tục '${v.name}' được khai báo nhưng chưa sử dụng", severity = DiagnosticSeverity.WARNING)
          )
        }
      }
    }

    // Check W302: sta.alloc without sta.free
    if (ctx.hasAlloc && !ctx.hasFree) {
      warnings.add(
        SdDiagnostic("W302", 3, program.mainPos.line, program.mainPos.col, fileName, "có gọi sta.alloc nhưng chưa thấy gọi sta.free tương ứng (nguy cơ rò rỉ bộ nhớ Heap)", severity = DiagnosticSeverity.WARNING)
      )
    }
  }

  private fun warnUnusedVars(scope: Scope) {
    for ((_, sym) in scope.vars) {
      if (!sym.used && !sym.name.startsWith("_")) {
        warnings.add(
          SdDiagnostic("W301", 3, sym.pos.line, sym.pos.col, sym.pos.fileName, "biến '${sym.name}' được khai báo nhưng chưa sử dụng", severity = DiagnosticSeverity.WARNING)
        )
      }
    }
  }

  private fun registerStruct(st: StructDecl, structs: MutableMap<String, SdType.StructType>) {
    val fieldSet = HashSet<String>()
    val fields = mutableListOf<StructField>()
    for (f in st.fields) {
      if (!fieldSet.add(f.name)) {
        throw SdCompileException(SdDiagnostic("E302", 3, f.pos.line, f.pos.col, fileName, "trùng tên trường '${f.name}' trong struct '${st.name}'"))
      }
      val t = resolveTypeName(f.typeName, null, null, structs, f.pos)
      fields.add(StructField(f.name, t))
    }
    structs[st.name] = SdType.StructType(st.name, fields)
  }

  private fun buildFnSig(fn: FnDecl, structs: Map<String, SdType.StructType>): FnSignature {
    val pSet = HashSet<String>()
    val params = fn.params.map { p ->
      if (!pSet.add(p.name)) {
        throw SdCompileException(SdDiagnostic("E302", 3, p.pos.line, p.pos.col, fileName, "trùng tên tham số '${p.name}' trong hàm '${fn.name}'"))
      }
      p.name to resolveTypeName(p.typeName, p.ptrElemTypeName, p.arraySize, structs, p.pos)
    }
    val ret = resolveTypeName(fn.returnTypeName, fn.returnPtrElemTypeName, null, structs, fn.pos)
    return FnSignature(fn.name, params, ret, fn.pos)
  }

  private fun buildVoidSig(vd: VoidDecl, structs: Map<String, SdType.StructType>): VoidSignature {
    val pSet = HashSet<String>()
    val params = vd.params.map { p ->
      if (!pSet.add(p.name)) {
        throw SdCompileException(SdDiagnostic("E302", 3, p.pos.line, p.pos.col, fileName, "trùng tên tham số '${p.name}' trong thủ tục '${vd.name}'"))
      }
      p.name to resolveTypeName(p.typeName, p.ptrElemTypeName, p.arraySize, structs, p.pos)
    }
    return VoidSignature(vd.name, params, vd.pos)
  }

  private fun resolveTypeName(
    typeName: String,
    ptrElem: String?,
    arrSize: Int?,
    structs: Map<String, SdType.StructType>,
    pos: SourcePos,
  ): SdType {
    val base: SdType = when (typeName) {
      "int" -> SdType.IntType
      "float" -> SdType.FloatType
      "str" -> SdType.StrType
      "bool" -> SdType.BoolType
      "void" -> SdType.VoidType
      "ptr" -> {
        val inner = if (ptrElem != null) resolveTypeName(ptrElem, null, null, structs, pos) else null
        SdType.PtrType(inner)
      }
      else -> structs[typeName]
        ?: throw SdCompileException(SdDiagnostic("E301", 3, pos.line, pos.col, fileName, "kiểu dữ liệu chưa khai báo: '$typeName'"))
    }
    return if (arrSize != null) SdType.ArrayType(base, arrSize) else base
  }

  private data class AnalysisCtx(
    val program: ProgramNode,
    val importedLibs: Set<String>,
    val structs: Map<String, SdType.StructType>,
    val functions: Map<String, FnSignature>,
    val voids: Map<String, VoidSignature>,
    val importedFns: Map<String, Map<String, FnSignature>>,
    val importedVoids: Map<String, Map<String, VoidSignature>>,
    val shortNameOrigins: Map<String, List<String>>,
    val globalScope: Scope,
    val loadedRegs: MutableMap<String, SourcePos>,
    var inAaC: Boolean = false,
    val inLoop: Boolean = false,
    val inSb: Boolean = false,
    val expectedReturnType: SdType = SdType.IntType,
    var hasAlloc: Boolean = false,
    var hasFree: Boolean = false,
  )

  private fun analyzeStatements(stmts: List<Stmt>, scope: Scope, ctx: AnalysisCtx) {
    for (s in stmts) {
      analyzeStmt(s, scope, ctx)
    }
  }

  private fun analyzeStmt(stmt: Stmt, scope: Scope, ctx: AnalysisCtx) {
    when (stmt) {
      is Stmt.VarDeclStmt -> {
        val varType = resolveTypeName(stmt.typeName, stmt.ptrElemTypeName, stmt.arraySize, ctx.structs, stmt.pos)
        var finalType = varType
        if (stmt.initExpr != null) {
          val rhsType = inferExprType(stmt.initExpr, scope, ctx)
          if (stmt.isBorrowed) {
            finalType = rhsType
          } else if (!SdTypeUtils.isAssignable(varType, rhsType)) {
            throw SdCompileException(SdDiagnostic("E303", 3, stmt.pos.line, stmt.pos.col, fileName, "không thể gán kiểu '$rhsType' cho biến '${stmt.name}' kiểu '$varType'"))
          }
        }
        scope.declare(VarSymbol(stmt.name, finalType, stmt.pos, isBorrowed = stmt.isBorrowed, used = false))
      }

      is Stmt.AssignStmt -> {
        val rhsType = inferExprType(stmt.value, scope, ctx)
        if (stmt.target is Expr.VarExpr) {
          val existing = scope.resolve(stmt.target.name)
          if (existing == null) {
            if (stmt.allowImplicitDecl || ctx.inAaC) {
              // Mirror (*m) or AaC infers variable type on first assignment
              scope.declare(VarSymbol(stmt.target.name, rhsType, stmt.pos, used = false))
              return
            }
            throw SdCompileException(SdDiagnostic("E301", 3, stmt.pos.line, stmt.pos.col, fileName, "biến '${stmt.target.name}' chưa được khai báo"))
          }
          existing.used = true
          if (!SdTypeUtils.isAssignable(existing.type, rhsType)) {
            throw SdCompileException(SdDiagnostic("E303", 3, stmt.pos.line, stmt.pos.col, fileName, "không thể gán giá trị kiểu '$rhsType' cho biến '${stmt.target.name}' kiểu '${existing.type}'"))
          }
        } else {
          val lhsType = inferExprType(stmt.target, scope, ctx)
          if (!SdTypeUtils.isAssignable(lhsType, rhsType)) {
            throw SdCompileException(SdDiagnostic("E303", 3, stmt.pos.line, stmt.pos.col, fileName, "sai kiểu khi gán '$rhsType' vào '$lhsType'"))
          }
        }
      }

      is Stmt.IncDecStmt -> {
        val t = inferExprType(stmt.target, scope, ctx)
        if (t != SdType.IntType && t != SdType.FloatType && t !is SdType.PtrType && t != SdType.UnknownType) {
          throw SdCompileException(SdDiagnostic("E303", 3, stmt.pos.line, stmt.pos.col, fileName, "phép ++/-- chỉ áp dụng cho số hoặc con trỏ, không dùng với '$t'"))
        }
      }

      is Stmt.PriStmt -> {
        if ("ioprt" !in ctx.importedLibs) {
          throw SdCompileException(SdDiagnostic("E205", 3, stmt.pos.line, stmt.pos.col, fileName, "chưa imp*ioprt trước khi dùng lệnh in (pri / ids.pri)", hint = "thêm imp*ioprt.ed hoặc imp*ioprt.$ ở đầu"))
        }
        inferExprType(stmt.expr, scope, ctx)
      }

      is Stmt.IptStmt -> {
        if ("ioprt" !in ctx.importedLibs) {
          throw SdCompileException(SdDiagnostic("E205", 3, stmt.pos.line, stmt.pos.col, fileName, "chưa imp*ioprt trước khi dùng lệnh nhập ipt", hint = "thêm imp*ioprt.ed hoặc imp*ioprt.$ ở đầu"))
        }
        inferExprType(stmt.target, scope, ctx)
      }

      is Stmt.CallStmt -> {
        inferCallType(stmt.call, scope, ctx, allowVoid = true)
      }

      is Stmt.RefStmt -> {
        if (ctx.expectedReturnType == SdType.VoidType) {
          throw SdCompileException(SdDiagnostic("E305", 3, stmt.pos.line, stmt.pos.col, fileName, "không được dùng ref* có giá trị trong thủ tục void"))
        }
        val valType = inferExprType(stmt.value, scope, ctx)
        if (!SdTypeUtils.isAssignable(ctx.expectedReturnType, valType)) {
          throw SdCompileException(SdDiagnostic("E303", 3, stmt.pos.line, stmt.pos.col, fileName, "giá trị ref* kiểu '$valType' không khớp kiểu trả về '${ctx.expectedReturnType}'"))
        }
      }

      is Stmt.FixedAddrSimStmt -> {
        scope.declare(VarSymbol(stmt.name, SdType.PtrType(null), stmt.pos, used = false))
      }

      is Stmt.BrkStmt, is Stmt.NxtStmt -> {
        if (!ctx.inLoop) {
          throw SdCompileException(SdDiagnostic("E305", 3, stmt.pos.line, stmt.pos.col, fileName, "lệnh brk/nxt chỉ được đặt bên trong vòng lặp for hoặc while"))
        }
      }

      is Stmt.BoleanStmt -> {
        var sawElse = false
        for (i in stmt.branches.indices) {
          val b = stmt.branches[i]
          if (b.condition == null) {
            if (i != stmt.branches.lastIndex) {
              throw SdCompileException(SdDiagnostic("E306", 3, b.pos.line, b.pos.col, fileName, "nhánh sta(else) bắt buộc phải đặt ở cuối cùng"))
            }
            sawElse = true
          } else {
            val cType = inferExprType(b.condition, scope, ctx)
            if (cType != SdType.BoolType && cType != SdType.UnknownType) {
              throw SdCompileException(SdDiagnostic("E303", 3, b.pos.line, b.pos.col, fileName, "điều kiện bolean phải có kiểu bool, gặp '$cType'"))
            }
          }
          val branchScope = Scope(scope)
          analyzeStatements(b.body, branchScope, ctx)
        }
      }

      is Stmt.ForStmt -> {
        val loopScope = Scope(scope)
        val initValType = inferExprType(stmt.initExpr, scope, ctx)
        if (stmt.initVarType != null) {
          val declType = resolveTypeName(stmt.initVarType, null, null, ctx.structs, stmt.pos)
          if (!SdTypeUtils.isAssignable(declType, initValType)) {
            throw SdCompileException(SdDiagnostic("E303", 3, stmt.pos.line, stmt.pos.col, fileName, "kiểu khởi tạo vòng for không khớp"))
          }
          loopScope.declare(VarSymbol(stmt.initVarName, declType, stmt.pos, used = true))
        } else {
          val existing = loopScope.resolve(stmt.initVarName)
          if (existing == null) {
            if (ctx.inAaC) {
              loopScope.declare(VarSymbol(stmt.initVarName, initValType, stmt.pos, used = true))
            } else {
              throw SdCompileException(SdDiagnostic("E301", 3, stmt.pos.line, stmt.pos.col, fileName, "biến vòng for '${stmt.initVarName}' chưa khai báo"))
            }
          } else {
            existing.used = true
          }
        }
        val condType = inferExprType(stmt.condition, loopScope, ctx)
        if (condType != SdType.BoolType && condType != SdType.UnknownType) {
          throw SdCompileException(SdDiagnostic("E303", 3, stmt.pos.line, stmt.pos.col, fileName, "điều kiện vòng for phải là bool, gặp '$condType'"))
        }
        analyzeStmt(stmt.stepStmt, loopScope, ctx.copy(inLoop = true))
        analyzeStatements(stmt.body, loopScope, ctx.copy(inLoop = true))
        warnUnusedVars(loopScope)
      }

      is Stmt.WhileStmt -> {
        val condType = inferExprType(stmt.condition, scope, ctx)
        if (condType != SdType.BoolType && condType != SdType.UnknownType) {
          throw SdCompileException(SdDiagnostic("E303", 3, stmt.pos.line, stmt.pos.col, fileName, "điều kiện vòng while phải là bool, gặp '$condType'"))
        }
        // W201 check: are any condition variables modified in body?
        val condVars = collectReadVars(stmt.condition)
        val modifiedVars = collectModifiedVars(stmt.body)
        if (condVars.isNotEmpty() && condVars.none { it in modifiedVars } && !containsBrk(stmt.body)) {
          warnings.add(
            SdDiagnostic("W201", 3, stmt.pos.line, stmt.pos.col, fileName, "thân vòng while không sửa biến điều kiện nào (nguy cơ lặp vô hạn)", severity = DiagnosticSeverity.WARNING)
          )
        }
        val loopScope = Scope(scope)
        analyzeStatements(stmt.body, loopScope, ctx.copy(inLoop = true))
        warnUnusedVars(loopScope)
      }

      is Stmt.SbStmt -> {
        val targetSym = scope.resolve(stmt.targetVar)
          ?: throw SdCompileException(SdDiagnostic("E301", 3, stmt.pos.line, stmt.pos.col, fileName, "biến '${stmt.targetVar}' trong ids sb chưa được khai báo"))
        targetSym.used = true
        for (b in stmt.branches) {
          if (b.condition != null) {
            val cType = inferExprType(b.condition, scope, ctx)
            if (cType != SdType.BoolType && cType != SdType.UnknownType) {
              throw SdCompileException(SdDiagnostic("E303", 3, b.pos.line, b.pos.col, fileName, "điều kiện nhánh block trong ids sb phải là bool"))
            }
          }
          if (b.action == Stmt.SbAction.REBACK) {
            val modified = collectModifiedVars(b.body)
            if (stmt.targetVar !in modified) {
              warnings.add(
                SdDiagnostic("W203", 3, b.pos.line, b.pos.col, fileName, "nhánh Reback $$ không thấy sửa trực tiếp biến '${stmt.targetVar}' của ids sb", severity = DiagnosticSeverity.WARNING)
              )
            }
          }
          val bScope = Scope(scope)
          analyzeStatements(b.body, bScope, ctx.copy(inSb = true))
        }
      }

      is Stmt.FreeStmt -> {
        if ("mem" !in ctx.importedLibs) {
          throw SdCompileException(SdDiagnostic("E205", 3, stmt.pos.line, stmt.pos.col, fileName, "chưa imp*mem.$ trước khi gọi sta.free"))
        }
        ctx.hasFree = true
        val pType = inferExprType(stmt.ptrExpr, scope, ctx)
        if (pType !is SdType.PtrType && pType != SdType.UnknownType) {
          throw SdCompileException(SdDiagnostic("E303", 3, stmt.pos.line, stmt.pos.col, fileName, "sta.free cần đối số kiểu con trỏ ptr, gặp '$pType'"))
        }
      }

      is Stmt.RegLoadStmt -> {
        inferExprType(stmt.valueExpr, scope, ctx)
        ctx.loadedRegs[stmt.regName] = stmt.pos
      }

      is Stmt.RegPushNullifyStmt -> {
        for (r in stmt.registers) {
          ctx.loadedRegs.remove(r)
        }
        if (stmt.destVarName != null && stmt.destVarType != null) {
          val t = resolveTypeName(stmt.destVarType, null, null, ctx.structs, stmt.pos)
          scope.declare(VarSymbol(stmt.destVarName, t, stmt.pos, used = false))
        }
      }

      is Stmt.AaCEnterStmt -> {
        ctx.inAaC = true
      }

      is Stmt.BlockScopeStmt -> {
        analyzeStatements(stmt.body, scope, ctx)
      }

      is Stmt.HardHaltStmt -> {
        // Hard Halt outside ids sb will trigger E409 at runtime when executed, so allow compiling cleanly.
      }

      is Stmt.SbFlushStmt, is Stmt.InitFlagStmt -> {}
    }
  }

  private fun inferExprType(expr: Expr, scope: Scope, ctx: AnalysisCtx): SdType {
    return when (expr) {
      is Expr.IntLit -> SdType.IntType
      is Expr.FloatLit -> SdType.FloatType
      is Expr.StrLit -> SdType.StrType
      is Expr.BoolLit -> SdType.BoolType
      is Expr.ArrayLit -> {
        if (expr.elements.isEmpty()) SdType.ArrayType(SdType.IntType, 0)
        else {
          val firstT = inferExprType(expr.elements.first(), scope, ctx)
          expr.elements.drop(1).forEach { inferExprType(it, scope, ctx) }
          SdType.ArrayType(firstT, expr.elements.size)
        }
      }
      is Expr.VarExpr -> {
        val sym = scope.resolve(expr.name)
          ?: throw SdCompileException(SdDiagnostic("E301", 3, expr.pos.line, expr.pos.col, fileName, "biến '${expr.name}' chưa được khai báo"))
        sym.used = true
        sym.type
      }
      is Expr.GlobalVarExpr -> {
        val sym = ctx.globalScope.resolve(expr.name)
          ?: throw SdCompileException(SdDiagnostic("E301", 3, expr.pos.line, expr.pos.col, fileName, "biến toàn cục '${expr.name}' chưa được khai báo"))
        sym.used = true
        sym.type
      }
      is Expr.RegExpr -> SdType.UnknownType
      is Expr.MemberExpr -> {
        val targetT = inferExprType(expr.target, scope, ctx)
        when (targetT) {
          is SdType.StructType -> {
            targetT.fields.firstOrNull { it.name == expr.field }?.type
              ?: throw SdCompileException(SdDiagnostic("E301", 3, expr.pos.line, expr.pos.col, fileName, "struct '${targetT.name}' không có trường '${expr.field}'"))
          }
          is SdType.UnknownType -> SdType.UnknownType
          else -> throw SdCompileException(SdDiagnostic("E303", 3, expr.pos.line, expr.pos.col, fileName, "không thể truy cập trường '.${expr.field}' trên kiểu '$targetT'"))
        }
      }
      is Expr.IndexExpr -> {
        val arrT = inferExprType(expr.target, scope, ctx)
        val idxT = inferExprType(expr.index, scope, ctx)
        if (idxT != SdType.IntType && idxT != SdType.UnknownType) {
          throw SdCompileException(SdDiagnostic("E303", 3, expr.pos.line, expr.pos.col, fileName, "chỉ số mảng phải là int, gặp '$idxT'"))
        }
        when (arrT) {
          is SdType.ArrayType -> arrT.elemType
          is SdType.PtrType -> arrT.elemType ?: SdType.UnknownType
          SdType.UnknownType -> SdType.UnknownType
          else -> throw SdCompileException(SdDiagnostic("E303", 3, expr.pos.line, expr.pos.col, fileName, "chỉ có thể lấy chỉ số [] trên mảng hoặc con trỏ, gặp '$arrT'"))
        }
      }
      is Expr.AddrOfExpr -> {
        val innerT = inferExprType(expr.target, scope, ctx)
        SdType.PtrType(innerT)
      }
      is Expr.DerefExpr -> {
        val ptrT = inferExprType(expr.ptrExpr, scope, ctx)
        when (ptrT) {
          is SdType.PtrType -> ptrT.elemType ?: SdType.UnknownType
          SdType.UnknownType -> SdType.UnknownType
          else -> throw SdCompileException(SdDiagnostic("E303", 3, expr.pos.line, expr.pos.col, fileName, "toán tử '*' chỉ dùng với con trỏ, gặp '$ptrT'"))
        }
      }
      is Expr.BorrowDerefExpr -> {
        val sym = scope.resolve(expr.varName)
          ?: throw SdCompileException(SdDiagnostic("E301", 3, expr.pos.line, expr.pos.col, fileName, "biến '${expr.varName}' chưa được khai báo"))
        sym.used = true
        if (!sym.isBorrowed) {
          throw SdCompileException(SdDiagnostic("E218", 3, expr.pos.line, expr.pos.col, fileName, "toán tử *m(${expr.varName}) chỉ dùng với biến tham chiếu mượn khai báo bằng ':&'"))
        }
        sym.type
      }
      is Expr.UnaryExpr -> {
        val opT = inferExprType(expr.operand, scope, ctx)
        when (expr.op) {
          "not" -> {
            if (opT != SdType.BoolType && opT != SdType.UnknownType) {
              throw SdCompileException(SdDiagnostic("E303", 3, expr.pos.line, expr.pos.col, fileName, "'not' cần kiểu bool, gặp '$opT'"))
            }
            SdType.BoolType
          }
          "-" -> {
            if (opT != SdType.IntType && opT != SdType.FloatType && opT != SdType.UnknownType) {
              throw SdCompileException(SdDiagnostic("E303", 3, expr.pos.line, expr.pos.col, fileName, "toán tử '-' cần kiểu số, gặp '$opT'"))
            }
            opT
          }
          else -> opT
        }
      }
      is Expr.BinaryExpr -> {
        val lt = inferExprType(expr.left, scope, ctx)
        val rt = inferExprType(expr.right, scope, ctx)
        when (expr.op) {
          "and", "or" -> {
            if ((lt != SdType.BoolType && lt != SdType.UnknownType) || (rt != SdType.BoolType && rt != SdType.UnknownType)) {
              throw SdCompileException(SdDiagnostic("E303", 3, expr.pos.line, expr.pos.col, fileName, "phép logic '${expr.op}' chỉ dùng cho bool"))
            }
            SdType.BoolType
          }
          "==", "!=" -> SdType.BoolType
          "<", "<=", ">", ">=" -> SdType.BoolType
          "+" -> when {
            lt is SdType.PtrType && rt == SdType.IntType -> lt
            lt == SdType.StrType && rt == SdType.StrType -> SdType.StrType
            lt == SdType.FloatType || rt == SdType.FloatType -> SdType.FloatType
            else -> SdType.IntType
          }
          "-", "*", "/" -> when {
            lt is SdType.PtrType && rt == SdType.IntType && expr.op == "-" -> lt
            lt == SdType.FloatType || rt == SdType.FloatType -> SdType.FloatType
            else -> SdType.IntType
          }
          "%" -> {
            if ((lt != SdType.IntType && lt != SdType.UnknownType) || (rt != SdType.IntType && rt != SdType.UnknownType)) {
              throw SdCompileException(SdDiagnostic("E303", 3, expr.pos.line, expr.pos.col, fileName, "phép chia dư '%' chỉ áp dụng cho kiểu int"))
            }
            SdType.IntType
          }
          else -> SdType.UnknownType
        }
      }
      is Expr.CallExpr -> inferCallType(expr, scope, ctx, allowVoid = false)
    }
  }

  private fun inferCallType(
    call: Expr.CallExpr,
    scope: Scope,
    ctx: AnalysisCtx,
    allowVoid: Boolean,
  ): SdType {
    call.args.forEach { inferExprType(it, scope, ctx) }
    val mod = call.module
    if (mod != null) {
      if (mod == "sta" && call.name == "alloc") {
        if ("mem" !in ctx.importedLibs) {
          throw SdCompileException(SdDiagnostic("E205", 3, call.pos.line, call.pos.col, fileName, "chưa imp*mem.$ trước khi gọi sta.alloc"))
        }
        ctx.hasAlloc = true
        checkArgCount(call, 1)
        return SdType.PtrType(null)
      }
      if (mod in SdParser.STD_LIBS) {
        if (mod !in ctx.importedLibs) {
          throw SdCompileException(SdDiagnostic("E205", 3, call.pos.line, call.pos.col, fileName, "chưa imp*$mod trước khi gọi $mod.${call.name}"))
        }
        return inferStdLibCall(mod, call)
      }
      // Imported user module
      val fnSig = ctx.importedFns[mod]?.get(call.name)
      if (fnSig != null) {
        fnSig.used = true
        checkCallArgs(call, fnSig.params, scope, ctx)
        return fnSig.returnType
      }
      val vdSig = ctx.importedVoids[mod]?.get(call.name)
      if (vdSig != null) {
        if (!allowVoid) {
          throw SdCompileException(SdDiagnostic("E303", 3, call.pos.line, call.pos.col, fileName, "thủ tục void '${mod}.${call.name}' không trả về giá trị"))
        }
        vdSig.used = true
        checkCallArgs(call, vdSig.params, scope, ctx)
        return SdType.VoidType
      }
      throw SdCompileException(SdDiagnostic("E301", 3, call.pos.line, call.pos.col, fileName, "không tìm thấy hàm '${call.name}' trong '$mod'"))
    }

    // Local fn or void
    val localFn = ctx.functions[call.name]
    if (localFn != null) {
      localFn.used = true
      checkCallArgs(call, localFn.params, scope, ctx)
      return localFn.returnType
    }
    val localVoid = ctx.voids[call.name]
    if (localVoid != null) {
      if (!allowVoid) {
        throw SdCompileException(SdDiagnostic("E303", 3, call.pos.line, call.pos.col, fileName, "thủ tục void '${call.name}' không trả về giá trị"))
      }
      localVoid.used = true
      checkCallArgs(call, localVoid.params, scope, ctx)
      return SdType.VoidType
    }

    // Short name from single imported user module?
    val origins = ctx.shortNameOrigins[call.name]
    if (!origins.isNullOrEmpty()) {
      if (origins.size > 1) {
        throw SdCompileException(SdDiagnostic("E302", 3, call.pos.line, call.pos.col, fileName, "tên hàm '${call.name}' trùng ở nhiều tệp (${origins.joinToString(", ")}); hãy gọi dạng ten_tep.${call.name}()"))
      }
      val originMod = origins[0]
      val fnSig = ctx.importedFns[originMod]?.get(call.name)
      if (fnSig != null) {
        checkCallArgs(call, fnSig.params, scope, ctx)
        return fnSig.returnType
      }
      val vdSig = ctx.importedVoids[originMod]?.get(call.name)
      if (vdSig != null) {
        if (!allowVoid) throw SdCompileException(SdDiagnostic("E303", 3, call.pos.line, call.pos.col, fileName, "thủ tục void không trả về giá trị"))
        checkCallArgs(call, vdSig.params, scope, ctx)
        return SdType.VoidType
      }
    }

    throw SdCompileException(SdDiagnostic("E301", 3, call.pos.line, call.pos.col, fileName, "hàm hoặc thủ tục '${call.name}' chưa được khai báo"))
  }

  private fun checkCallArgs(
    call: Expr.CallExpr,
    expected: List<Pair<String, SdType>>,
    scope: Scope,
    ctx: AnalysisCtx,
  ) {
    if (call.args.size != expected.size) {
      throw SdCompileException(
        SdDiagnostic("E304", 3, call.pos.line, call.pos.col, fileName, "hàm '${call.name}' cần ${expected.size} đối số, nhưng nhận ${call.args.size}")
      )
    }
    for (i in call.args.indices) {
      val actualT = inferExprType(call.args[i], scope, ctx)
      val expT = expected[i].second
      if (!SdTypeUtils.isAssignable(expT, actualT)) {
        throw SdCompileException(
          SdDiagnostic("E303", 3, call.args[i].pos.line, call.args[i].pos.col, fileName, "đối số #${i + 1} của '${call.name}' cần '$expT', gặp '$actualT'")
        )
      }
    }
  }

  private fun checkArgCount(call: Expr.CallExpr, expectedCount: Int) {
    if (call.args.size != expectedCount) {
      throw SdCompileException(
        SdDiagnostic("E304", 3, call.pos.line, call.pos.col, fileName, "${call.module ?: ""}.${call.name} cần $expectedCount đối số, gặp ${call.args.size}")
      )
    }
  }

  private fun inferStdLibCall(mod: String, call: Expr.CallExpr): SdType {
    return when (mod) {
      "math" -> when (call.name) {
        "abs", "max", "min" -> { checkArgCount(call, if (call.name == "abs") 1 else 2); SdType.UnknownType }
        "pow", "sqrt" -> { checkArgCount(call, if (call.name == "sqrt") 1 else 2); SdType.FloatType }
        else -> throw SdCompileException(SdDiagnostic("E301", 3, call.pos.line, call.pos.col, fileName, "thư viện math không có hàm '${call.name}'"))
      }
      "str" -> when (call.name) {
        "len" -> { checkArgCount(call, 1); SdType.IntType }
        "upper", "lower" -> { checkArgCount(call, 1); SdType.StrType }
        "concat" -> { checkArgCount(call, 2); SdType.StrType }
        else -> throw SdCompileException(SdDiagnostic("E301", 3, call.pos.line, call.pos.col, fileName, "thư viện str không có hàm '${call.name}'"))
      }
      "mem" -> when (call.name) {
        "memset" -> { checkArgCount(call, 3); SdType.VoidType }
        "alloc" -> { checkArgCount(call, 1); SdType.PtrType(null) }
        "free" -> { checkArgCount(call, 1); SdType.VoidType }
        else -> throw SdCompileException(SdDiagnostic("E301", 3, call.pos.line, call.pos.col, fileName, "thư viện mem không có hàm '${call.name}'"))
      }
      "sys" -> when (call.name) {
        "time" -> { checkArgCount(call, 0); SdType.IntType }
        "gc" -> { checkArgCount(call, 0); SdType.IntType }
        "sleep" -> { checkArgCount(call, 1); SdType.VoidType }
        else -> throw SdCompileException(SdDiagnostic("E301", 3, call.pos.line, call.pos.col, fileName, "thư viện sys không có hàm '${call.name}'"))
      }
      "file" -> when (call.name) {
        "write" -> { checkArgCount(call, 2); SdType.VoidType }
        "read" -> { checkArgCount(call, 1); SdType.StrType }
        else -> throw SdCompileException(SdDiagnostic("E301", 3, call.pos.line, call.pos.col, fileName, "thư viện file không có hàm '${call.name}'"))
      }
      "draw" -> when (call.name) {
        "rect" -> { checkArgCount(call, 4); SdType.VoidType }
        else -> throw SdCompileException(SdDiagnostic("E301", 3, call.pos.line, call.pos.col, fileName, "thư viện draw không có hàm '${call.name}'"))
      }
      else -> SdType.UnknownType
    }
  }

  private fun collectReadVars(expr: Expr): Set<String> {
    val out = HashSet<String>()
    fun walk(e: Expr) {
      when (e) {
        is Expr.VarExpr -> out.add(e.name)
        is Expr.GlobalVarExpr -> out.add(e.name)
        is Expr.MemberExpr -> walk(e.target)
        is Expr.IndexExpr -> { walk(e.target); walk(e.index) }
        is Expr.AddrOfExpr -> walk(e.target)
        is Expr.DerefExpr -> walk(e.ptrExpr)
        is Expr.BorrowDerefExpr -> out.add(e.varName)
        is Expr.UnaryExpr -> walk(e.operand)
        is Expr.BinaryExpr -> { walk(e.left); walk(e.right) }
        is Expr.CallExpr -> e.args.forEach(::walk)
        is Expr.ArrayLit -> e.elements.forEach(::walk)
        else -> {}
      }
    }
    walk(expr)
    return out
  }

  private fun collectModifiedVars(stmts: List<Stmt>): Set<String> {
    val out = HashSet<String>()
    fun rootVar(e: Expr): String? = when (e) {
      is Expr.VarExpr -> e.name
      is Expr.GlobalVarExpr -> e.name
      is Expr.MemberExpr -> rootVar(e.target)
      is Expr.IndexExpr -> rootVar(e.target)
      is Expr.BorrowDerefExpr -> e.varName
      is Expr.DerefExpr -> rootVar(e.ptrExpr)
      else -> null
    }
    fun walk(list: List<Stmt>) {
      for (s in list) {
        when (s) {
          is Stmt.AssignStmt -> rootVar(s.target)?.let { out.add(it) }
          is Stmt.IncDecStmt -> rootVar(s.target)?.let { out.add(it) }
          is Stmt.IptStmt -> rootVar(s.target)?.let { out.add(it) }
          is Stmt.BoleanStmt -> s.branches.forEach { walk(it.body) }
          is Stmt.ForStmt -> { out.add(s.initVarName); walk(s.body) }
          is Stmt.WhileStmt -> walk(s.body)
          is Stmt.SbStmt -> s.branches.forEach { walk(it.body) }
          is Stmt.BlockScopeStmt -> walk(s.body)
          else -> {}
        }
      }
    }
    walk(stmts)
    return out
  }

  private fun containsBrk(stmts: List<Stmt>): Boolean {
    for (s in stmts) {
      if (s is Stmt.BrkStmt) return true
      if (s is Stmt.BoleanStmt && s.branches.any { containsBrk(it.body) }) return true
    }
    return false
  }
}
