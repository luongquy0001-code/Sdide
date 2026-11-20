package com.example.sdide.engine

enum class SdTargetLanguage(val displayName: String, val extension: String) {
  C99("C99", ".c"),
  JAVASCRIPT("JavaScript", ".js"),
  PYTHON("Python", ".py"),
  KOTLIN("Kotlin", ".kt"),
}

class SdBackendGenerationException(message: String) : IllegalArgumentException(message)

object SdTargetCodeGenerator {
  fun generate(
    unit: SdCompilationUnit,
    target: SdTargetLanguage,
    externalPrograms: Map<String, ProgramNode> = emptyMap(),
  ): String {
    if (target == SdTargetLanguage.C99) return SdCToTranspiler().transpile(unit)
    if (unit !is SdCompilationUnit.SingleProgram) {
      throw SdBackendGenerationException("${target.displayName} chưa hỗ trợ dự án nhiều ngăn .sdall.")
    }
    if (externalPrograms.isNotEmpty()) {
      throw SdBackendGenerationException("${target.displayName} chưa hỗ trợ imp* tới mã nguồn thư viện người dùng.")
    }
    return PortableSdBackend(target).generate(unit.program)
  }
}

private class PortableSdBackend(private val target: SdTargetLanguage) {
  private val out = StringBuilder()
  private val scopes = ArrayDeque<Map<String, String>>()
  private var userFunctionNames = emptySet<String>()
  private var functionReturnTypes = emptyMap<String, String>()
  private var indent = 0
  private var inMain = false

  private val keywordNames = when (target) {
    SdTargetLanguage.C99 -> emptySet()
    SdTargetLanguage.JAVASCRIPT -> setOf(
      "await", "break", "case", "catch", "class", "const", "continue", "debugger", "default",
      "delete", "do", "else", "export", "extends", "finally", "for", "function", "if", "import",
      "in", "instanceof", "let", "new", "return", "static", "super", "switch", "this", "throw",
      "try", "typeof", "var", "void", "while", "with", "yield",
    )
    SdTargetLanguage.PYTHON -> setOf(
      "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class",
      "continue", "def", "del", "elif", "else", "except", "finally", "for", "from", "global",
      "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise", "return",
      "try", "while", "with", "yield",
    )
    SdTargetLanguage.KOTLIN -> setOf(
      "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
      "interface", "is", "null", "object", "package", "return", "super", "this", "throw", "true",
      "try", "typealias", "typeof", "val", "var", "when", "while",
    )
  }

  fun generate(program: ProgramNode): String {
    validateProgram(program)
    userFunctionNames = (program.functions.map { it.name } + program.voids.map { it.name }).toSet()
    functionReturnTypes = buildMap {
      program.functions.forEach { put(it.name, it.returnTypeName) }
      program.voids.forEach { put(it.name, "void") }
    }
    when (target) {
      SdTargetLanguage.JAVASCRIPT -> {
        out.appendLine("\"use strict\";")
        out.appendLine("function sdPrint(value) { console.log(typeof value === \"boolean\" ? String(value).toLowerCase() : value); }")
        out.appendLine("function sdDiv(a, b) { if (b === 0) throw new Error(\"division by zero\"); return Number.isInteger(a) && Number.isInteger(b) ? Math.trunc(a / b) : a / b; }")
        out.appendLine()
      }
      SdTargetLanguage.PYTHON -> {
        if (program.imports.any { it.name == "math" }) out.appendLine("import math")
        out.appendLine()
        out.appendLine("def sd_print(value):")
        out.appendLine("    if isinstance(value, bool):")
        out.appendLine("        value = str(value).lower()")
        out.appendLine("    print(value)")
        out.appendLine()
        out.appendLine("def sd_div(a, b):")
        out.appendLine("    if isinstance(a, int) and isinstance(b, int):")
        out.appendLine("        return (abs(a) // abs(b)) * (-1 if (a < 0) != (b < 0) else 1)")
        out.appendLine("    return a / b")
        out.appendLine()
        out.appendLine("def sd_mod(a, b):")
        out.appendLine("    if isinstance(a, int) and isinstance(b, int):")
        out.appendLine("        return a - sd_div(a, b) * b")
        out.appendLine("    raise TypeError(\"Sd % requires integers\")")
        out.appendLine()
      }
      SdTargetLanguage.KOTLIN -> {
        out.appendLine("import kotlin.math.*")
        out.appendLine()
        out.appendLine("private fun sdPrint(value: Any?) = println(if (value is Boolean) value.toString().lowercase() else value)")
        out.appendLine("private fun sdDiv(a: Long, b: Long): Long { require(b != 0L) { \"division by zero\" }; return a / b }")
        out.appendLine("private fun sdDiv(a: Double, b: Double): Double { require(b != 0.0) { \"division by zero\" }; return a / b }")
        out.appendLine("private fun sdDiv(a: Long, b: Double): Double { require(b != 0.0) { \"division by zero\" }; return a.toDouble() / b }")
        out.appendLine("private fun sdDiv(a: Double, b: Long): Double { require(b != 0L) { \"division by zero\" }; return a / b.toDouble() }")
        out.appendLine()
      }
      SdTargetLanguage.C99 -> error("C99 is delegated to SdCToTranspiler")
    }

    program.functions.forEach { emitFunction(it.name, it.params, it.returnTypeName, it.body) }
    program.voids.forEach { emitFunction(it.name, it.params, "void", it.body) }
    emitEntry(program.mainBody)

    return out.toString()
  }

  private fun validateProgram(program: ProgramNode) {
    if (program.globals.isNotEmpty()) unsupported("biến toàn cục")
    if (program.structs.isNotEmpty()) unsupported("struct")
    if (program.aacMilestones.isNotEmpty()) unsupported("AaC")
    if (program.defks.isNotEmpty()) unsupported("defk")
    if (program.defineRenames.isNotEmpty()) unsupported("đổi tên cú pháp bằng define")
    val unsupportedImports = program.imports.map { it.name }.filterNot { it in setOf("ioprt", "math", "str") }
    if (unsupportedImports.isNotEmpty()) {
      unsupported("thư viện ${unsupportedImports.distinct().joinToString()}")
    }
    (program.functions.map { it.params to it.returnTypeName } +
      program.voids.map { it.params to "void" }).forEach { (params, returnType) ->
      params.forEach { validateType(it.typeName, it.ptrElemTypeName, it.arraySize) }
      validateType(returnType, null, null)
    }
    program.functions.forEach { validateStatements(it.body) }
    program.voids.forEach { validateStatements(it.body) }
    validateStatements(program.mainBody)
  }

  private fun validateType(typeName: String, ptrElemTypeName: String?, arraySize: Int?) {
    if (ptrElemTypeName != null || arraySize != null ||
      typeName !in setOf("int", "float", "str", "bool", "void")
    ) {
      unsupported("kiểu dữ liệu '$typeName' (chỉ hỗ trợ kiểu vô hướng)")
    }
  }

  private fun validateStatements(statements: List<Stmt>) {
    statements.forEach { statement ->
      when (statement) {
        is Stmt.VarDeclStmt -> validateType(statement.typeName, statement.ptrElemTypeName, statement.arraySize)
        is Stmt.AssignStmt -> {
          if (statement.target !is Expr.VarExpr) unsupported("gán vào trường, mảng hoặc con trỏ")
          validateExpr(statement.value)
        }
        is Stmt.IncDecStmt -> validateExpr(statement.target)
        is Stmt.PriStmt -> validateExpr(statement.expr)
        is Stmt.RefStmt -> validateExpr(statement.value)
        is Stmt.CallStmt -> validateExpr(statement.call)
        is Stmt.BoleanStmt -> statement.branches.forEach { branch ->
          branch.condition?.let(::validateExpr)
          validateStatements(branch.body)
        }
        is Stmt.ForStmt -> {
          if (statement.initVarType != null) validateType(statement.initVarType, null, null)
          validateExpr(statement.initExpr)
          validateExpr(statement.condition)
          validateStatements(statement.body)
          if (statement.stepStmt !is Stmt.AssignStmt && statement.stepStmt !is Stmt.IncDecStmt) {
            unsupported("bước vòng lặp for")
          }
          validateStatements(listOf(statement.stepStmt))
          if (target != SdTargetLanguage.JAVASCRIPT && containsNext(statement.body)) {
            unsupported("nxt bên trong vòng for")
          }
        }
        is Stmt.WhileStmt -> {
          validateExpr(statement.condition)
          validateStatements(statement.body)
        }
        is Stmt.BrkStmt, is Stmt.NxtStmt -> Unit
        is Stmt.BlockScopeStmt -> validateStatements(statement.body)
        is Stmt.IptStmt -> unsupported("nhập liệu ipt")
        is Stmt.FixedAddrSimStmt -> unsupported("địa chỉ mô phỏng")
        is Stmt.SbStmt -> unsupported("khối ids sb")
        is Stmt.SbFlushStmt -> unsupported("reback_flush")
        is Stmt.HardHaltStmt -> unsupported("Hard Halt")
        is Stmt.FreeStmt -> unsupported("quản lý con trỏ/heap thủ công")
        is Stmt.RegLoadStmt, is Stmt.RegPushNullifyStmt -> unsupported("thanh ghi mô phỏng")
        is Stmt.AaCEnterStmt -> unsupported("AaC")
        is Stmt.InitFlagStmt -> unsupported("cờ khởi tạo")
      }
    }
  }

  private fun containsNext(statements: List<Stmt>): Boolean = statements.any { statement ->
    when (statement) {
      is Stmt.NxtStmt -> true
      is Stmt.BoleanStmt -> statement.branches.any { containsNext(it.body) }
      is Stmt.ForStmt -> containsNext(statement.body)
      is Stmt.WhileStmt -> containsNext(statement.body)
      is Stmt.BlockScopeStmt -> containsNext(statement.body)
      else -> false
    }
  }

  private fun validateExpr(expr: Expr) {
    when (expr) {
      is Expr.IntLit, is Expr.FloatLit, is Expr.StrLit, is Expr.BoolLit, is Expr.VarExpr -> Unit
      is Expr.BinaryExpr -> {
        validateExpr(expr.left)
        validateExpr(expr.right)
      }
      is Expr.UnaryExpr -> validateExpr(expr.operand)
      is Expr.CallExpr -> {
        if (expr.module !in setOf(null, "math", "str")) unsupported("hàm ${expr.module}.${expr.name}")
        expr.args.forEach(::validateExpr)
      }
      is Expr.GlobalVarExpr -> unsupported("biến toàn cục")
      is Expr.RegExpr -> unsupported("thanh ghi mô phỏng")
      is Expr.ArrayLit -> unsupported("mảng")
      is Expr.MemberExpr -> unsupported("truy cập trường struct")
      is Expr.IndexExpr -> unsupported("mảng")
      is Expr.AddrOfExpr, is Expr.DerefExpr, is Expr.BorrowDerefExpr -> unsupported("con trỏ")
    }
  }

  private fun emitFunction(name: String, params: List<ParamDecl>, returnType: String, body: List<Stmt>) {
    val paramTypes = params.associate { it.name to it.typeName }
    scopes.addLast(paramTypes)
    val arguments = params.joinToString(", ") { parameter ->
      val argName = nameOf(parameter.name)
      if (target == SdTargetLanguage.KOTLIN) "$argName: ${kotlinType(parameter.typeName)}" else argName
    }
    when (target) {
      SdTargetLanguage.JAVASCRIPT -> out.appendLine("function ${functionNameOf(name)}($arguments) {")
      SdTargetLanguage.PYTHON -> out.appendLine("def ${functionNameOf(name)}($arguments):")
      SdTargetLanguage.KOTLIN -> {
        val resultType = if (returnType == "void") "" else ": ${kotlinType(returnType)}"
        out.appendLine("fun ${functionNameOf(name)}($arguments)$resultType {")
      }
      SdTargetLanguage.C99 -> error("C99 is delegated to SdCToTranspiler")
    }
    indent++
    if (target == SdTargetLanguage.PYTHON && body.isEmpty()) line("pass")
    emitStatements(body)
    indent--
    line(if (target == SdTargetLanguage.PYTHON) "" else "}")
    out.appendLine()
    scopes.removeLast()
  }

  private fun emitEntry(body: List<Stmt>) {
    inMain = true
    scopes.addLast(emptyMap())
    when (target) {
      SdTargetLanguage.JAVASCRIPT -> out.appendLine("function main() {")
      SdTargetLanguage.PYTHON -> out.appendLine("def main():")
      SdTargetLanguage.KOTLIN -> out.appendLine("fun main() {")
      SdTargetLanguage.C99 -> error("C99 is delegated to SdCToTranspiler")
    }
    indent++
    if (body.isEmpty()) line(if (target == SdTargetLanguage.PYTHON) "pass" else "")
    emitStatements(body)
    if (body.none { it is Stmt.RefStmt }) {
      line(
        when (target) {
          SdTargetLanguage.JAVASCRIPT, SdTargetLanguage.PYTHON -> "return 0"
          SdTargetLanguage.KOTLIN -> "return"
          SdTargetLanguage.C99 -> error("C99 is delegated to SdCToTranspiler")
        },
      )
    }
    indent--
    line(if (target == SdTargetLanguage.PYTHON) "" else "}")
    when (target) {
      SdTargetLanguage.JAVASCRIPT -> {
        out.appendLine("const sdExitCode = main();")
        out.appendLine("if (typeof process !== \"undefined\") process.exitCode = Number(sdExitCode ?? 0);")
      }
      SdTargetLanguage.PYTHON -> {
        out.appendLine("if __name__ == \"__main__\":")
        out.appendLine("    raise SystemExit(main())")
      }
      SdTargetLanguage.KOTLIN -> Unit
      SdTargetLanguage.C99 -> error("C99 is delegated to SdCToTranspiler")
    }
    scopes.removeLast()
    inMain = false
  }

  private fun emitStatements(statements: List<Stmt>) {
    statements.forEach(::emitStatement)
  }

  private fun emitStatement(statement: Stmt) {
    when (statement) {
      is Stmt.VarDeclStmt -> {
        val name = nameOf(statement.name)
        val value = statement.initExpr?.let {
          coerceKotlin(it, statement.typeName)
        } ?: defaultValue(statement.typeName)
        bindType(statement.name, statement.typeName)
        when (target) {
          SdTargetLanguage.JAVASCRIPT -> line("let $name = $value;")
          SdTargetLanguage.PYTHON -> line("$name = $value")
          SdTargetLanguage.KOTLIN -> line("var $name: ${kotlinType(statement.typeName)} = $value")
          SdTargetLanguage.C99 -> error("C99 is delegated to SdCToTranspiler")
        }
      }
      is Stmt.AssignStmt -> {
        val lhs = emitExpr(statement.target)
        val rhs = coerceKotlin(statement.value, inferType(statement.target))
        val declaration = statement.allowImplicitDecl && target == SdTargetLanguage.KOTLIN
        if (statement.allowImplicitDecl) bindType(nameOfTarget(statement.target), inferType(statement.value) ?: "int")
        when (target) {
          SdTargetLanguage.JAVASCRIPT ->
            line("${if (statement.allowImplicitDecl) "let " else ""}$lhs = $rhs;")
          SdTargetLanguage.PYTHON -> line("$lhs = $rhs")
          SdTargetLanguage.KOTLIN -> line("${if (declaration) "var " else ""}$lhs = $rhs")
          SdTargetLanguage.C99 -> error("C99 is delegated to SdCToTranspiler")
        }
      }
      is Stmt.IncDecStmt -> {
        val name = emitExpr(statement.target)
        val op = if (statement.isIncrement) "+" else "-"
        line(
          when (target) {
            SdTargetLanguage.JAVASCRIPT -> "$name${if (statement.isIncrement) "++" else "--"};"
            SdTargetLanguage.PYTHON -> "$name $op= 1"
            SdTargetLanguage.KOTLIN -> "$name $op= 1L"
            SdTargetLanguage.C99 -> error("C99 is delegated to SdCToTranspiler")
          },
        )
      }
      is Stmt.PriStmt -> {
        val expression = if (statement.expr is Expr.StrLit) {
          emitFormattedString(statement.expr.value)
        } else {
          emitExpr(statement.expr)
        }
        line(
          when (target) {
            SdTargetLanguage.JAVASCRIPT -> "sdPrint($expression);"
            SdTargetLanguage.PYTHON -> "sd_print($expression)"
            SdTargetLanguage.KOTLIN -> "sdPrint($expression)"
            SdTargetLanguage.C99 -> error("C99 is delegated to SdCToTranspiler")
          },
        )
      }
      is Stmt.CallStmt -> line(
        when (target) {
          SdTargetLanguage.JAVASCRIPT -> "${emitExpr(statement.call)};"
          SdTargetLanguage.PYTHON -> emitExpr(statement.call)
          SdTargetLanguage.KOTLIN -> emitExpr(statement.call)
          SdTargetLanguage.C99 -> error("C99 is delegated to SdCToTranspiler")
        },
      )
      is Stmt.RefStmt -> line(
        if (target == SdTargetLanguage.KOTLIN && inMain) {
          "kotlin.system.exitProcess((${emitExpr(statement.value)}).toInt())"
        } else {
          "return ${emitExpr(statement.value)}${if (target == SdTargetLanguage.JAVASCRIPT) ";" else ""}"
        },
      )
      is Stmt.BoleanStmt -> emitConditional(statement)
      is Stmt.ForStmt -> emitFor(statement)
      is Stmt.WhileStmt -> {
        val condition = emitExpr(statement.condition)
        line(
          when (target) {
            SdTargetLanguage.JAVASCRIPT -> "while ($condition) {"
            SdTargetLanguage.PYTHON -> "while $condition:"
            SdTargetLanguage.KOTLIN -> "while ($condition) {"
            SdTargetLanguage.C99 -> error("C99 is delegated to SdCToTranspiler")
          },
        )
        scopes.addLast(emptyMap())
        indent++
        emitStatements(statement.body)
        if (target == SdTargetLanguage.PYTHON && statement.body.isEmpty()) line("pass")
        indent--
        scopes.removeLast()
        if (target != SdTargetLanguage.PYTHON) line("}")
      }
      is Stmt.BrkStmt -> line(if (target == SdTargetLanguage.PYTHON) "break" else "break;")
      is Stmt.NxtStmt -> line(if (target == SdTargetLanguage.PYTHON) "continue" else "continue;")
      is Stmt.BlockScopeStmt -> {
        if (target != SdTargetLanguage.PYTHON) line("{")
        scopes.addLast(emptyMap())
        indent++
        emitStatements(statement.body)
        if (target == SdTargetLanguage.PYTHON && statement.body.isEmpty()) line("pass")
        indent--
        scopes.removeLast()
        if (target != SdTargetLanguage.PYTHON) line("}")
      }
      else -> unsupported(statement.javaClass.simpleName)
    }
  }

  private fun emitConditional(statement: Stmt.BoleanStmt) {
    statement.branches.forEachIndexed { index, branch ->
      val prefix = when {
        index == 0 -> "if"
        branch.condition != null -> if (target == SdTargetLanguage.PYTHON) "elif" else "else if"
        else -> "else"
      }
      val condition = branch.condition?.let(::emitExpr)
      val header = when (target) {
        SdTargetLanguage.JAVASCRIPT -> if (condition == null) "else {" else "$prefix ($condition) {"
        SdTargetLanguage.PYTHON -> if (condition == null) "else:" else "$prefix $condition:"
        SdTargetLanguage.KOTLIN -> if (condition == null) "else {" else "$prefix ($condition) {"
        SdTargetLanguage.C99 -> error("C99 is delegated to SdCToTranspiler")
      }
      line(header)
      scopes.addLast(emptyMap())
      indent++
      emitStatements(branch.body)
      if (target == SdTargetLanguage.PYTHON && branch.body.isEmpty()) line("pass")
      indent--
      scopes.removeLast()
      if (target != SdTargetLanguage.PYTHON) line("}")
    }
  }

  private fun emitFor(statement: Stmt.ForStmt) {
    val name = nameOf(statement.initVarName)
    val init = emitExpr(statement.initExpr)
    val condition = emitExpr(statement.condition)
    val step = when (val stepStmt = statement.stepStmt) {
      is Stmt.IncDecStmt ->
        "$name${if (stepStmt.isIncrement) "++" else "--"}"
      is Stmt.AssignStmt -> "$name = ${coerceKotlin(stepStmt.value, inferType(stepStmt.target))}"
      else -> unsupported("bước vòng lặp for")
    }
    val type = statement.initVarType
    if (type != null) scopes.addLast(mapOf(statement.initVarName to type))
    when (target) {
      SdTargetLanguage.JAVASCRIPT -> {
        val declaration = if (type != null) "let " else ""
        line("for ($declaration$name = $init; $condition; $step) {")
        indent++
        emitStatements(statement.body)
        indent--
        line("}")
      }
      SdTargetLanguage.PYTHON, SdTargetLanguage.KOTLIN -> {
        if (target == SdTargetLanguage.PYTHON) {
          line("$name = $init")
          line("while $condition:")
        } else {
          val declaration = if (type != null) "var " else ""
          val typeAnnotation = if (type != null) ": ${kotlinType(type)}" else ""
          line("$declaration$name$typeAnnotation = $init")
          line("while ($condition) {")
        }
        scopes.addLast(emptyMap())
        indent++
        emitStatements(statement.body)
        when (val stepStmt = statement.stepStmt) {
          is Stmt.IncDecStmt -> {
            val op = if (stepStmt.isIncrement) "+" else "-"
            line(if (target == SdTargetLanguage.PYTHON) "$name $op= 1" else "$name $op= 1L")
          }
          is Stmt.AssignStmt -> {
            val rhs = coerceKotlin(stepStmt.value, inferType(stepStmt.target))
            line(if (target == SdTargetLanguage.PYTHON) "$name = $rhs" else "$name = $rhs")
          }
          else -> Unit
        }
        indent--
        scopes.removeLast()
        if (target == SdTargetLanguage.KOTLIN) line("}")
      }
      SdTargetLanguage.C99 -> error("C99 is delegated to SdCToTranspiler")
    }
    if (type != null) scopes.removeLast()
  }

  private fun emitExpr(expr: Expr): String = when (expr) {
    is Expr.IntLit -> if (target == SdTargetLanguage.KOTLIN) "${expr.value}L" else expr.value.toString()
    is Expr.FloatLit -> expr.value.toString()
    is Expr.StrLit -> quote(expr.value)
    is Expr.BoolLit -> expr.value.toString()
    is Expr.VarExpr -> nameOf(expr.name)
    is Expr.UnaryExpr -> {
      val op = when {
        expr.op != "not" -> expr.op
        target == SdTargetLanguage.PYTHON -> "not "
        else -> "!"
      }
      "($op${emitExpr(expr.operand)})"
    }
    is Expr.BinaryExpr -> {
      val op = when (expr.op) {
        "and" -> if (target == SdTargetLanguage.PYTHON) "and" else "&&"
        "or" -> if (target == SdTargetLanguage.PYTHON) "or" else "||"
        else -> " ${expr.op} "
      }
      val resultType = inferType(expr)
      val left = coerceKotlin(expr.left, resultType)
      val right = coerceKotlin(expr.right, resultType)
      when {
        target == SdTargetLanguage.PYTHON && expr.op == "/" -> "sd_div($left, $right)"
        target == SdTargetLanguage.PYTHON && expr.op == "%" -> "sd_mod($left, $right)"
        target == SdTargetLanguage.JAVASCRIPT && expr.op == "/" -> "sdDiv($left, $right)"
        target == SdTargetLanguage.KOTLIN && expr.op == "/" -> "sdDiv($left, $right)"
        else -> "($left$op$right)"
      }
    }
    is Expr.CallExpr -> emitCall(expr)
    else -> unsupported("biểu thức ${expr.javaClass.simpleName}")
  }

  private fun emitCall(call: Expr.CallExpr): String {
    val args = call.args.map(::emitExpr)
    val first = args.firstOrNull().orEmpty()
    val second = args.getOrNull(1).orEmpty()
    return when (call.module) {
      "math" -> when (call.name) {
        "abs" -> when (target) {
          SdTargetLanguage.JAVASCRIPT -> "Math.abs($first)"
          SdTargetLanguage.PYTHON -> "abs($first)"
          SdTargetLanguage.KOTLIN -> "abs($first)"
          SdTargetLanguage.C99 -> error("C99 is delegated to SdCToTranspiler")
        }
        "sqrt", "pow" -> when (target) {
          SdTargetLanguage.JAVASCRIPT -> "Math.${call.name}(${args.joinToString(", ")})"
          SdTargetLanguage.PYTHON -> "math.${call.name}(${args.joinToString(", ")})"
          SdTargetLanguage.KOTLIN -> if (call.name == "sqrt") {
            "sqrt(($first).toDouble())"
          } else {
            "($first).toDouble().pow(($second).toDouble())"
          }
          SdTargetLanguage.C99 -> error("C99 is delegated to SdCToTranspiler")
        }
        "min", "max" -> when (target) {
          SdTargetLanguage.JAVASCRIPT -> "Math.${call.name}(${args.joinToString(", ")})"
          SdTargetLanguage.PYTHON, SdTargetLanguage.KOTLIN -> "${call.name}(${args.joinToString(", ")})"
          SdTargetLanguage.C99 -> error("C99 is delegated to SdCToTranspiler")
        }
        else -> unsupported("math.${call.name}")
      }
      "str" -> when (call.name) {
        "len" -> when (target) {
          SdTargetLanguage.JAVASCRIPT -> "($first).length"
          SdTargetLanguage.PYTHON -> "len($first)"
          SdTargetLanguage.KOTLIN -> "($first).length.toLong()"
          SdTargetLanguage.C99 -> error("C99 is delegated to SdCToTranspiler")
        }
        "upper", "lower" -> {
          val method = when (target) {
            SdTargetLanguage.JAVASCRIPT -> if (call.name == "upper") "toUpperCase" else "toLowerCase"
            SdTargetLanguage.PYTHON -> call.name
            SdTargetLanguage.KOTLIN -> if (call.name == "upper") "uppercase" else "lowercase"
            SdTargetLanguage.C99 -> error("C99 is delegated to SdCToTranspiler")
          }
          "($first).$method()"
        }
        "concat" -> "(${args.joinToString(" + ")})"
        else -> unsupported("str.${call.name}")
      }
      null -> "${if (call.name in userFunctionNames) functionNameOf(call.name) else nameOf(call.name)}(${args.joinToString(", ")})"
      else -> unsupported("hàm ${call.module}.${call.name}")
    }
  }

  private data class TemplatePart(val literal: String? = null, val expression: String? = null, val format: String? = null)

  private fun emitFormattedString(template: String): String {
    val parts = parseTemplate(template)
    if (parts.none { it.expression != null }) return quote(template)
    val expressions = parts.mapNotNull { part ->
      when {
        part.literal != null && part.literal.isNotEmpty() -> quote(part.literal)
        part.expression != null -> formatExpression(part.expression, part.format)
        else -> null
      }
    }
    return expressions.ifEmpty { listOf(quote("")) }.joinToString(" + ", "(", ")")
  }

  private fun parseTemplate(template: String): List<TemplatePart> {
    val parts = mutableListOf<TemplatePart>()
    var cursor = 0
    var literalStart = 0
    while (cursor < template.length) {
      val spec = when {
        template.startsWith("/f3{", cursor) -> "f3" to 4
        template.startsWith("/d{", cursor) -> "d" to 3
        template.startsWith("/s{", cursor) -> "s" to 3
        template.startsWith("/f{", cursor) -> "f" to 3
        template.startsWith("/b{", cursor) -> "b" to 3
        else -> null
      }
      if (spec == null) {
        cursor++
        continue
      }
      val openingBrace = cursor + spec.second - 1
      var depth = 1
      var end = openingBrace + 1
      while (end < template.length && depth > 0) {
        when (template[end]) {
          '{' -> depth++
          '}' -> depth--
        }
        end++
      }
      if (depth != 0) {
        cursor++
        continue
      }
      if (cursor > literalStart) parts += TemplatePart(literal = template.substring(literalStart, cursor))
      parts += TemplatePart(
        expression = template.substring(openingBrace + 1, end - 1),
        format = spec.first,
      )
      cursor = end
      literalStart = end
    }
    if (literalStart < template.length) parts += TemplatePart(literal = template.substring(literalStart))
    return parts
  }

  private fun formatExpression(rawExpression: String, format: String?): String {
    val expression = emitTemplateReference(rawExpression)
    return when (format) {
      "d" -> when (target) {
        SdTargetLanguage.JAVASCRIPT -> "String(Math.trunc($expression))"
        SdTargetLanguage.PYTHON -> "str(int($expression))"
        SdTargetLanguage.KOTLIN -> "($expression).toLong().toString()"
        SdTargetLanguage.C99 -> error("C99 is delegated to SdCToTranspiler")
      }
      "s" -> when (target) {
        SdTargetLanguage.JAVASCRIPT -> "String($expression)"
        SdTargetLanguage.PYTHON -> "str($expression)"
        SdTargetLanguage.KOTLIN -> "($expression).toString()"
        SdTargetLanguage.C99 -> error("C99 is delegated to SdCToTranspiler")
      }
      "b" -> when (target) {
        SdTargetLanguage.JAVASCRIPT -> "String($expression).toLowerCase()"
        SdTargetLanguage.PYTHON -> "str($expression).lower()"
        SdTargetLanguage.KOTLIN -> "($expression).toString().lowercase()"
        SdTargetLanguage.C99 -> error("C99 is delegated to SdCToTranspiler")
      }
      "f", "f3" -> {
        val precision = if (format == "f3") 3 else 2
        when (target) {
          SdTargetLanguage.JAVASCRIPT -> "Number($expression).toFixed($precision)"
          SdTargetLanguage.PYTHON -> "\"%.$precision" + "f\" % ($expression)"
          SdTargetLanguage.KOTLIN -> "String.format(java.util.Locale.US, \"%.$precision" + "f\", ($expression).toDouble())"
          SdTargetLanguage.C99 -> error("C99 is delegated to SdCToTranspiler")
        }
      }
      else -> when (target) {
        SdTargetLanguage.JAVASCRIPT -> "String($expression)"
        SdTargetLanguage.PYTHON -> "str($expression)"
        SdTargetLanguage.KOTLIN -> "($expression).toString()"
        SdTargetLanguage.C99 -> error("C99 is delegated to SdCToTranspiler")
      }
    }
  }

  private fun emitTemplateReference(raw: String): String {
    if (!Regex("""[A-Za-z_]\w*(?:\.[A-Za-z_]\w*|\[\d+])*(?:\s*[+\-*/]\s*\d+)?""").matches(raw)) {
      unsupported("biểu thức định dạng chuỗi '$raw'")
    }
    val root = Regex("""^[A-Za-z_]\w*""").find(raw)?.value ?: unsupported("biểu thức định dạng chuỗi '$raw'")
    return nameOf(root) + raw.removePrefix(root)
  }

  private fun inferType(expr: Expr): String? = when (expr) {
    is Expr.IntLit -> "int"
    is Expr.FloatLit -> "float"
    is Expr.StrLit -> "str"
    is Expr.BoolLit -> "bool"
    is Expr.VarExpr -> scopes.toList().asReversed().firstNotNullOfOrNull { it[expr.name] }
    is Expr.UnaryExpr -> if (expr.op == "not") "bool" else inferType(expr.operand)
    is Expr.BinaryExpr -> when {
      expr.op in setOf("and", "or", "==", "!=", "<", "<=", ">", ">=") -> "bool"
      expr.op == "+" && (inferType(expr.left) == "str" || inferType(expr.right) == "str") -> "str"
      inferType(expr.left) == "float" || inferType(expr.right) == "float" -> "float"
      else -> "int"
    }
    is Expr.CallExpr -> when {
      expr.module == "str" && expr.name == "len" -> "int"
      expr.module == "str" -> "str"
      expr.module == "math" && expr.name in setOf("sqrt", "pow") -> "float"
      expr.module == "math" && expr.name == "abs" -> expr.args.firstOrNull()?.let(::inferType)
      expr.module == "math" && expr.name in setOf("min", "max") ->
        if (expr.args.any { inferType(it) == "float" }) "float" else "int"
      expr.module == null -> functionReturnTypes[expr.name]
      else -> null
    }
    else -> null
  }

  private fun coerceKotlin(expr: Expr, targetType: String?): String {
    val code = emitExpr(expr)
    return if (
      target == SdTargetLanguage.KOTLIN &&
      targetType == "float" &&
      inferType(expr) == "int"
    ) {
      "($code).toDouble()"
    } else {
      code
    }
  }

  private fun bindType(name: String, type: String?) {
    val scope = scopes.lastOrNull() ?: return
    val updated = scope.toMutableMap()
    if (type != null) updated[name] = type
    scopes.removeLast()
    scopes.addLast(updated)
  }

  private fun nameOfTarget(targetExpr: Expr): String =
    (targetExpr as? Expr.VarExpr)?.name ?: unsupported("gán vào tên biến không hợp lệ")

  private fun functionNameOf(name: String): String = "sd_fn_${nameOf(name)}"

  private fun defaultValue(typeName: String): String = when (typeName) {
    "int" -> if (target == SdTargetLanguage.KOTLIN) "0L" else "0"
    "float" -> "0.0"
    "str" -> quote("")
    "bool" -> "false"
    else -> unsupported("kiểu '$typeName'")
  }

  private fun kotlinType(typeName: String): String = when (typeName) {
    "int" -> "Long"
    "float" -> "Double"
    "str" -> "String"
    "bool" -> "Boolean"
    "void" -> "Unit"
    else -> unsupported("kiểu '$typeName'")
  }

  private fun nameOf(name: String): String = if (name in keywordNames) "_sd_$name" else name

  private fun quote(value: String): String {
    val escaped = buildString {
      value.forEach { ch ->
        when (ch) {
          '\\' -> append("\\\\")
          '"' -> append("\\\"")
          '\n' -> append("\\n")
          '\r' -> append("\\r")
          '\t' -> append("\\t")
          else -> append(ch)
        }
      }
    }
    return "\"$escaped\""
  }

  private fun line(text: String) {
    if (target == SdTargetLanguage.PYTHON) {
      if (text.isNotEmpty()) out.append("    ".repeat(indent)).append(text)
      out.appendLine()
    } else {
      if (text.isNotEmpty()) out.append("    ".repeat(indent)).append(text)
      out.appendLine()
    }
  }

  private fun unsupported(feature: String): Nothing =
    throw SdBackendGenerationException("${target.displayName} hiện chưa hỗ trợ $feature trong Sd/Sd++.")
}

data class SdOptimizationChange(
  val line: Int,
  val before: String,
  val after: String,
  val explanation: String,
)

data class SdOptimizationResult(
  val optimizedSource: String,
  val changes: List<SdOptimizationChange>,
) {
  val changed: Boolean get() = changes.isNotEmpty()
}

object SdSourceOptimizer {
  private val intDeclaration = Regex("""(?:sta\(\s*int\s+|iptedt\.int\s+)([A-Za-z_]\w*)\b""")
  private val identityAssignment = Regex(
    """^(\s*)iid:\s*sta\(\s*([A-Za-z_]\w*)\s*=\s*\2\s*([+\-*/])\s*(0|1)\s*\)\s*ed\s*$""",
  )

  fun optimize(source: String): SdOptimizationResult {
    val lines = source.split('\n').toMutableList()
    val declaredInts = mutableSetOf<String>()
    val changes = mutableListOf<SdOptimizationChange>()
    lines.forEachIndexed { index, line ->
      val match = identityAssignment.matchEntire(line)
      val isIdentity = match?.let { groups ->
        groups.groupValues[3] in setOf("+", "-") && groups.groupValues[4] == "0" ||
          groups.groupValues[3] in setOf("*", "/") && groups.groupValues[4] == "1"
      } == true
      if (match != null && isIdentity && match.groupValues[2] in declaredInts) {
        val name = match.groupValues[2]
        changes += SdOptimizationChange(
          line = index + 1,
          before = line,
          after = "",
          explanation = "Bỏ phép gán đồng nhất int '$name ${match.groupValues[3]} ${match.groupValues[4]}'.",
        )
        lines[index] = ""
      }
      intDeclaration.findAll(line).forEach { declaredInts += it.groupValues[1] }
    }
    return SdOptimizationResult(lines.joinToString("\n"), changes)
  }
}
