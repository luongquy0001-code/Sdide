package com.example.sdide.engine

import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

enum class DebugCommand {
  STEP_INTO,
  STEP_OVER,
  CONTINUE,
  STOP,
}

data class VarSnapshot(
  val name: String,
  val type: String,
  val value: String,
  val isBorrowed: Boolean = false,
)

data class CallFrameInfo(
  val name: String,
  val line: Int,
  val locals: List<VarSnapshot>,
)

data class HeapBlockSnapshot(
  val blockId: Int,
  val addressHex: String,
  val sizeSlots: Int,
  val freed: Boolean,
  val preview: String,
)

data class RegisterSnapshot(
  val name: String,
  val value: String,
  val active: Boolean,
  val managedByAaC: Boolean,
)

data class DrawRectCmd(
  val x: Int,
  val y: Int,
  val width: Int,
  val height: Int,
)

data class DebugSnapshot(
  val line: Int,
  val fileName: String?,
  val callStack: List<CallFrameInfo>,
  val globals: List<VarSnapshot>,
  val heapBlocks: List<HeapBlockSnapshot>,
  val registers: List<RegisterSnapshot>,
)

interface DebugSessionListener {
  fun onPaused(snapshot: DebugSnapshot): DebugCommand
  fun onOutputChunk(text: String) {}
}

interface WorkspaceFileBridge {
  fun writeFile(name: String, content: String)
  fun readFile(name: String): String?
}

class InMemoryFileBridge : WorkspaceFileBridge {
  private val files = LinkedHashMap<String, String>()
  override fun writeFile(name: String, content: String) {
    files[name] = content
  }
  override fun readFile(name: String): String? = files[name]
}

interface StdinProvider {
  fun readNextLine(promptHint: String): String?
}

class QueueStdinProvider(lines: List<String>) : StdinProvider {
  private val queue = ArrayDeque(lines)
  override fun readNextLine(promptHint: String): String? =
    if (queue.isEmpty()) null else queue.removeFirst()
}

class DebugController(
  initialStepMode: Boolean = false,
  breakpoints: Set<Int> = emptySet(),
  private val onPausedCallback: ((DebugSnapshot) -> Unit)? = null,
  private val onOutputCallback: ((String) -> Unit)? = null,
) : DebugSessionListener {
  @Volatile var stepMode: Boolean = initialStepMode
  @Volatile var stepOverDepth: Int? = null
  @Volatile var cancelled: Boolean = false
  @Volatile var breakpoints: Set<Int> = breakpoints
  private val commandQueue = LinkedBlockingQueue<DebugCommand>()

  fun sendCommand(cmd: DebugCommand) {
    if (cmd == DebugCommand.STOP) cancelled = true
    commandQueue.offer(cmd)
  }

  override fun onPaused(snapshot: DebugSnapshot): DebugCommand {
    if (cancelled) return DebugCommand.STOP
    onPausedCallback?.invoke(snapshot)
    return try {
      val cmd = commandQueue.take()
      if (cmd == DebugCommand.STOP) cancelled = true
      cmd
    } catch (_: InterruptedException) {
      cancelled = true
      DebugCommand.STOP
    }
  }

  override fun onOutputChunk(text: String) {
    onOutputCallback?.invoke(text)
  }
}

class SdInterpreter(
  private val externalPrograms: Map<String, ProgramNode> = emptyMap(),
  private val stdinProvider: StdinProvider = QueueStdinProvider(emptyList()),
  private val fileBridge: WorkspaceFileBridge = InMemoryFileBridge(),
  private val debugController: DebugController? = null,
  private val mainFileName: String? = null,
) {
  companion object {
    const val MAX_STEPS = 4_000_000L
    const val MAX_OUTPUT_CHARS = 400_000
    const val MAX_CALL_DEPTH = 128
    const val MAX_REBACK = 65_536
    const val MAX_HEAP_SLOTS = 2_000_000
    const val MAX_HEAP_BLOCKS = 8_192
    const val MAX_RETIRED_HEAP_BLOCKS = 256
    const val GC_DRIP_ALLOCATION_INTERVAL = 64
    const val GC_DRIP_SWEEP_BLOCKS = 64
    const val GC_MAJOR_ALLOCATION_INTERVAL = 1_024
  }

  private class ReturnSignal(val value: SdValue) : RuntimeException(null, null, false, false)
  private class BreakSignal : RuntimeException(null, null, false, false)
  private class NextSignal : RuntimeException(null, null, false, false)
  private class DebugStopSignal : RuntimeException(null, null, false, false)

  private data class HeapBlock(
    val id: Int,
    val sizeSlots: Int,
    val slots: MutableList<SdValue>,
    var freed: Boolean = false,
  ) {
    val addressHex: String get() = "0x${(0x10000L + id * 0x1000L).toString(16).uppercase()}"
  }

  private class RuntimeScope(val parent: RuntimeScope? = null) {
    val vars = LinkedHashMap<String, MutableBox>()

    fun declare(name: String, box: MutableBox) {
      vars[name] = box
    }

    fun clear() {
      vars.clear()
    }

    fun resolve(name: String): MutableBox? = vars[name] ?: parent?.resolve(name)
  }

  private data class RuntimeFrame(
    val name: String,
    var currentLine: Int,
    val scope: RuntimeScope,
  )

  private data class ModuleEnv(
    val structs: Map<String, SdType.StructType>,
    val functions: Map<String, FnDecl>,
    val voids: Map<String, VoidDecl>,
    val moduleDeps: Map<String, ModuleEnv>,
  )

  private val output = StringBuilder()
  private val runtimeWarnings = mutableListOf<SdDiagnostic>()
  private val drawCommands = mutableListOf<DrawRectCmd>()
  private val heap = LinkedHashMap<Int, HeapBlock>()
  private var nextHeapId = 1
  private var totalLiveHeapSlots = 0
  private var totalLiveHeapBlocks = 0
  private var allocationsSinceDripCollection = 0
  private var allocationsSinceMajorCollection = 0
  private var nextHeapSweepId = 1

  private val registers =
    SdParser.VALID_REGISTERS.associateWithTo(LinkedHashMap<String, SdValue?>()) { null }
  private val aacManagedRegs = HashMap<String, String>() // varName -> regName

  private val globalScope = RuntimeScope(null)
  private val callStack = ArrayDeque<RuntimeFrame>()
  private val activeScopes = ArrayDeque<RuntimeScope>()
  private var stepCount = 0L
  private var lastExitCode = 0L

  fun currentOutput(): String = output.toString()

  data class ExecutionOutcome(
    val output: String,
    val exitCode: Long,
    val warnings: List<SdDiagnostic>,
    val finalSnapshot: DebugSnapshot,
    val drawCommands: List<DrawRectCmd>,
    val terminatedByUser: Boolean = false,
  )

  fun execute(unit: SdCompilationUnit): ExecutionOutcome {
    var terminatedByUser = false
    try {
      when (unit) {
        is SdCompilationUnit.SingleProgram -> {
          val env = buildModuleEnv(unit.program)
          executeProgram(unit.program, env)
        }
        is SdCompilationUnit.SdAllProgram -> {
          for (comp in unit.compartments) {
            if (comp.switch == CompartmentSwitch.OFF || comp.switch == CompartmentSwitch.PAUSE) continue
            val env = buildModuleEnv(comp.program)
            executeProgram(comp.program, env)
            if (comp.switch == CompartmentSwitch.STOP) break
          }
        }
      }
    } catch (_: DebugStopSignal) {
      terminatedByUser = true
    }

    collectHeapGarbage()

    // W302 runtime leak check if unfreed heap blocks remain
    val leaked = heap.values.filter { !it.freed }
    if (leaked.isNotEmpty()) {
      runtimeWarnings.add(
        SdDiagnostic(
          code = "W302",
          phase = 4,
          line = 1,
          col = 1,
          fileName = mainFileName,
          message = "còn ${leaked.size} vùng nhớ Heap (${leaked.sumOf { it.sizeSlots }} ô) chưa gọi sta.free khi kết thúc",
          severity = DiagnosticSeverity.WARNING,
        )
      )
    }

    return ExecutionOutcome(
      output = output.toString(),
      exitCode = lastExitCode,
      warnings = runtimeWarnings,
      finalSnapshot = captureSnapshot(callStack.lastOrNull()?.currentLine ?: 1, mainFileName),
      drawCommands = drawCommands.toList(),
      terminatedByUser = terminatedByUser,
    )
  }

  private fun buildModuleEnv(program: ProgramNode): ModuleEnv {
    val structs = LinkedHashMap<String, SdType.StructType>()
    val functions = LinkedHashMap<String, FnDecl>()
    val voids = LinkedHashMap<String, VoidDecl>()
    val deps = LinkedHashMap<String, ModuleEnv>()

    for (imp in program.imports) {
      val ext = externalPrograms[imp.name] ?: continue
      val extEnv = buildModuleEnv(ext)
      deps[imp.name] = extEnv
      structs.putAll(extEnv.structs)
    }
    for (st in program.structs) {
      val fields = st.fields.map { StructField(it.name, resolveType(it.typeName, null, null, structs)) }
      structs[st.name] = SdType.StructType(st.name, fields)
    }
    for (fn in program.functions) functions[fn.name] = fn
    for (vd in program.voids) voids[vd.name] = vd
    return ModuleEnv(structs, functions, voids, deps)
  }

  private fun executeProgram(program: ProgramNode, env: ModuleEnv) {
    // Evaluate globals
    for (g in program.globals) {
      val v = if (g.initExpr != null) evalExpr(g.initExpr, globalScope, env) else SdValue.IntVal(0L)
      val t = g.type ?: inferRuntimeType(v)
      globalScope.declare(g.name, MutableBox(v, t, isBorrowed = g.isBorrowed))
    }

    val mainScope = RuntimeScope(globalScope)
    val frame = RuntimeFrame("main", program.mainPos.line, mainScope)
    callStack.addLast(frame)
    try {
      executeStatements(program.mainBody, mainScope, env)
    } catch (ret: ReturnSignal) {
      if (ret.value is SdValue.IntVal) lastExitCode = ret.value.value
    } finally {
      callStack.removeLast()
      // Auto-nullify AaC-managed registers upon exiting AaC scope
      if (program.aacMilestones.isNotEmpty()) {
        for (reg in aacManagedRegs.values) {
          registers[reg] = null
        }
        aacManagedRegs.clear()
      }
    }
  }

  private fun executeStatements(stmts: List<Stmt>, scope: RuntimeScope, env: ModuleEnv) {
    activeScopes.addLast(scope)
    try {
      for (stmt in stmts) {
        executeStmt(stmt, scope, env)
      }
    } finally {
      activeScopes.removeLast()
    }
  }

  private fun createLoopCondition(expr: Expr, scope: RuntimeScope, env: ModuleEnv): () -> Boolean {
    val comparison = expr as? Expr.BinaryExpr
      ?: return { asBool(evalExpr(expr, scope, env), expr.pos) }
    if (comparison.op !in setOf("<", "<=", ">", ">=", "==", "!=")) {
      return { asBool(evalExpr(expr, scope, env), expr.pos) }
    }

    val left = bindIntLoopOperand(comparison.left, scope)
      ?: return { asBool(evalExpr(expr, scope, env), expr.pos) }
    val right = bindIntLoopOperand(comparison.right, scope)
      ?: return { asBool(evalExpr(expr, scope, env), expr.pos) }

    return {
      val leftValue = left()
      val rightValue = right()
      if (leftValue == null || rightValue == null) {
        asBool(evalExpr(expr, scope, env), expr.pos)
      } else {
        when (comparison.op) {
          "<" -> leftValue < rightValue
          "<=" -> leftValue <= rightValue
          ">" -> leftValue > rightValue
          ">=" -> leftValue >= rightValue
          "==" -> leftValue == rightValue
          else -> leftValue != rightValue
        }
      }
    }
  }

  private fun bindIntLoopOperand(expr: Expr, scope: RuntimeScope): (() -> Long?)? {
    return when (expr) {
      is Expr.IntLit -> {
        val value = SdTypeUtils.checkSafeInt(expr.value, expr.pos.line, expr.pos.col, expr.pos.fileName)
        ({ value })
      }
      is Expr.VarExpr -> {
        val box = scope.resolve(expr.name) ?: return null
        { (box.value as? SdValue.IntVal)?.value }
      }
      is Expr.GlobalVarExpr -> {
        val box = globalScope.resolve(expr.name) ?: return null
        { (box.value as? SdValue.IntVal)?.value }
      }
      else -> null
    }
  }

  private fun checkStepAndDebug(pos: SourcePos) {
    stepCount++
    if (stepCount > MAX_STEPS) {
      throw SdRuntimeException(SdDiagnostic("E501", 4, pos.line, pos.col, pos.fileName, "vượt quá giới hạn $MAX_STEPS bước thực thi"))
    }
    val dbg = debugController ?: return
    if (dbg.cancelled) throw DebugStopSignal()

    val stepOverDepth = dbg.stepOverDepth
    val breakpoints = dbg.breakpoints
    if (!dbg.stepMode && stepOverDepth == null && breakpoints.isEmpty()) return

    val isMainFile = pos.fileName == null || mainFileName == null || pos.fileName == mainFileName
    val hitBreakpoint = isMainFile && pos.line in breakpoints
    val hitStepOver = stepOverDepth != null && callStack.size <= stepOverDepth
    val shouldPause = hitBreakpoint || dbg.stepMode || hitStepOver
    if (!shouldPause) return

    callStack.lastOrNull()?.currentLine = pos.line
    dbg.stepOverDepth = null
    val snap = captureSnapshot(pos.line, pos.fileName)
    when (dbg.onPaused(snap)) {
      DebugCommand.STEP_INTO -> {
        dbg.stepMode = true
      }
      DebugCommand.STEP_OVER -> {
        dbg.stepMode = false
        dbg.stepOverDepth = callStack.size
      }
      DebugCommand.CONTINUE -> {
        dbg.stepMode = false
      }
      DebugCommand.STOP -> {
        throw DebugStopSignal()
      }
    }
  }

  private fun executeStmt(stmt: Stmt, scope: RuntimeScope, env: ModuleEnv) {
    checkStepAndDebug(stmt.pos)
    when (stmt) {
      is Stmt.VarDeclStmt -> {
        val declType = resolveType(stmt.typeName, stmt.ptrElemTypeName, stmt.arraySize, env.structs)
        val initVal = if (stmt.initExpr != null) {
          val v = evalExpr(stmt.initExpr, scope, env)
          coerceValue(v, declType)
        } else {
          SdTypeUtils.defaultValueFor(declType, env.structs)
        }
        val finalType = if (stmt.isBorrowed) inferRuntimeType(initVal) else declType
        scope.declare(stmt.name, MutableBox(initVal, finalType, isBorrowed = stmt.isBorrowed))
        if (stmt.isBorrowed && aacManagedRegs.isNotEmpty() || (stmt.isBorrowed && registers.values.any { it == null })) {
          allocateAaCRegIfApplicable(stmt.name, initVal)
        }
      }

      is Stmt.AssignStmt -> {
        val rhs = evalExpr(stmt.value, scope, env)
        if (stmt.target is Expr.VarExpr && scope.resolve(stmt.target.name) == null && stmt.allowImplicitDecl) {
          val inferredType = inferRuntimeType(rhs)
          scope.declare(stmt.target.name, MutableBox(rhs, inferredType))
          return
        }
        assignToTarget(stmt.target, rhs, scope, env, stmt.pos)
      }

      is Stmt.IncDecStmt -> {
        val cur = evalExpr(stmt.target, scope, env)
        val delta = if (stmt.isIncrement) 1L else -1L
        val nextVal = when (cur) {
          is SdValue.IntVal -> SdValue.IntVal(SdTypeUtils.checkSafeInt(cur.value + delta, stmt.pos.line, stmt.pos.col, stmt.pos.fileName))
          is SdValue.FloatVal -> SdValue.FloatVal(cur.value + delta)
          is SdValue.PtrVal -> {
            when (val tgt = cur.target) {
              is PtrTarget.HeapSlotRef -> SdValue.PtrVal(PtrTarget.HeapSlotRef(tgt.blockId, tgt.offset + delta.toInt()))
              is PtrTarget.FixedAddrRef -> SdValue.PtrVal(PtrTarget.FixedAddrRef(tgt.addr + delta))
              else -> throw SdRuntimeException(SdDiagnostic("E405", 4, stmt.pos.line, stmt.pos.col, stmt.pos.fileName, "không thể tăng/giảm con trỏ biến stack hoặc null"))
            }
          }
          else -> throw SdRuntimeException(SdDiagnostic("E303", 4, stmt.pos.line, stmt.pos.col, stmt.pos.fileName, "không thể áp dụng ++/--"))
        }
        assignToTarget(stmt.target, nextVal, scope, env, stmt.pos)
      }

      is Stmt.PriStmt -> {
        val rendered = renderPriExpr(stmt.expr, scope, env, stmt.pos)
        appendOutput(rendered + "\n", stmt.pos)
      }

      is Stmt.IptStmt -> {
        val hintName = (stmt.target as? Expr.VarExpr)?.name ?: "ipt"
        val rawLine = stdinProvider.readNextLine(hintName)
          ?: throw SdRuntimeException(SdDiagnostic("E403", 4, stmt.pos.line, stmt.pos.col, stmt.pos.fileName, "hết dữ liệu nhập liệu (stdin) cho biến '$hintName'"))
        val targetBox = resolveLValueBox(stmt.target, scope, env, stmt.pos)
        val parsedVal: SdValue = when (targetBox?.type ?: SdType.IntType) {
          SdType.IntType -> rawLine.trim().toLongOrNull()?.let { SdValue.IntVal(SdTypeUtils.checkSafeInt(it, stmt.pos.line, stmt.pos.col, stmt.pos.fileName)) }
            ?: throw SdRuntimeException(SdDiagnostic("E403", 4, stmt.pos.line, stmt.pos.col, stmt.pos.fileName, "nhập sai kiểu int: '$rawLine'"))
          SdType.FloatType -> rawLine.trim().toDoubleOrNull()?.let { SdValue.FloatVal(it) }
            ?: throw SdRuntimeException(SdDiagnostic("E403", 4, stmt.pos.line, stmt.pos.col, stmt.pos.fileName, "nhập sai kiểu float: '$rawLine'"))
          SdType.BoolType -> when (rawLine.trim().lowercase()) {
            "true", "1" -> SdValue.BoolVal(true)
            "false", "0" -> SdValue.BoolVal(false)
            else -> throw SdRuntimeException(SdDiagnostic("E403", 4, stmt.pos.line, stmt.pos.col, stmt.pos.fileName, "nhập sai kiểu bool: '$rawLine'"))
          }
          else -> SdValue.StrVal(rawLine)
        }
        assignToTarget(stmt.target, parsedVal, scope, env, stmt.pos)
      }

      is Stmt.CallStmt -> {
        evalCall(stmt.call, scope, env)
      }

      is Stmt.RefStmt -> {
        val v = evalExpr(stmt.value, scope, env)
        throw ReturnSignal(v)
      }

      is Stmt.FixedAddrSimStmt -> {
        scope.declare(stmt.name, MutableBox(SdValue.PtrVal(PtrTarget.FixedAddrRef(stmt.addr)), SdType.PtrType(null)))
      }

      is Stmt.BrkStmt -> throw BreakSignal()
      is Stmt.NxtStmt -> throw NextSignal()

      is Stmt.BoleanStmt -> {
        for (b in stmt.branches) {
          if (b.condition == null || asBool(evalExpr(b.condition, scope, env), b.pos)) {
            executeStatements(b.body, RuntimeScope(scope), env)
            break
          }
        }
      }

      is Stmt.ForStmt -> {
        val loopScope = RuntimeScope(scope)
        val initVal = evalExpr(stmt.initExpr, scope, env)
        if (stmt.initVarType != null) {
          val t = resolveType(stmt.initVarType, null, null, env.structs)
          loopScope.declare(stmt.initVarName, MutableBox(coerceValue(initVal, t), t))
        } else {
          val existing = loopScope.resolve(stmt.initVarName)
          if (existing != null) existing.value = coerceValue(initVal, existing.type)
          else loopScope.declare(stmt.initVarName, MutableBox(initVal, inferRuntimeType(initVal)))
        }
        val condition = createLoopCondition(stmt.condition, loopScope, env)
        val bodyScope = RuntimeScope(loopScope)
        while (condition()) {
          bodyScope.clear()
          try {
            executeStatements(stmt.body, bodyScope, env)
          } catch (_: NextSignal) {
            // proceed to stepStmt
          } catch (_: BreakSignal) {
            break
          }
          executeStmt(stmt.stepStmt, loopScope, env)
        }
      }

      is Stmt.WhileStmt -> {
        val condition = createLoopCondition(stmt.condition, scope, env)
        val bodyScope = RuntimeScope(scope)
        while (condition()) {
          bodyScope.clear()
          try {
            executeStatements(stmt.body, bodyScope, env)
          } catch (_: NextSignal) {
            continue
          } catch (_: BreakSignal) {
            break
          }
        }
      }

      is Stmt.SbStmt -> {
        var rebackCount = 0
        outer@ while (true) {
          for (b in stmt.branches) {
            val matched = b.condition == null || asBool(evalExpr(b.condition, scope, env), b.pos)
            if (matched) {
              executeStatements(b.body, RuntimeScope(scope), env)
              when (b.action) {
                Stmt.SbAction.REBACK -> {
                  rebackCount++
                  if (rebackCount > MAX_REBACK) {
                    throw SdRuntimeException(SdDiagnostic("E402", 4, b.pos.line, b.pos.col, b.pos.fileName, "khối ids sb(${stmt.targetVar}) lặp Reback $$ vượt quá $MAX_REBACK lần"))
                  }
                  continue@outer
                }
                Stmt.SbAction.TRANSF -> {
                  // Continue inspecting remaining branches in this pass
                  continue
                }
                Stmt.SbAction.BREAK, Stmt.SbAction.NONE -> {
                  break@outer
                }
              }
            }
          }
          break@outer
        }
      }

      is Stmt.HardHaltStmt -> {
        throw SdRuntimeException(SdDiagnostic("E409", 4, stmt.pos.line, stmt.pos.col, stmt.pos.fileName, "cờ $$ bên ngoài khối ids sb gây dừng khẩn cấp (Hard Halt)"))
      }

      is Stmt.FreeStmt -> {
        val ptrVal = evalExpr(stmt.ptrExpr, scope, env)
        val target = (ptrVal as? SdValue.PtrVal)?.target
        if (target !is PtrTarget.HeapSlotRef) {
          throw SdRuntimeException(SdDiagnostic("E406", 4, stmt.pos.line, stmt.pos.col, stmt.pos.fileName, "sta.free chỉ giải phóng con trỏ hợp lệ từ sta.alloc (không phải null)"))
        }
        val block = heap[target.blockId]
          ?: throw SdRuntimeException(SdDiagnostic("E406", 4, stmt.pos.line, stmt.pos.col, stmt.pos.fileName, "vùng nhớ Heap không tồn tại"))
        if (block.freed) {
          throw SdRuntimeException(SdDiagnostic("E406", 4, stmt.pos.line, stmt.pos.col, stmt.pos.fileName, "giải phóng hai lần cùng vùng nhớ Heap (double free)"))
        }
        retireHeapBlock(block)
        trimFreedHeapHistory()
      }

      is Stmt.RegLoadStmt -> {
        val v = evalExpr(stmt.valueExpr, scope, env)
        registers[stmt.regName] = v
      }

      is Stmt.RegPushNullifyStmt -> {
        if (stmt.destVarName != null && stmt.destVarType != null && stmt.registers.isNotEmpty()) {
          val rawVal = registers[stmt.registers.first()] ?: SdValue.IntVal(0L)
          val declType = resolveType(stmt.destVarType, null, null, env.structs)
          scope.declare(stmt.destVarName, MutableBox(coerceValue(rawVal, declType), declType))
        }
        for (r in stmt.registers) {
          registers[r] = null
        }
      }

      is Stmt.AaCEnterStmt -> {
        // Pre-allocate simulated tracking; borrowed vars will use free registers
        aacManagedRegs.clear()
      }

      is Stmt.BlockScopeStmt -> {
        executeStatements(stmt.body, scope, env)
      }

      is Stmt.SbFlushStmt, is Stmt.InitFlagStmt -> {}
    }
  }

  private fun allocateAaCRegIfApplicable(varName: String, value: SdValue) {
    val freeReg = registers.entries.firstOrNull { it.value == null }?.key ?: return
    registers[freeReg] = value
    aacManagedRegs[varName] = freeReg
  }

  private fun resolveLValueBox(expr: Expr, scope: RuntimeScope, env: ModuleEnv, pos: SourcePos): MutableBox? {
    return when (expr) {
      is Expr.VarExpr -> scope.resolve(expr.name)
      is Expr.GlobalVarExpr -> globalScope.resolve(expr.name)
      is Expr.MemberExpr -> {
        val base = evalExpr(expr.target, scope, env) as? SdValue.StructVal ?: return null
        base.fields[expr.field]
      }
      is Expr.DerefExpr -> {
        val ptrVal = evalExpr(expr.ptrExpr, scope, env) as? SdValue.PtrVal ?: return null
        val vr = ptrVal.target as? PtrTarget.VarRef
        vr?.box
      }
      else -> null
    }
  }

  private fun assignToTarget(target: Expr, value: SdValue, scope: RuntimeScope, env: ModuleEnv, pos: SourcePos) {
    when (target) {
      is Expr.VarExpr -> {
        val box = scope.resolve(target.name)
          ?: throw SdRuntimeException(SdDiagnostic("E301", 4, pos.line, pos.col, pos.fileName, "biến '${target.name}' chưa khai báo"))
        box.value = coerceValue(value, box.type)
        aacManagedRegs[target.name]?.let { reg -> registers[reg] = box.value }
      }
      is Expr.GlobalVarExpr -> {
        val box = globalScope.resolve(target.name)
          ?: throw SdRuntimeException(SdDiagnostic("E301", 4, pos.line, pos.col, pos.fileName, "biến toàn cục '${target.name}' chưa khai báo"))
        box.value = coerceValue(value, box.type)
      }
      is Expr.MemberExpr -> {
        val base = evalExpr(target.target, scope, env)
        if (base !is SdValue.StructVal) {
          throw SdRuntimeException(SdDiagnostic("E303", 4, pos.line, pos.col, pos.fileName, "không phải struct để ghi trường '.${target.field}'"))
        }
        val box = base.fields[target.field]
          ?: throw SdRuntimeException(SdDiagnostic("E301", 4, pos.line, pos.col, pos.fileName, "không có trường '${target.field}'"))
        box.value = coerceValue(value, box.type)
      }
      is Expr.IndexExpr -> {
        val arr = evalExpr(target.target, scope, env)
        val idxVal = asInt(evalExpr(target.index, scope, env), pos).toInt()
        when (arr) {
          is SdValue.ArrayVal -> {
            if (idxVal < 0 || idxVal >= arr.elements.size) {
              throw SdRuntimeException(SdDiagnostic("E404", 4, pos.line, pos.col, pos.fileName, "chỉ số mảng $idxVal ngoài phạm vi [0..${arr.elements.size - 1}]"))
            }
            arr.elements[idxVal] = coerceValue(value, arr.elemType)
          }
          is SdValue.PtrVal -> writeThroughPtrOffset(arr.target, idxVal, value, pos)
          else -> throw SdRuntimeException(SdDiagnostic("E303", 4, pos.line, pos.col, pos.fileName, "không thể ghi bằng chỉ số []"))
        }
      }
      is Expr.DerefExpr -> {
        val p = evalExpr(target.ptrExpr, scope, env) as? SdValue.PtrVal
          ?: throw SdRuntimeException(SdDiagnostic("E405", 4, pos.line, pos.col, pos.fileName, "không phải con trỏ"))
        writeThroughPtrOffset(p.target, 0, value, pos)
      }
      is Expr.BorrowDerefExpr -> {
        val box = scope.resolve(target.varName)
          ?: throw SdRuntimeException(SdDiagnostic("E301", 4, pos.line, pos.col, pos.fileName, "biến '${target.varName}' chưa khai báo"))
        box.value = coerceValue(value, box.type)
        aacManagedRegs[target.varName]?.let { reg -> registers[reg] = box.value }
      }
      else -> throw SdRuntimeException(SdDiagnostic("E303", 4, pos.line, pos.col, pos.fileName, "đích gán không hợp lệ"))
    }
  }

  private fun writeThroughPtrOffset(target: PtrTarget?, offsetDelta: Int, value: SdValue, pos: SourcePos) {
    when (target) {
      null -> throw SdRuntimeException(SdDiagnostic("E405", 4, pos.line, pos.col, pos.fileName, "giải tham chiếu con trỏ null"))
      is PtrTarget.VarRef -> {
        if (offsetDelta != 0) throw SdRuntimeException(SdDiagnostic("E408", 4, pos.line, pos.col, pos.fileName, "lệch chỉ số trên con trỏ biến stack"))
        target.box.value = coerceValue(value, target.box.type)
      }
      is PtrTarget.HeapSlotRef -> {
        val block = heap[target.blockId]
          ?: throw SdRuntimeException(SdDiagnostic("E405", 4, pos.line, pos.col, pos.fileName, "con trỏ Heap không hợp lệ"))
        if (block.freed) {
          throw SdRuntimeException(SdDiagnostic("E405", 4, pos.line, pos.col, pos.fileName, "truy cập vùng nhớ Heap đã được giải phóng"))
        }
        val slot = target.offset + offsetDelta
        if (slot < 0 || slot >= block.slots.size) {
          throw SdRuntimeException(SdDiagnostic("E408", 4, pos.line, pos.col, pos.fileName, "ghi ô nhớ $slot ngoài vùng Heap đã cấp phát (0..${block.slots.size - 1})"))
        }
        block.slots[slot] = value
      }
      is PtrTarget.FixedAddrRef -> {
        // Simulated write at fixed address
      }
    }
  }

  private fun readThroughPtrOffset(target: PtrTarget?, offsetDelta: Int, pos: SourcePos): SdValue {
    return when (target) {
      null -> throw SdRuntimeException(SdDiagnostic("E405", 4, pos.line, pos.col, pos.fileName, "giải tham chiếu con trỏ null"))
      is PtrTarget.VarRef -> {
        if (offsetDelta != 0) throw SdRuntimeException(SdDiagnostic("E408", 4, pos.line, pos.col, pos.fileName, "đọc lệch chỉ số trên con trỏ stack"))
        target.box.value
      }
      is PtrTarget.HeapSlotRef -> {
        val block = heap[target.blockId]
          ?: throw SdRuntimeException(SdDiagnostic("E405", 4, pos.line, pos.col, pos.fileName, "con trỏ Heap không hợp lệ"))
        if (block.freed) {
          throw SdRuntimeException(SdDiagnostic("E405", 4, pos.line, pos.col, pos.fileName, "đọc vùng nhớ Heap đã giải phóng"))
        }
        val slot = target.offset + offsetDelta
        if (slot < 0 || slot >= block.slots.size) {
          throw SdRuntimeException(SdDiagnostic("E408", 4, pos.line, pos.col, pos.fileName, "đọc ô nhớ $slot ngoài vùng Heap đã cấp phát (0..${block.slots.size - 1})"))
        }
        block.slots[slot]
      }
      is PtrTarget.FixedAddrRef -> SdValue.IntVal(target.addr)
    }
  }

  private fun evalExpr(expr: Expr, scope: RuntimeScope, env: ModuleEnv): SdValue {
    return when (expr) {
      is Expr.IntLit -> SdValue.IntVal(SdTypeUtils.checkSafeInt(expr.value, expr.pos.line, expr.pos.col, expr.pos.fileName))
      is Expr.FloatLit -> SdValue.FloatVal(expr.value)
      is Expr.StrLit -> SdValue.StrVal(expr.value)
      is Expr.BoolLit -> SdValue.BoolVal(expr.value)
      is Expr.ArrayLit -> {
        val vals = expr.elements.map { evalExpr(it, scope, env) }.toMutableList()
        val elemT = if (vals.isEmpty()) SdType.IntType else inferRuntimeType(vals.first())
        SdValue.ArrayVal(vals, elemT)
      }
      is Expr.VarExpr -> {
        scope.resolve(expr.name)?.value
          ?: throw SdRuntimeException(SdDiagnostic("E301", 4, expr.pos.line, expr.pos.col, expr.pos.fileName, "biến '${expr.name}' chưa khai báo"))
      }
      is Expr.GlobalVarExpr -> {
        globalScope.resolve(expr.name)?.value
          ?: throw SdRuntimeException(SdDiagnostic("E301", 4, expr.pos.line, expr.pos.col, expr.pos.fileName, "biến toàn cục '${expr.name}' chưa khai báo"))
      }
      is Expr.RegExpr -> {
        registers[expr.regName] ?: SdValue.IntVal(0L)
      }
      is Expr.MemberExpr -> {
        val base = evalExpr(expr.target, scope, env) as? SdValue.StructVal
          ?: throw SdRuntimeException(SdDiagnostic("E303", 4, expr.pos.line, expr.pos.col, expr.pos.fileName, "không phải struct"))
        base.fields[expr.field]?.value
          ?: throw SdRuntimeException(SdDiagnostic("E301", 4, expr.pos.line, expr.pos.col, expr.pos.fileName, "không có trường '${expr.field}'"))
      }
      is Expr.IndexExpr -> {
        val arr = evalExpr(expr.target, scope, env)
        val i = asInt(evalExpr(expr.index, scope, env), expr.pos).toInt()
        when (arr) {
          is SdValue.ArrayVal -> {
            if (i < 0 || i >= arr.elements.size) {
              throw SdRuntimeException(SdDiagnostic("E404", 4, expr.pos.line, expr.pos.col, expr.pos.fileName, "chỉ số mảng $i ngoài phạm vi [0..${arr.elements.size - 1}]"))
            }
            arr.elements[i]
          }
          is SdValue.PtrVal -> readThroughPtrOffset(arr.target, i, expr.pos)
          else -> throw SdRuntimeException(SdDiagnostic("E303", 4, expr.pos.line, expr.pos.col, expr.pos.fileName, "không thể truy cập chỉ số []"))
        }
      }
      is Expr.AddrOfExpr -> {
        val box = resolveLValueBox(expr.target, scope, env, expr.pos)
          ?: throw SdRuntimeException(SdDiagnostic("E303", 4, expr.pos.line, expr.pos.col, expr.pos.fileName, "không thể lấy địa chỉ @"))
        SdValue.PtrVal(PtrTarget.VarRef(box))
      }
      is Expr.DerefExpr -> {
        val p = evalExpr(expr.ptrExpr, scope, env) as? SdValue.PtrVal
          ?: throw SdRuntimeException(SdDiagnostic("E405", 4, expr.pos.line, expr.pos.col, expr.pos.fileName, "giải tham chiếu giá trị không phải con trỏ"))
        readThroughPtrOffset(p.target, 0, expr.pos)
      }
      is Expr.BorrowDerefExpr -> {
        val box = scope.resolve(expr.varName)
          ?: throw SdRuntimeException(SdDiagnostic("E301", 4, expr.pos.line, expr.pos.col, expr.pos.fileName, "biến mượn '${expr.varName}' không tồn tại"))
        if (!box.isBorrowed) {
          throw SdRuntimeException(SdDiagnostic("E218", 4, expr.pos.line, expr.pos.col, expr.pos.fileName, "toán tử *m(${expr.varName}) chỉ dùng với biến tham chiếu mượn khai báo bằng ':&'"))
        }
        box.value
      }
      is Expr.UnaryExpr -> {
        val v = evalExpr(expr.operand, scope, env)
        when (expr.op) {
          "not" -> SdValue.BoolVal(!asBool(v, expr.pos))
          "-" -> when (v) {
            is SdValue.IntVal -> SdValue.IntVal(SdTypeUtils.checkSafeInt(-v.value, expr.pos.line, expr.pos.col, expr.pos.fileName))
            is SdValue.FloatVal -> SdValue.FloatVal(-v.value)
            else -> throw SdRuntimeException(SdDiagnostic("E303", 4, expr.pos.line, expr.pos.col, expr.pos.fileName, "toán tử '-' không hợp lệ"))
          }
          else -> v
        }
      }
      is Expr.BinaryExpr -> evalBinary(expr, scope, env)
      is Expr.CallExpr -> evalCall(expr, scope, env)
    }
  }

  private fun evalBinary(expr: Expr.BinaryExpr, scope: RuntimeScope, env: ModuleEnv): SdValue {
    if (expr.op == "and") {
      val l = asBool(evalExpr(expr.left, scope, env), expr.pos)
      return SdValue.BoolVal(l && asBool(evalExpr(expr.right, scope, env), expr.pos))
    }
    if (expr.op == "or") {
      val l = asBool(evalExpr(expr.left, scope, env), expr.pos)
      return SdValue.BoolVal(l || asBool(evalExpr(expr.right, scope, env), expr.pos))
    }
    val l = evalExpr(expr.left, scope, env)
    val r = evalExpr(expr.right, scope, env)

    // Pointer arithmetic: ptr + int or ptr - int
    if (l is SdValue.PtrVal && r is SdValue.IntVal && (expr.op == "+" || expr.op == "-")) {
      val delta = if (expr.op == "+") r.value.toInt() else -r.value.toInt()
      return when (val tgt = l.target) {
        is PtrTarget.HeapSlotRef -> SdValue.PtrVal(PtrTarget.HeapSlotRef(tgt.blockId, tgt.offset + delta))
        is PtrTarget.FixedAddrRef -> SdValue.PtrVal(PtrTarget.FixedAddrRef(tgt.addr + delta))
        else -> throw SdRuntimeException(SdDiagnostic("E405", 4, expr.pos.line, expr.pos.col, expr.pos.fileName, "phép toán con trỏ chỉ dùng trên Heap hoặc địa chỉ cố định"))
      }
    }

    if (expr.op == "==" || expr.op == "!=") {
      val eq = valuesEqual(l, r)
      return SdValue.BoolVal(if (expr.op == "==") eq else !eq)
    }

    if (expr.op == "+" && l is SdValue.StrVal && r is SdValue.StrVal) {
      return SdValue.StrVal(l.value + r.value)
    }

    if (expr.op in listOf("<", "<=", ">", ">=")) {
      val cmp = compareNumeric(l, r, expr.pos)
      return SdValue.BoolVal(
        when (expr.op) {
          "<" -> cmp < 0
          "<=" -> cmp <= 0
          ">" -> cmp > 0
          else -> cmp >= 0
        }
      )
    }

    if (l is SdValue.FloatVal || r is SdValue.FloatVal) {
      val lf = asFloat(l, expr.pos)
      val rf = asFloat(r, expr.pos)
      return when (expr.op) {
        "+" -> SdValue.FloatVal(lf + rf)
        "-" -> SdValue.FloatVal(lf - rf)
        "*" -> SdValue.FloatVal(lf * rf)
        "/" -> {
          if (rf == 0.0) throw SdRuntimeException(SdDiagnostic("E401", 4, expr.pos.line, expr.pos.col, expr.pos.fileName, "chia cho 0"))
          SdValue.FloatVal(lf / rf)
        }
        else -> throw SdRuntimeException(SdDiagnostic("E303", 4, expr.pos.line, expr.pos.col, expr.pos.fileName, "toán tử '${expr.op}' không hỗ trợ float"))
      }
    }

    val li = asInt(l, expr.pos)
    val ri = asInt(r, expr.pos)
    val res = when (expr.op) {
      "+" -> li + ri
      "-" -> li - ri
      "*" -> li * ri
      "/" -> {
        if (ri == 0L) throw SdRuntimeException(SdDiagnostic("E401", 4, expr.pos.line, expr.pos.col, expr.pos.fileName, "chia cho 0"))
        li / ri
      }
      "%" -> {
        if (ri == 0L) throw SdRuntimeException(SdDiagnostic("E401", 4, expr.pos.line, expr.pos.col, expr.pos.fileName, "chia dư % cho 0"))
        li % ri
      }
      else -> 0L
    }
    return SdValue.IntVal(SdTypeUtils.checkSafeInt(res, expr.pos.line, expr.pos.col, expr.pos.fileName))
  }

  private fun evalCall(call: Expr.CallExpr, scope: RuntimeScope, env: ModuleEnv): SdValue {
    val args = call.args.map { evalExpr(it, scope, env) }
    val mod = call.module
    if (mod == "sta" && call.name == "alloc") {
      return allocHeap(asInt(args[0], call.pos).toInt(), call.pos)
    }
    if (mod != null && mod in SdParser.STD_LIBS) {
      return evalStdCall(mod, call.name, args, call.pos)
    }

    // Resolve target fn/void and its module environment
    val (targetEnv, fnDecl, voidDecl) = resolveCallable(mod, call.name, env, call.pos)
    if (callStack.size >= MAX_CALL_DEPTH) {
      throw SdRuntimeException(SdDiagnostic("E402", 4, call.pos.line, call.pos.col, call.pos.fileName, "tràn ngăn xếp (đệ quy vượt quá $MAX_CALL_DEPTH tầng)"))
    }

    val fnScope = RuntimeScope(globalScope)
    if (fnDecl != null) {
      for (i in fnDecl.params.indices) {
        val p = fnDecl.params[i]
        val pType = resolveType(p.typeName, p.ptrElemTypeName, p.arraySize, targetEnv.structs)
        val argVal = SdTypeUtils.deepCopyIfNeeded(args[i])
        fnScope.declare(p.name, MutableBox(coerceValue(argVal, pType), pType))
      }
      callStack.addLast(RuntimeFrame(fnDecl.name, fnDecl.pos.line, fnScope))
      return try {
        executeStatements(fnDecl.body, fnScope, targetEnv)
        SdValue.UnitVal
      } catch (ret: ReturnSignal) {
        ret.value
      } finally {
        callStack.removeLast()
      }
    } else if (voidDecl != null) {
      for (i in voidDecl.params.indices) {
        val p = voidDecl.params[i]
        val pType = resolveType(p.typeName, p.ptrElemTypeName, p.arraySize, targetEnv.structs)
        val argVal = SdTypeUtils.deepCopyIfNeeded(args[i])
        fnScope.declare(p.name, MutableBox(coerceValue(argVal, pType), pType))
      }
      callStack.addLast(RuntimeFrame(voidDecl.name, voidDecl.pos.line, fnScope))
      try {
        executeStatements(voidDecl.body, fnScope, targetEnv)
      } catch (_: ReturnSignal) {
      } finally {
        callStack.removeLast()
      }
      return SdValue.UnitVal
    }
    return SdValue.UnitVal
  }

  private fun resolveCallable(
    mod: String?,
    name: String,
    env: ModuleEnv,
    pos: SourcePos,
  ): Triple<ModuleEnv, FnDecl?, VoidDecl?> {
    if (mod != null) {
      val sub = env.moduleDeps[mod]
        ?: throw SdRuntimeException(SdDiagnostic("E301", 4, pos.line, pos.col, pos.fileName, "không tìm thấy mô-đun '$mod'"))
      return Triple(sub, sub.functions[name], sub.voids[name])
    }
    env.functions[name]?.let { return Triple(env, it, null) }
    env.voids[name]?.let { return Triple(env, null, it) }
    for ((_, sub) in env.moduleDeps) {
      sub.functions[name]?.let { return Triple(sub, it, null) }
      sub.voids[name]?.let { return Triple(sub, null, it) }
    }
    throw SdRuntimeException(SdDiagnostic("E301", 4, pos.line, pos.col, pos.fileName, "không tìm thấy hàm '$name'"))
  }

  private fun allocHeap(slots: Int, pos: SourcePos): SdValue.PtrVal {
    if (slots <= 0) {
      throw SdRuntimeException(SdDiagnostic("E407", 4, pos.line, pos.col, pos.fileName, "kích thước sta.alloc phải > 0"))
    }
    allocationsSinceDripCollection++
    allocationsSinceMajorCollection++
    when {
      allocationsSinceMajorCollection >= GC_MAJOR_ALLOCATION_INTERVAL ->
        collectHeapGarbage()
      allocationsSinceDripCollection >= GC_DRIP_ALLOCATION_INTERVAL ->
        collectHeapGarbage(GC_DRIP_SWEEP_BLOCKS)
      totalLiveHeapBlocks >= MAX_HEAP_BLOCKS ||
        totalLiveHeapSlots + slots > MAX_HEAP_SLOTS ->
        collectHeapGarbage()
    }
    if (totalLiveHeapBlocks >= MAX_HEAP_BLOCKS) {
      throw SdRuntimeException(SdDiagnostic("E407", 4, pos.line, pos.col, pos.fileName, "vượt quá giới hạn $MAX_HEAP_BLOCKS vùng nhớ Heap đang cấp phát"))
    }
    if (totalLiveHeapSlots + slots > MAX_HEAP_SLOTS) {
      throw SdRuntimeException(SdDiagnostic("E407", 4, pos.line, pos.col, pos.fileName, "hết bộ nhớ Heap mô phỏng (giới hạn $MAX_HEAP_SLOTS ô)"))
    }
    val id = nextHeapId++
    val list = MutableList<SdValue>(slots) { SdValue.IntVal(0L) }
    heap[id] = HeapBlock(id, slots, list)
    totalLiveHeapSlots += slots
    totalLiveHeapBlocks++
    return SdValue.PtrVal(PtrTarget.HeapSlotRef(id, 0))
  }

  private fun collectHeapGarbage(maxSweepBlocks: Int? = null): Int {
    val markedBlocks = HashSet<Int>()
    val pendingValues = java.util.ArrayDeque<SdValue>()
    val visitedBoxes = java.util.Collections.newSetFromMap(
      java.util.IdentityHashMap<MutableBox, Boolean>(),
    )
    val visitedContainers = java.util.Collections.newSetFromMap(
      java.util.IdentityHashMap<Any, Boolean>(),
    )

    fun markBox(box: MutableBox) {
      if (visitedBoxes.add(box)) pendingValues.addLast(box.value)
    }
    val visitedScopes = java.util.Collections.newSetFromMap(
      java.util.IdentityHashMap<RuntimeScope, Boolean>(),
    )
    fun markScope(scope: RuntimeScope) {
      var current: RuntimeScope? = scope
      while (current != null && visitedScopes.add(current)) {
        current.vars.values.forEach(::markBox)
        current = current.parent
      }
    }

    globalScope.vars.values.forEach(::markBox)
    activeScopes.forEach(::markScope)
    callStack.forEach { markScope(it.scope) }
    registers.values.filterNotNull().forEach(pendingValues::addLast)

    while (pendingValues.isNotEmpty()) {
      when (val value = pendingValues.removeLast()) {
        is SdValue.PtrVal -> when (val target = value.target) {
          is PtrTarget.VarRef -> markBox(target.box)
          is PtrTarget.HeapSlotRef -> {
            val block = heap[target.blockId]
            if (block != null && !block.freed && markedBlocks.add(block.id)) {
              block.slots.forEach(pendingValues::addLast)
            }
          }
          is PtrTarget.FixedAddrRef, null -> Unit
        }
        is SdValue.ArrayVal -> if (visitedContainers.add(value)) {
          value.elements.forEach(pendingValues::addLast)
        }
        is SdValue.StructVal -> if (visitedContainers.add(value)) {
          value.fields.values.forEach(::markBox)
        }
        is SdValue.IntVal, is SdValue.FloatVal, is SdValue.StrVal,
        is SdValue.BoolVal, SdValue.UnitVal -> Unit
      }
    }

    val candidates = heap.values.filterNot { it.freed }
    if (candidates.isEmpty()) {
      allocationsSinceDripCollection = 0
      allocationsSinceMajorCollection = 0
      return 0
    }
    val sweepCount = maxSweepBlocks?.coerceAtMost(candidates.size) ?: candidates.size
    val startIndex = candidates.indexOfFirst { it.id >= nextHeapSweepId }.let {
      if (it < 0) 0 else it
    }
    var reclaimed = 0
    repeat(sweepCount) { offset ->
      val block = candidates[(startIndex + offset) % candidates.size]
      nextHeapSweepId = block.id + 1
      if (block.id !in markedBlocks) {
        retireHeapBlock(block)
        reclaimed++
      }
    }
    trimFreedHeapHistory()
    allocationsSinceDripCollection = 0
    if (maxSweepBlocks == null) allocationsSinceMajorCollection = 0
    return reclaimed
  }

  private fun retireHeapBlock(block: HeapBlock) {
    if (block.freed) return
    block.freed = true
    totalLiveHeapSlots -= block.sizeSlots
    totalLiveHeapBlocks--
    block.slots.clear()
  }

  private fun trimFreedHeapHistory() {
    var freedBlocks = heap.values.count { it.freed }
    while (freedBlocks > MAX_RETIRED_HEAP_BLOCKS) {
      val oldestFreedId = heap.entries.firstOrNull { it.value.freed }?.key ?: return
      heap.remove(oldestFreedId)
      freedBlocks--
    }
  }

  private fun evalStdCall(mod: String, name: String, args: List<SdValue>, pos: SourcePos): SdValue {
    return when (mod) {
      "math" -> when (name) {
        "abs" -> when (val v = args[0]) {
          is SdValue.IntVal -> SdValue.IntVal(abs(v.value))
          is SdValue.FloatVal -> SdValue.FloatVal(abs(v.value))
          else -> SdValue.IntVal(0L)
        }
        "pow" -> SdValue.FloatVal(asFloat(args[0], pos).pow(asFloat(args[1], pos)))
        "sqrt" -> {
          val x = asFloat(args[0], pos)
          if (x < 0) throw SdRuntimeException(SdDiagnostic("E401", 4, pos.line, pos.col, pos.fileName, "math.sqrt của số âm ($x)"))
          SdValue.FloatVal(sqrt(x))
        }
        "max" -> {
          val a = args[0]; val b = args[1]
          if (a is SdValue.IntVal && b is SdValue.IntVal) SdValue.IntVal(max(a.value, b.value))
          else SdValue.FloatVal(max(asFloat(a, pos), asFloat(b, pos)))
        }
        "min" -> {
          val a = args[0]; val b = args[1]
          if (a is SdValue.IntVal && b is SdValue.IntVal) SdValue.IntVal(min(a.value, b.value))
          else SdValue.FloatVal(min(asFloat(a, pos), asFloat(b, pos)))
        }
        else -> SdValue.UnitVal
      }
      "str" -> when (name) {
        "len" -> SdValue.IntVal((args[0] as? SdValue.StrVal)?.value?.length?.toLong() ?: 0L)
        "upper" -> SdValue.StrVal((args[0] as? SdValue.StrVal)?.value?.uppercase(Locale.getDefault()) ?: "")
        "lower" -> SdValue.StrVal((args[0] as? SdValue.StrVal)?.value?.lowercase(Locale.getDefault()) ?: "")
        "concat" -> SdValue.StrVal(
          ((args[0] as? SdValue.StrVal)?.value ?: "") + ((args[1] as? SdValue.StrVal)?.value ?: "")
        )
        else -> SdValue.UnitVal
      }
      "mem" -> when (name) {
        "alloc" -> allocHeap(asInt(args[0], pos).toInt(), pos)
        "memset" -> {
          val arr = args[0] as? SdValue.ArrayVal
          val fill = args[1]
          val n = asInt(args[2], pos).toInt()
          if (arr != null) {
            val count = min(n, arr.elements.size)
            for (i in 0 until count) {
              arr.elements[i] = coerceValue(fill, arr.elemType)
            }
          }
          SdValue.UnitVal
        }
        else -> SdValue.UnitVal
      }
      "sys" -> when (name) {
        "time" -> SdValue.IntVal(System.currentTimeMillis() / 1000L)
        "gc" -> {
          if (args.isNotEmpty()) {
            throw SdRuntimeException(SdDiagnostic("E304", 4, pos.line, pos.col, pos.fileName, "sys.gc không nhận đối số"))
          }
          SdValue.IntVal(collectHeapGarbage().toLong())
        }
        "sleep" -> {
          val ms = min(asInt(args[0], pos).coerceAtLeast(0L), 5000L)
          try { Thread.sleep(ms) } catch (_: InterruptedException) {}
          SdValue.UnitVal
        }
        else -> SdValue.UnitVal
      }
      "file" -> when (name) {
        "write" -> {
          val fname = (args[0] as? SdValue.StrVal)?.value ?: ""
          val content = (args[1] as? SdValue.StrVal)?.value ?: ""
          fileBridge.writeFile(fname, content)
          SdValue.UnitVal
        }
        "read" -> {
          val fname = (args[0] as? SdValue.StrVal)?.value ?: ""
          val content = fileBridge.readFile(fname)
            ?: throw SdRuntimeException(SdDiagnostic("E403", 4, pos.line, pos.col, pos.fileName, "không tìm thấy tệp '$fname' để đọc"))
          SdValue.StrVal(content)
        }
        else -> SdValue.UnitVal
      }
      "draw" -> when (name) {
        "rect" -> {
          val x = asInt(args[0], pos).toInt()
          val y = asInt(args[1], pos).toInt()
          val w = asInt(args[2], pos).toInt()
          val h = asInt(args[3], pos).toInt()
          drawCommands.add(DrawRectCmd(x, y, w, h))
          val ascii = renderAsciiRect(w, h)
          appendOutput(ascii, pos)
          SdValue.UnitVal
        }
        else -> SdValue.UnitVal
      }
      else -> SdValue.UnitVal
    }
  }

  private fun renderAsciiRect(w: Int, h: Int): String {
    val cw = w.coerceIn(2, 40)
    val ch = h.coerceIn(2, 20)
    val sb = StringBuilder()
    for (r in 0 until ch) {
      for (c in 0 until cw) {
        val border = r == 0 || r == ch - 1 || c == 0 || c == cw - 1
        sb.append(if (border) '*' else ' ')
      }
      sb.append('\n')
    }
    return sb.toString()
  }

  private fun renderPriExpr(expr: Expr, scope: RuntimeScope, env: ModuleEnv, pos: SourcePos): String {
    if (expr is Expr.StrLit) {
      return interpolateFormatString(expr.value, scope, env, pos)
    }
    return formatValue(evalExpr(expr, scope, env), null)
  }

  private fun interpolateFormatString(template: String, scope: RuntimeScope, env: ModuleEnv, pos: SourcePos): String {
    val sb = StringBuilder()
    var i = 0
    while (i < template.length) {
      if (template[i] == '/' && i + 1 < template.length) {
        val kind = when {
          template.startsWith("/f3{", i) -> "f3"
          template.startsWith("/d{", i) -> "d"
          template.startsWith("/s{", i) -> "s"
          template.startsWith("/f{", i) -> "f"
          template.startsWith("/b{", i) -> "b"
          else -> null
        }
        if (kind != null) {
          val braceStart = i + 1 + kind.length
          val closeIdx = findMatchingBrace(template, braceStart)
          if (closeIdx > braceStart) {
            val innerExprText = template.substring(braceStart + 1, closeIdx)
            val valRes = evalMiniExpr(innerExprText, scope, env, pos)
            sb.append(formatValue(valRes, kind))
            i = closeIdx + 1
            continue
          }
        }
        // Legacy format without braces: /dten
        val legChar = template[i + 1]
        if (legChar in listOf('d', 's', 'f', 'b')) {
          var end = i + 2
          while (end < template.length && (template[end].isLetterOrDigit() || template[end] == '_')) end++
          if (end > i + 2) {
            val varName = template.substring(i + 2, end)
            val box = scope.resolve(varName)
            if (box != null) {
              sb.append(formatValue(box.value, legChar.toString()))
              i = end
              continue
            }
          }
        }
      }
      sb.append(template[i])
      i++
    }
    return sb.toString()
  }

  private fun findMatchingBrace(s: String, openIdx: Int): Int {
    var depth = 0
    for (k in openIdx until s.length) {
      if (s[k] == '{') depth++
      else if (s[k] == '}') {
        depth--
        if (depth == 0) return k
      }
    }
    return -1
  }

  private fun evalMiniExpr(code: String, scope: RuntimeScope, env: ModuleEnv, pos: SourcePos): SdValue {
    val wrapped = "ssta { ids.pri: $code $ } ##"
    val lex = SdLexer(wrapped, pos.fileName).tokenize()
    val unit = SdParser(lex.tokens, pos.fileName).parseCompilationUnit()
    val prog = (unit as SdCompilationUnit.SingleProgram).program
    val priStmt = prog.mainBody.firstOrNull() as? Stmt.PriStmt
      ?: throw SdRuntimeException(SdDiagnostic("E202", 4, pos.line, pos.col, pos.fileName, "biểu thức trong định dạng '{$code}' không hợp lệ"))
    return evalExpr(priStmt.expr, scope, env)
  }

  private fun formatValue(v: SdValue, fmtKind: String?): String = when (v) {
    is SdValue.IntVal -> if (fmtKind == "f") String.format(Locale.US, "%.2f", v.value.toDouble())
    else if (fmtKind == "f3") String.format(Locale.US, "%.3f", v.value.toDouble())
    else v.value.toString()
    is SdValue.FloatVal -> if (fmtKind == "f3") String.format(Locale.US, "%.3f", v.value)
    else if (fmtKind == "d") v.value.toLong().toString()
    else String.format(Locale.US, "%.2f", v.value)
    is SdValue.StrVal -> v.value
    is SdValue.BoolVal -> if (v.value) "true" else "false"
    is SdValue.PtrVal -> v.toString()
    is SdValue.ArrayVal -> v.toString()
    is SdValue.StructVal -> v.toString()
    SdValue.UnitVal -> ""
  }

  private fun appendOutput(text: String, pos: SourcePos) {
    if (output.length + text.length > MAX_OUTPUT_CHARS) {
      throw SdRuntimeException(SdDiagnostic("E501", 4, pos.line, pos.col, pos.fileName, "vượt quá giới hạn $MAX_OUTPUT_CHARS ký tự đầu ra"))
    }
    output.append(text)
    debugController?.onOutputChunk(text)
  }

  private fun resolveType(name: String, ptrElem: String?, arrSize: Int?, structs: Map<String, SdType.StructType>): SdType {
    val base = when (name) {
      "int" -> SdType.IntType
      "float" -> SdType.FloatType
      "str" -> SdType.StrType
      "bool" -> SdType.BoolType
      "void" -> SdType.VoidType
      "ptr" -> SdType.PtrType(ptrElem?.let { resolveType(it, null, null, structs) })
      else -> structs[name] ?: SdType.UnknownType
    }
    return if (arrSize != null) SdType.ArrayType(base, arrSize) else base
  }

  private fun inferRuntimeType(v: SdValue): SdType = when (v) {
    is SdValue.IntVal -> SdType.IntType
    is SdValue.FloatVal -> SdType.FloatType
    is SdValue.StrVal -> SdType.StrType
    is SdValue.BoolVal -> SdType.BoolType
    is SdValue.PtrVal -> SdType.PtrType(null)
    is SdValue.ArrayVal -> SdType.ArrayType(v.elemType, v.elements.size)
    is SdValue.StructVal -> SdType.UnknownType
    SdValue.UnitVal -> SdType.VoidType
  }

  private fun coerceValue(v: SdValue, targetType: SdType): SdValue {
    if (targetType == SdType.FloatType && v is SdValue.IntVal) {
      return SdValue.FloatVal(v.value.toDouble())
    }
    return v
  }

  private fun asBool(v: SdValue, pos: SourcePos): Boolean = when (v) {
    is SdValue.BoolVal -> v.value
    else -> throw SdRuntimeException(SdDiagnostic("E303", 4, pos.line, pos.col, pos.fileName, "kỳ vọng bool, gặp '$v'"))
  }

  private fun asInt(v: SdValue, pos: SourcePos): Long = when (v) {
    is SdValue.IntVal -> v.value
    else -> throw SdRuntimeException(SdDiagnostic("E303", 4, pos.line, pos.col, pos.fileName, "kỳ vọng int, gặp '$v'"))
  }

  private fun asFloat(v: SdValue, pos: SourcePos): Double = when (v) {
    is SdValue.FloatVal -> v.value
    is SdValue.IntVal -> v.value.toDouble()
    else -> throw SdRuntimeException(SdDiagnostic("E303", 4, pos.line, pos.col, pos.fileName, "kỳ vọng số thực/nguyên, gặp '$v'"))
  }

  private fun valuesEqual(a: SdValue, b: SdValue): Boolean = when {
    a is SdValue.IntVal && b is SdValue.IntVal -> a.value == b.value
    (a is SdValue.IntVal || a is SdValue.FloatVal) && (b is SdValue.IntVal || b is SdValue.FloatVal) ->
      (if (a is SdValue.FloatVal) a.value else (a as SdValue.IntVal).value.toDouble()) ==
        (if (b is SdValue.FloatVal) b.value else (b as SdValue.IntVal).value.toDouble())
    a is SdValue.StrVal && b is SdValue.StrVal -> a.value == b.value
    a is SdValue.BoolVal && b is SdValue.BoolVal -> a.value == b.value
    a is SdValue.PtrVal && b is SdValue.PtrVal -> a.target == b.target
    else -> a == b
  }

  private fun compareNumeric(a: SdValue, b: SdValue, pos: SourcePos): Int {
    if (a is SdValue.IntVal && b is SdValue.IntVal) return a.value.compareTo(b.value)
    return asFloat(a, pos).compareTo(asFloat(b, pos))
  }

  private fun captureSnapshot(line: Int, file: String?): DebugSnapshot {
    val frames = callStack.map { f ->
      val vars = f.scope.vars.entries.map { (n, box) ->
        VarSnapshot(n, box.type.toString(), box.value.toString(), box.isBorrowed)
      }
      CallFrameInfo(f.name, f.currentLine, vars)
    }
    val globalsList = globalScope.vars.entries.map { (n, box) ->
      VarSnapshot(n, box.type.toString(), box.value.toString(), box.isBorrowed)
    }
    val heapList = heap.values.map { hb ->
      val preview = hb.slots.take(6).joinToString(", ") + if (hb.slots.size > 6) ", ..." else ""
      HeapBlockSnapshot(hb.id, hb.addressHex, hb.sizeSlots, hb.freed, preview)
    }
    val regList = registers.entries.map { (name, value) ->
      RegisterSnapshot(
        name = name,
        value = value?.toString() ?: "null (trống)",
        active = value != null,
        managedByAaC = name in aacManagedRegs.values,
      )
    }
    return DebugSnapshot(line, file, frames, globalsList, heapList, regList)
  }
}
