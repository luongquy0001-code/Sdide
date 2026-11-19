package com.example.sdide.engine

/**
 * Generates equivalent C99 code matching the `sdc -S` transpiler specification in Chapter 20.
 */
class SdCToTranspiler {

  fun transpile(unit: SdCompilationUnit): String {
    val sb = StringBuilder()
    sb.appendLine("/* Sinh tu dong boi sdc -S (Chuan Sd / Sd++ -> C99) */")
    sb.appendLine("#include <stdio.h>")
    sb.appendLine("#include <stdlib.h>")
    sb.appendLine("#include <stdint.h>")
    sb.appendLine("#include <stdbool.h>")
    sb.appendLine("#include <string.h>")
    sb.appendLine("#include <math.h>")
    sb.appendLine()
    sb.appendLine("#define SD_SAFE_MAX (1LL << 53)")
    sb.appendLine("static inline int64_t sd_check_overflow(int64_t v) {")
    sb.appendLine("    if (v > SD_SAFE_MAX || v < -SD_SAFE_MAX) {")
    sb.appendLine("        fprintf(stderr, \"[LOI E405] Tran so nguyen vuot 2^53\\n\");")
    sb.appendLine("        exit(1);")
    sb.appendLine("    }")
    sb.appendLine("    return v;")
    sb.appendLine("}")
    sb.appendLine()

    when (unit) {
      is SdCompilationUnit.SingleProgram -> emitProgram(unit.program, sb)
      is SdCompilationUnit.SdAllProgram -> {
        sb.appendLine("/* Chuong trinh .sdall gom ${unit.compartments.size} ngan */")
        for (c in unit.compartments) {
          sb.appendLine("/* --- Ngan #${c.index} [${c.switch}] --- */")
          if (c.switch != CompartmentSwitch.OFF && c.switch != CompartmentSwitch.PAUSE) {
            emitProgram(c.program, sb, fnSuffix = "_ngan_${c.index}")
          }
        }
        sb.appendLine("int main(void) {")
        for (c in unit.compartments) {
          if (c.switch != CompartmentSwitch.OFF && c.switch != CompartmentSwitch.PAUSE) {
            sb.appendLine("    sd_entry_ngan_${c.index}();")
            if (c.switch == CompartmentSwitch.STOP) break
          }
        }
        sb.appendLine("    return 0;")
        sb.appendLine("}")
      }
    }
    return sb.toString()
  }

  private fun emitProgram(prog: ProgramNode, sb: StringBuilder, fnSuffix: String = "") {
    if (prog.aacMilestones.isNotEmpty() || prog.mainBody.any { it is Stmt.RegLoadStmt }) {
      sb.appendLine("/* Thanh ghi mo phong CPU: 0Ax..15Nx */")
      SdParser.VALID_REGISTERS.forEach { register ->
        sb.appendLine("static int64_t reg_$register = 0;")
      }
      sb.appendLine()
    }

    for (st in prog.structs) {
      sb.appendLine("typedef struct {")
      for (f in st.fields) {
        sb.appendLine("    ${mapCType(f.typeName)} ${f.name};")
      }
      sb.appendLine("} ${st.name};")
      sb.appendLine()
    }

    for (g in prog.globals) {
      val cType = mapCType(g.type?.toString() ?: "int64_t")
      val init = g.initExpr?.let { " = ${emitExpr(it)}" } ?: " = 0"
      sb.appendLine("static $cType g_${g.name}$init;")
    }
    if (prog.globals.isNotEmpty()) sb.appendLine()

    for (fn in prog.functions) {
      val ret = mapCType(fn.returnTypeName)
      val params = fn.params.joinToString(", ") { p ->
        if (p.arraySize != null) "${mapCType(p.typeName)} ${p.name}[${p.arraySize}]"
        else "${mapCType(p.typeName)} ${p.name}"
      }.ifEmpty { "void" }
      sb.appendLine("$ret ${fn.name}($params) {")
      emitStmts(fn.body, sb, indent = "    ", isMain = false)
      sb.appendLine("}")
      sb.appendLine()
    }

    for (vd in prog.voids) {
      val params = vd.params.joinToString(", ") { p ->
        if (p.arraySize != null) "${mapCType(p.typeName)} ${p.name}[${p.arraySize}]"
        else "${mapCType(p.typeName)} ${p.name}"
      }.ifEmpty { "void" }
      sb.appendLine("void ${vd.name}($params) {")
      emitStmts(vd.body, sb, indent = "    ", isMain = false)
      sb.appendLine("}")
      sb.appendLine()
    }

    if (fnSuffix.isEmpty()) {
      sb.appendLine("int main(void) {")
      emitStmts(prog.mainBody, sb, indent = "    ", isMain = true)
      if (prog.mainBody.none { it is Stmt.RefStmt }) {
        sb.appendLine("    return 0;")
      }
      sb.appendLine("}")
    } else {
      sb.appendLine("static int sd_entry$fnSuffix(void) {")
      emitStmts(prog.mainBody, sb, indent = "    ", isMain = true)
      if (prog.mainBody.none { it is Stmt.RefStmt }) {
        sb.appendLine("    return 0;")
      }
      sb.appendLine("}")
      sb.appendLine()
    }
  }

  private fun emitStmts(stmts: List<Stmt>, sb: StringBuilder, indent: String, isMain: Boolean) {
    for (s in stmts) {
      emitStmt(s, sb, indent, isMain)
    }
  }

  private fun emitStmt(s: Stmt, sb: StringBuilder, indent: String, isMain: Boolean) {
    when (s) {
      is Stmt.VarDeclStmt -> {
        val cType = mapCType(s.typeName)
        if (s.arraySize != null) {
          if (s.initExpr is Expr.ArrayLit) {
            val elems = s.initExpr.elements.joinToString(", ") { emitExpr(it) }
            sb.appendLine("$indent$cType ${s.name}[${s.arraySize}] = {$elems};")
          } else {
            sb.appendLine("$indent$cType ${s.name}[${s.arraySize}] = {0};")
          }
        } else {
          val init = s.initExpr?.let { " = ${emitExpr(it)}" } ?: ""
          sb.appendLine("$indent$cType ${s.name}$init;")
        }
      }
      is Stmt.AssignStmt -> {
        val lhs = emitExpr(s.target)
        val rhs = emitExpr(s.value)
        if (s.allowImplicitDecl && s.target is Expr.VarExpr) {
          sb.appendLine("${indent}auto ${lhs} = ${rhs}; /* suy luan *m */")
        } else {
          sb.appendLine("$indent$lhs = $rhs;")
        }
      }
      is Stmt.IncDecStmt -> {
        val op = if (s.isIncrement) "++" else "--"
        sb.appendLine("$indent${emitExpr(s.target)}$op;")
      }
      is Stmt.PriStmt -> {
        if (s.expr is Expr.StrLit) {
          val (fmt, args) = convertPriTemplate(s.expr.value)
          if (args.isEmpty()) {
            sb.appendLine("${indent}printf(\"${escapeC(fmt)}\\n\");")
          } else {
            sb.appendLine("${indent}printf(\"${escapeC(fmt)}\\n\", ${args.joinToString(", ")});")
          }
        } else {
          sb.appendLine("${indent}printf(\"%lld\\n\", (long long)(${emitExpr(s.expr)}));")
        }
      }
      is Stmt.IptStmt -> {
        sb.appendLine("${indent}scanf(\"%lld\", &(${emitExpr(s.target)}));")
      }
      is Stmt.CallStmt -> sb.appendLine("$indent${emitExpr(s.call)};")
      is Stmt.RefStmt -> {
        if (isMain) sb.appendLine("${indent}return (int)(${emitExpr(s.value)});")
        else sb.appendLine("${indent}return ${emitExpr(s.value)};")
      }
      is Stmt.FixedAddrSimStmt -> {
        sb.appendLine("${indent}uintptr_t ${s.name} = (uintptr_t)0x${s.addr.toString(16).uppercase()}; /* mo phong W204 */")
      }
      is Stmt.BrkStmt -> sb.appendLine("${indent}break;")
      is Stmt.NxtStmt -> sb.appendLine("${indent}continue;")
      is Stmt.BoleanStmt -> {
        s.branches.forEachIndexed { idx, b ->
          val header = when {
            idx == 0 -> "if (${emitExpr(b.condition!!)})"
            b.condition != null -> "else if (${emitExpr(b.condition)})"
            else -> "else"
          }
          sb.appendLine("$indent$header {")
          emitStmts(b.body, sb, "$indent    ", isMain)
          sb.appendLine("$indent}")
        }
      }
      is Stmt.ForStmt -> {
        val initPrefix = s.initVarType?.let { "${mapCType(it)} " } ?: ""
        val stepStr = when (val st = s.stepStmt) {
          is Stmt.IncDecStmt -> "${emitExpr(st.target)}${if (st.isIncrement) "++" else "--"}"
          is Stmt.AssignStmt -> "${emitExpr(st.target)} = ${emitExpr(st.value)}"
          else -> ""
        }
        sb.appendLine("${indent}for ($initPrefix${s.initVarName} = ${emitExpr(s.initExpr)}; ${emitExpr(s.condition)}; $stepStr) {")
        emitStmts(s.body, sb, "$indent    ", isMain)
        sb.appendLine("$indent}")
      }
      is Stmt.WhileStmt -> {
        sb.appendLine("${indent}while (${emitExpr(s.condition)}) {")
        emitStmts(s.body, sb, "$indent    ", isMain)
        sb.appendLine("$indent}")
      }
      is Stmt.SbStmt -> {
        sb.appendLine("${indent}/* ids sb(${s.targetVar}) */")
        sb.appendLine("${indent}while (1) {")
        for (b in s.branches) {
          if (b.condition != null) {
            sb.appendLine("$indent    if (${emitExpr(b.condition)}) {")
          } else {
            sb.appendLine("$indent    { /* remaining */")
          }
          emitStmts(b.body, sb, "$indent        ", isMain)
          when (b.action) {
            Stmt.SbAction.REBACK -> sb.appendLine("$indent        continue; /* $$ Reback */")
            Stmt.SbAction.TRANSF -> sb.appendLine("$indent        /* transf $ */")
            Stmt.SbAction.BREAK, Stmt.SbAction.NONE -> sb.appendLine("$indent        break;")
          }
          sb.appendLine("$indent    }")
        }
        sb.appendLine("$indent    break;")
        sb.appendLine("$indent}")
      }
      is Stmt.FreeStmt -> sb.appendLine("${indent}free(${emitExpr(s.ptrExpr)});")
      is Stmt.RegLoadStmt -> sb.appendLine("${indent}reg_${s.regName} = (int64_t)(${emitExpr(s.valueExpr)});")
      is Stmt.RegPushNullifyStmt -> {
        if (s.destVarName != null && s.destVarType != null && s.registers.isNotEmpty()) {
          sb.appendLine("$indent${mapCType(s.destVarType)} ${s.destVarName} = reg_${s.registers.first()};")
        }
        for (r in s.registers) sb.appendLine("${indent}reg_$r = 0;")
      }
      is Stmt.AaCEnterStmt -> sb.appendLine("${indent}/* impt*AaC: quan ly thanh ghi tu dong */")
      is Stmt.HardHaltStmt -> sb.appendLine("${indent}abort(); /* $$ Hard Halt */")
      is Stmt.SbFlushStmt -> sb.appendLine("${indent}/* reback_flush() */")
      is Stmt.InitFlagStmt -> sb.appendLine("${indent}/* init ${s.symbol} */")
      is Stmt.BlockScopeStmt -> emitStmts(s.body, sb, indent, isMain)
    }
  }

  private fun emitExpr(e: Expr): String = when (e) {
    is Expr.IntLit -> "${e.value}LL"
    is Expr.FloatLit -> e.value.toString()
    is Expr.StrLit -> "\"${escapeC(e.value)}\""
    is Expr.BoolLit -> if (e.value) "true" else "false"
    is Expr.ArrayLit -> "{${e.elements.joinToString(", ") { emitExpr(it) }}}"
    is Expr.VarExpr -> e.name
    is Expr.GlobalVarExpr -> "g_${e.name}"
    is Expr.RegExpr -> "reg_${e.regName}"
    is Expr.MemberExpr -> "${emitExpr(e.target)}.${e.field}"
    is Expr.IndexExpr -> "${emitExpr(e.target)}[${emitExpr(e.index)}]"
    is Expr.AddrOfExpr -> "&(${emitExpr(e.target)})"
    is Expr.DerefExpr -> "*(${emitExpr(e.ptrExpr)})"
    is Expr.BorrowDerefExpr -> e.varName
    is Expr.UnaryExpr -> when (e.op) {
      "not" -> "!(${emitExpr(e.operand)})"
      else -> "${e.op}(${emitExpr(e.operand)})"
    }
    is Expr.BinaryExpr -> {
      val op = when (e.op) {
        "and" -> "&&"
        "or" -> "||"
        else -> e.op
      }
      "(${emitExpr(e.left)} $op ${emitExpr(e.right)})"
    }
    is Expr.CallExpr -> {
      val args = e.args.joinToString(", ") { emitExpr(it) }
      when {
        e.module == "sta" && e.name == "alloc" -> "malloc((size_t)($args) * 8)"
        e.module == "math" && e.name == "abs" -> "llabs($args)"
        e.module == "math" -> "${e.name}($args)"
        e.module == "str" && e.name == "len" -> "(int64_t)strlen($args)"
        e.module != null -> "${e.module}_${e.name}($args)"
        else -> "${e.name}($args)"
      }
    }
  }

  private fun mapCType(sdType: String): String = when (sdType) {
    "int" -> "int64_t"
    "float" -> "double"
    "str" -> "const char*"
    "bool" -> "bool"
    "void" -> "void"
    "ptr" -> "int64_t*"
    else -> if (sdType.startsWith("ptr<")) "int64_t*" else sdType
  }

  private fun convertPriTemplate(tpl: String): Pair<String, List<String>> {
    val fmt = StringBuilder()
    val args = mutableListOf<String>()
    var i = 0
    while (i < tpl.length) {
      val spec = when {
        tpl.startsWith("/f3{", i) -> "f3" to "%.3f"
        tpl.startsWith("/d{", i) -> "d" to "%lld"
        tpl.startsWith("/s{", i) -> "s" to "%s"
        tpl.startsWith("/f{", i) -> "f" to "%.2f"
        tpl.startsWith("/b{", i) -> "b" to "%d"
        else -> null
      }
      if (spec != null) {
        val openIdx = i + 1 + spec.first.length
        var depth = 0
        var closeIdx = -1
        for (k in openIdx until tpl.length) {
          if (tpl[k] == '{') depth++
          else if (tpl[k] == '}') {
            depth--
            if (depth == 0) { closeIdx = k; break }
          }
        }
        if (closeIdx > openIdx) {
          val inner = tpl.substring(openIdx + 1, closeIdx)
          fmt.append(spec.second)
          args.add(if (spec.first == "d") "(long long)($inner)" else inner)
          i = closeIdx + 1
          continue
        }
      }
      if (tpl[i] == '%') fmt.append("%%") else fmt.append(tpl[i])
      i++
    }
    return fmt.toString() to args
  }

  private fun escapeC(s: String): String = buildString {
    for (ch in s) {
      when (ch) {
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        '\"' -> append("\\\"")
        '\\' -> append("\\\\")
        else -> append(ch)
      }
    }
  }
}
