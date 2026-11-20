package com.example.sdide.engine

internal enum class SdVmValueKind { INT, BOOL }

internal enum class SdVmOp {
  CHECK_STEP,
  PUSH_INT,
  PUSH_BOOL,
  LOAD,
  STORE,
  ADD,
  SUB,
  MUL,
  DIV,
  MOD,
  NEG,
  NOT,
  EQ,
  NE,
  LT,
  LE,
  GT,
  GE,
  JUMP,
  JUMP_IF_FALSE,
  JUMP_IF_TRUE,
  PRINT_TEXT,
  PRINT_VALUE,
  PRINT_NEWLINE,
  RETURN,
  HALT,
}

internal data class SdVmInstruction(
  val op: SdVmOp,
  val intOperand: Int = 0,
  val longOperand: Long = 0L,
  val textOperand: String? = null,
  val pos: SourcePos,
)

internal data class SdVmSlot(
  val name: String,
  val kind: SdVmValueKind,
  val visibleAtEnd: Boolean,
)

internal data class SdVmProgram(
  val instructions: List<SdVmInstruction>,
  val slots: List<SdVmSlot>,
)

internal object SdVmCompiler {
  private class UnsupportedFeature : RuntimeException(null, null, false, false)

  private data class LoopContext(
    val breakJumps: MutableList<Int> = mutableListOf(),
    val continueJumps: MutableList<Int> = mutableListOf(),
  )

  fun compile(
    unit: SdCompilationUnit,
    externalPrograms: Map<String, ProgramNode>,
  ): SdVmProgram? {
    val program = (unit as? SdCompilationUnit.SingleProgram)?.program ?: return null
    if (
      externalPrograms.isNotEmpty() ||
      program.imports.any { it.name !in SdParser.STD_LIBS } ||
      program.aacMilestones.isNotEmpty() ||
      program.defks.isNotEmpty() ||
      program.globals.isNotEmpty() ||
      program.structs.isNotEmpty() ||
      program.functions.isNotEmpty() ||
      program.voids.isNotEmpty() ||
      program.defineRenames.isNotEmpty()
    ) {
      return null
    }

    return try {
      Builder().compile(program)
    } catch (_: UnsupportedFeature) {
      null
    }
  }

  private class Builder {
    private val instructions = mutableListOf<SdVmInstruction>()
    private val slots = mutableListOf<SdVmSlot>()
    private val scopes = mutableListOf<MutableMap<String, Int>>()
    private val loops = mutableListOf<LoopContext>()

    fun compile(program: ProgramNode): SdVmProgram {
      scopes.add(mutableMapOf())
      compileStatements(program.mainBody)
      emit(SdVmOp.HALT, program.mainPos)
      return SdVmProgram(instructions.toList(), slots.toList())
    }

    private fun compileStatements(statements: List<Stmt>) {
      statements.forEach(::compileStatement)
    }

    private fun compileStatement(stmt: Stmt) {
      emit(SdVmOp.CHECK_STEP, stmt.pos)
      when (stmt) {
        is Stmt.VarDeclStmt -> compileVariableDeclaration(stmt)
        is Stmt.AssignStmt -> compileAssignment(stmt)
        is Stmt.IncDecStmt -> compileIncrement(stmt)
        is Stmt.PriStmt -> compilePrint(stmt)
        is Stmt.RefStmt -> {
          if (compileExpression(stmt.value) != SdVmValueKind.INT) unsupported()
          emit(SdVmOp.RETURN, stmt.pos)
        }
        is Stmt.BrkStmt -> {
          val loop = loops.lastOrNull() ?: unsupported()
          loop.breakJumps += emitJump(SdVmOp.JUMP, stmt.pos)
        }
        is Stmt.NxtStmt -> {
          val loop = loops.lastOrNull() ?: unsupported()
          loop.continueJumps += emitJump(SdVmOp.JUMP, stmt.pos)
        }
        is Stmt.BoleanStmt -> compileBoolean(stmt)
        is Stmt.ForStmt -> compileFor(stmt)
        is Stmt.WhileStmt -> compileWhile(stmt)
        is Stmt.BlockScopeStmt -> compileStatements(stmt.body)
        else -> unsupported()
      }
    }

    private fun compileVariableDeclaration(stmt: Stmt.VarDeclStmt) {
      if (stmt.arraySize != null || stmt.ptrElemTypeName != null || stmt.isBorrowed) unsupported()
      val kind = when (stmt.typeName) {
        "int" -> SdVmValueKind.INT
        "bool" -> SdVmValueKind.BOOL
        else -> unsupported()
      }
      val valueKind = if (stmt.initExpr != null) compileExpression(stmt.initExpr) else {
        emit(if (kind == SdVmValueKind.INT) SdVmOp.PUSH_INT else SdVmOp.PUSH_BOOL, stmt.pos)
        SdVmValueKind.INT
      }
      if (kind != valueKind && !(kind == SdVmValueKind.BOOL && stmt.initExpr == null)) unsupported()
      val slot = declareSlot(stmt.name, kind)
      emit(SdVmOp.STORE, stmt.pos, intOperand = slot)
    }

    private fun compileAssignment(stmt: Stmt.AssignStmt) {
      if (stmt.allowImplicitDecl) unsupported()
      val target = stmt.target as? Expr.VarExpr ?: unsupported()
      val slot = findSlot(target.name) ?: unsupported()
      val valueKind = compileExpression(stmt.value)
      if (slots[slot].kind != valueKind) unsupported()
      emit(SdVmOp.STORE, stmt.pos, intOperand = slot)
    }

    private fun compileIncrement(stmt: Stmt.IncDecStmt) {
      val target = stmt.target as? Expr.VarExpr ?: unsupported()
      val slot = findSlot(target.name) ?: unsupported()
      if (slots[slot].kind != SdVmValueKind.INT) unsupported()
      emit(SdVmOp.LOAD, stmt.pos, intOperand = slot)
      emit(SdVmOp.PUSH_INT, stmt.pos, longOperand = 1L)
      emit(if (stmt.isIncrement) SdVmOp.ADD else SdVmOp.SUB, stmt.pos)
      emit(SdVmOp.STORE, stmt.pos, intOperand = slot)
    }

    private fun compilePrint(stmt: Stmt.PriStmt) {
      val expression = stmt.expr
      if (expression is Expr.StrLit) {
        compileStringTemplate(expression.value, expression.pos)
      } else {
        val kind = compileExpression(expression)
        emit(SdVmOp.PRINT_VALUE, stmt.pos, intOperand = kind.ordinal)
      }
      emit(SdVmOp.PRINT_NEWLINE, stmt.pos)
    }

    private fun compileStringTemplate(template: String, pos: SourcePos) {
      val literal = StringBuilder()
      var index = 0
      while (index < template.length) {
        if (template.startsWith("/d{", index)) {
          val end = template.indexOf('}', index + 3)
          if (end < 0) unsupported()
          val name = template.substring(index + 3, end)
          if (!isIdentifier(name)) unsupported()
          val slot = findSlot(name) ?: unsupported()
          if (slots[slot].kind != SdVmValueKind.INT) unsupported()
          if (literal.isNotEmpty()) {
            emit(SdVmOp.PRINT_TEXT, pos, textOperand = literal.toString())
            literal.setLength(0)
          }
          emit(SdVmOp.LOAD, pos, intOperand = slot)
          emit(SdVmOp.PRINT_VALUE, pos, intOperand = SdVmValueKind.INT.ordinal)
          index = end + 1
        } else {
          if (
            template[index] == '/' &&
            index + 1 < template.length &&
            template[index + 1] in listOf('d', 's', 'f', 'b')
          ) {
            unsupported()
          }
          literal.append(template[index])
          index++
        }
      }
      if (literal.isNotEmpty()) emit(SdVmOp.PRINT_TEXT, pos, textOperand = literal.toString())
    }

    private fun compileBoolean(stmt: Stmt.BoleanStmt) {
      val endJumps = mutableListOf<Int>()
      for (branch in stmt.branches) {
        val nextBranch = branch.condition?.let {
          if (compileExpression(it) != SdVmValueKind.BOOL) unsupported()
          emitJump(SdVmOp.JUMP_IF_FALSE, branch.pos)
        }
        withScope { compileStatements(branch.body) }
        endJumps += emitJump(SdVmOp.JUMP, branch.pos)
        if (nextBranch != null) patchJump(nextBranch, instructions.size)
        if (branch.condition == null) break
      }
      endJumps.forEach { patchJump(it, instructions.size) }
    }

    private fun compileFor(stmt: Stmt.ForStmt) {
      val initKind = compileExpression(stmt.initExpr)
      if (initKind != SdVmValueKind.INT) unsupported()

      val loopSlot: Int
      val ownsLoopScope = stmt.initVarType != null
      if (ownsLoopScope) {
        if (stmt.initVarType != "int") unsupported()
        scopes.add(mutableMapOf())
        loopSlot = declareSlot(stmt.initVarName, SdVmValueKind.INT)
      } else {
        loopSlot = findSlot(stmt.initVarName) ?: unsupported()
        if (slots[loopSlot].kind != SdVmValueKind.INT) unsupported()
      }
      emit(SdVmOp.STORE, stmt.pos, intOperand = loopSlot)

      val loop = LoopContext()
      loops.add(loop)
      val conditionStart = instructions.size
      if (compileExpression(stmt.condition) != SdVmValueKind.BOOL) unsupported()
      val exitJump = emitJump(SdVmOp.JUMP_IF_FALSE, stmt.pos)
      withScope { compileStatements(stmt.body) }

      val stepStart = instructions.size
      loop.continueJumps.forEach { patchJump(it, stepStart) }
      compileStatement(stmt.stepStmt)
      emit(SdVmOp.JUMP, stmt.pos, intOperand = conditionStart)

      val loopEnd = instructions.size
      patchJump(exitJump, loopEnd)
      loop.breakJumps.forEach { patchJump(it, loopEnd) }
      loops.removeAt(loops.lastIndex)
      if (ownsLoopScope) scopes.removeAt(scopes.lastIndex)
    }

    private fun compileWhile(stmt: Stmt.WhileStmt) {
      val conditionStart = instructions.size
      if (compileExpression(stmt.condition) != SdVmValueKind.BOOL) unsupported()
      val exitJump = emitJump(SdVmOp.JUMP_IF_FALSE, stmt.pos)
      val loop = LoopContext()
      loops.add(loop)
      withScope { compileStatements(stmt.body) }
      loop.continueJumps.forEach { patchJump(it, conditionStart) }
      emit(SdVmOp.JUMP, stmt.pos, intOperand = conditionStart)

      val loopEnd = instructions.size
      patchJump(exitJump, loopEnd)
      loop.breakJumps.forEach { patchJump(it, loopEnd) }
      loops.removeAt(loops.lastIndex)
    }

    private fun compileExpression(expr: Expr): SdVmValueKind = when (expr) {
      is Expr.IntLit -> {
        emit(SdVmOp.PUSH_INT, expr.pos, longOperand = expr.value)
        SdVmValueKind.INT
      }
      is Expr.BoolLit -> {
        emit(SdVmOp.PUSH_BOOL, expr.pos, longOperand = if (expr.value) 1L else 0L)
        SdVmValueKind.BOOL
      }
      is Expr.VarExpr -> {
        val slot = findSlot(expr.name) ?: unsupported()
        emit(SdVmOp.LOAD, expr.pos, intOperand = slot)
        slots[slot].kind
      }
      is Expr.UnaryExpr -> {
        val operandKind = compileExpression(expr.operand)
        when (expr.op) {
          "-" -> {
            if (operandKind != SdVmValueKind.INT) unsupported()
            emit(SdVmOp.NEG, expr.pos)
            SdVmValueKind.INT
          }
          "not" -> {
            if (operandKind != SdVmValueKind.BOOL) unsupported()
            emit(SdVmOp.NOT, expr.pos)
            SdVmValueKind.BOOL
          }
          else -> unsupported()
        }
      }
      is Expr.BinaryExpr -> compileBinary(expr)
      else -> unsupported()
    }

    private fun compileBinary(expr: Expr.BinaryExpr): SdVmValueKind {
      if (expr.op == "and" || expr.op == "or") {
        if (compileExpression(expr.left) != SdVmValueKind.BOOL) unsupported()
        val jumpOp = if (expr.op == "and") SdVmOp.JUMP_IF_FALSE else SdVmOp.JUMP_IF_TRUE
        val shortCircuitJump = emitJump(jumpOp, expr.pos)
        if (compileExpression(expr.right) != SdVmValueKind.BOOL) unsupported()
        val endJump = emitJump(SdVmOp.JUMP, expr.pos)
        patchJump(shortCircuitJump, instructions.size)
        emit(SdVmOp.PUSH_BOOL, expr.pos, longOperand = if (expr.op == "and") 0L else 1L)
        patchJump(endJump, instructions.size)
        return SdVmValueKind.BOOL
      }

      val leftKind = compileExpression(expr.left)
      val rightKind = compileExpression(expr.right)
      val op = when (expr.op) {
        "+" -> SdVmOp.ADD
        "-" -> SdVmOp.SUB
        "*" -> SdVmOp.MUL
        "/" -> SdVmOp.DIV
        "%" -> SdVmOp.MOD
        "==" -> SdVmOp.EQ
        "!=" -> SdVmOp.NE
        "<" -> SdVmOp.LT
        "<=" -> SdVmOp.LE
        ">" -> SdVmOp.GT
        ">=" -> SdVmOp.GE
        else -> unsupported()
      }
      if (expr.op in setOf("+", "-", "*", "/", "%")) {
        if (leftKind != SdVmValueKind.INT || rightKind != SdVmValueKind.INT) unsupported()
        emit(op, expr.pos)
        return SdVmValueKind.INT
      }
      if (leftKind != rightKind) unsupported()
      if (expr.op !in setOf("==", "!=") &&
        (leftKind != SdVmValueKind.INT || rightKind != SdVmValueKind.INT)
      ) {
        unsupported()
      }
      emit(op, expr.pos)
      return SdVmValueKind.BOOL
    }

    private fun declareSlot(name: String, kind: SdVmValueKind): Int {
      if (scopes.last().containsKey(name)) unsupported()
      val slot = slots.size
      scopes.last()[name] = slot
      slots += SdVmSlot(name, kind, visibleAtEnd = scopes.size == 1)
      return slot
    }

    private fun findSlot(name: String): Int? {
      for (index in scopes.indices.reversed()) {
        scopes[index][name]?.let { return it }
      }
      return null
    }

    private fun withScope(action: () -> Unit) {
      scopes.add(mutableMapOf())
      try {
        action()
      } finally {
        scopes.removeAt(scopes.lastIndex)
      }
    }

    private fun emit(
      op: SdVmOp,
      pos: SourcePos,
      intOperand: Int = 0,
      longOperand: Long = 0L,
      textOperand: String? = null,
    ): Int {
      instructions += SdVmInstruction(op, intOperand, longOperand, textOperand, pos)
      return instructions.lastIndex
    }

    private fun emitJump(op: SdVmOp, pos: SourcePos): Int =
      emit(op, pos, intOperand = -1)

    private fun patchJump(instructionIndex: Int, target: Int) {
      instructions[instructionIndex] = instructions[instructionIndex].copy(intOperand = target)
    }

    private fun isIdentifier(value: String): Boolean {
      if (value.isEmpty() || !(value[0] == '_' || value[0].isLetter())) return false
      return value.drop(1).all { it == '_' || it.isLetterOrDigit() }
    }

    private fun unsupported(): Nothing = throw UnsupportedFeature()
  }
}
