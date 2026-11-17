package com.example.sdide.engine

data class SourcePos(
  val line: Int,
  val col: Int,
  val fileName: String? = null,
)

enum class CompartmentSwitch {
  START, ON, PAUSE, OFF, STOP
}

enum class CompartmentKind {
  SD_CORE, SDPP, REG_ARENA
}

sealed class SdCompilationUnit {
  data class SingleProgram(val program: ProgramNode) : SdCompilationUnit()
  data class SdAllProgram(val compartments: List<CompartmentNode>) : SdCompilationUnit()
}

data class CompartmentNode(
  val index: Int,
  val switch: CompartmentSwitch,
  val kind: CompartmentKind,
  val arenaName: String?,
  val program: ProgramNode,
  val pos: SourcePos,
)

data class ProgramNode(
  val isSdpp: Boolean,
  val arenaName: String? = null,
  val imports: List<ImportDecl>,
  val aacMilestones: List<AaCMilestoneNode> = emptyList(),
  val defks: List<DefkDecl> = emptyList(),
  val globals: List<GlobalVarDecl> = emptyList(),
  val structs: List<StructDecl> = emptyList(),
  val functions: List<FnDecl> = emptyList(),
  val voids: List<VoidDecl> = emptyList(),
  val defineRenames: Map<String, String> = emptyMap(), // e.g., "pri" -> "in_ra", "main" -> "main"
  val mainBody: List<Stmt>,
  val mainPos: SourcePos,
  val hasExplicitMain: Boolean = true,
)

data class ImportDecl(
  val name: String,
  val isSdppFlag: Boolean,
  val pos: SourcePos,
)

data class AaCMilestoneNode(
  val isPermanent: Boolean, // .+bin$ vs .bin.ed
  val pos: SourcePos,
)

data class DefkDecl(
  val symbol: String,
  val pos: SourcePos,
)

data class GlobalVarDecl(
  val name: String,
  val type: SdType?, // null when inferred from ids.g:
  val arraySize: Int? = null,
  val initExpr: Expr?,
  val isBorrowed: Boolean = false,
  val pos: SourcePos,
)

data class StructDecl(
  val name: String,
  val fields: List<StructFieldDecl>,
  val pos: SourcePos,
)

data class StructFieldDecl(
  val name: String,
  val typeName: String,
  val pos: SourcePos,
)

data class ParamDecl(
  val name: String,
  val typeName: String,
  val ptrElemTypeName: String? = null,
  val arraySize: Int? = null,
  val pos: SourcePos,
)

data class FnDecl(
  val name: String,
  val params: List<ParamDecl>,
  val returnTypeName: String,
  val returnPtrElemTypeName: String? = null,
  val body: List<Stmt>,
  val pos: SourcePos,
)

data class VoidDecl(
  val name: String,
  val params: List<ParamDecl>,
  val body: List<Stmt>,
  val pos: SourcePos,
)

sealed class Stmt {
  abstract val pos: SourcePos

  data class VarDeclStmt(
    val name: String,
    val typeName: String,
    val ptrElemTypeName: String? = null,
    val arraySize: Int? = null,
    val initExpr: Expr? = null,
    val isBorrowed: Boolean = false,
    val isInferred: Boolean = false,
    override val pos: SourcePos,
  ) : Stmt()

  data class AssignStmt(
    val target: Expr,
    val value: Expr,
    override val pos: SourcePos,
    val allowImplicitDecl: Boolean = false,
  ) : Stmt()

  data class IncDecStmt(
    val target: Expr,
    val isIncrement: Boolean,
    override val pos: SourcePos,
  ) : Stmt()

  data class PriStmt(
    val expr: Expr,
    override val pos: SourcePos,
  ) : Stmt()

  data class IptStmt(
    val target: Expr,
    override val pos: SourcePos,
  ) : Stmt()

  data class CallStmt(
    val call: Expr.CallExpr,
    override val pos: SourcePos,
  ) : Stmt()

  data class RefStmt(
    val value: Expr,
    override val pos: SourcePos,
  ) : Stmt()

  data class FixedAddrSimStmt(
    val name: String,
    val addr: Long,
    override val pos: SourcePos,
  ) : Stmt()

  data class BrkStmt(override val pos: SourcePos) : Stmt()
  data class NxtStmt(override val pos: SourcePos) : Stmt()

  data class BoleanBranch(
    val condition: Expr?, // null for else
    val body: List<Stmt>,
    val pos: SourcePos,
  )

  data class BoleanStmt(
    val branches: List<BoleanBranch>,
    override val pos: SourcePos,
  ) : Stmt()

  data class ForStmt(
    val initVarType: String?,
    val initVarName: String,
    val initExpr: Expr,
    val condition: Expr,
    val stepStmt: Stmt,
    val body: List<Stmt>,
    override val pos: SourcePos,
  ) : Stmt()

  data class WhileStmt(
    val condition: Expr,
    val body: List<Stmt>,
    override val pos: SourcePos,
  ) : Stmt()

  data class SbBranch(
    val condition: Expr?, // null for remaining:
    val body: List<Stmt>,
    val action: SbAction,
    val pos: SourcePos,
  )

  enum class SbAction { TRANSF, BREAK, REBACK, NONE }

  data class SbStmt(
    val targetVar: String,
    val branches: List<SbBranch>,
    override val pos: SourcePos,
  ) : Stmt()

  data class SbFlushStmt(override val pos: SourcePos) : Stmt()

  data class HardHaltStmt(override val pos: SourcePos) : Stmt()

  data class FreeStmt(
    val ptrExpr: Expr,
    override val pos: SourcePos,
  ) : Stmt()

  data class RegLoadStmt(
    val regName: String,
    val valueExpr: Expr,
    override val pos: SourcePos,
  ) : Stmt()

  data class RegPushNullifyStmt(
    val destVarName: String?,
    val destVarType: String?,
    val registers: List<String>,
    override val pos: SourcePos,
  ) : Stmt()

  data class InitFlagStmt(
    val symbol: String,
    override val pos: SourcePos,
  ) : Stmt()

  data class AaCEnterStmt(
    val isPermanent: Boolean,
    override val pos: SourcePos,
  ) : Stmt()

  data class BlockScopeStmt(
    val body: List<Stmt>,
    override val pos: SourcePos,
  ) : Stmt()
}

sealed class Expr {
  abstract val pos: SourcePos

  data class IntLit(val value: Long, override val pos: SourcePos) : Expr()
  data class FloatLit(val value: Double, override val pos: SourcePos) : Expr()
  data class StrLit(
    val value: String,
    val hasLegacyInterpolations: Boolean = false,
    override val pos: SourcePos,
  ) : Expr()
  data class BoolLit(val value: Boolean, override val pos: SourcePos) : Expr()
  data class ArrayLit(val elements: List<Expr>, override val pos: SourcePos) : Expr()

  data class VarExpr(val name: String, override val pos: SourcePos) : Expr()
  data class GlobalVarExpr(val name: String, override val pos: SourcePos) : Expr()
  data class RegExpr(val regName: String, override val pos: SourcePos) : Expr()

  data class MemberExpr(val target: Expr, val field: String, override val pos: SourcePos) : Expr()
  data class IndexExpr(val target: Expr, val index: Expr, override val pos: SourcePos) : Expr()

  data class AddrOfExpr(val target: Expr, override val pos: SourcePos) : Expr()
  data class DerefExpr(val ptrExpr: Expr, override val pos: SourcePos) : Expr()
  data class BorrowDerefExpr(val varName: String, override val pos: SourcePos) : Expr()

  data class UnaryExpr(val op: String, val operand: Expr, override val pos: SourcePos) : Expr()
  data class BinaryExpr(val op: String, val left: Expr, val right: Expr, override val pos: SourcePos) : Expr()

  data class CallExpr(
    val module: String?, // e.g. "math", "str", "file", "mem", "sys", "draw", "sta", or imported file
    val name: String,
    val args: List<Expr>,
    override val pos: SourcePos,
  ) : Expr()
}
