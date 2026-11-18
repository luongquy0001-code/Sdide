package com.example.sdide.engine

internal class SdVirtualMachine(
  private val debugController: DebugController?,
  private val mainFileName: String?,
) {
  companion object {
    private const val MAX_VM_INSTRUCTIONS = SdInterpreter.MAX_STEPS * 16
  }

  private val output = StringBuilder()

  fun currentOutput(): String = output.toString()

  fun execute(program: SdVmProgram): SdInterpreter.ExecutionOutcome {
    val stack = LongArray(maxOf(16, program.instructions.size + 1))
    val locals = LongArray(program.slots.size)
    var stackSize = 0
    var instructionPointer = 0
    var executedInstructions = 0L
    var executedStatements = 0L
    var exitCode = 0L
    var currentLine = 1
    var terminatedByUser = false

    while (instructionPointer in program.instructions.indices) {
      val instruction = program.instructions[instructionPointer]
      executedInstructions++
      if (executedInstructions > MAX_VM_INSTRUCTIONS) {
        throw runtimeError(
          "E501",
          instruction.pos,
          "vượt quá giới hạn $MAX_VM_INSTRUCTIONS lệnh bytecode",
        )
      }
      if (executedInstructions % 1024L == 0L && debugController?.cancelled == true) {
        terminatedByUser = true
        break
      }

      when (instruction.op) {
        SdVmOp.CHECK_STEP -> {
          executedStatements++
          currentLine = instruction.pos.line
          if (executedStatements > SdInterpreter.MAX_STEPS) {
            throw runtimeError(
              "E501",
              instruction.pos,
              "vượt quá giới hạn ${SdInterpreter.MAX_STEPS} bước thực thi",
            )
          }
          if (debugController?.cancelled == true) {
            terminatedByUser = true
            break
          }
        }
        SdVmOp.PUSH_INT -> {
          stack[stackSize++] = SdTypeUtils.checkSafeInt(
            instruction.longOperand,
            instruction.pos.line,
            instruction.pos.col,
            instruction.pos.fileName,
          )
        }
        SdVmOp.PUSH_BOOL -> stack[stackSize++] = instruction.longOperand
        SdVmOp.LOAD -> stack[stackSize++] = locals[instruction.intOperand]
        SdVmOp.STORE -> locals[instruction.intOperand] = stack[--stackSize]
        SdVmOp.ADD,
        SdVmOp.SUB,
        SdVmOp.MUL,
        SdVmOp.DIV,
        SdVmOp.MOD,
        SdVmOp.EQ,
        SdVmOp.NE,
        SdVmOp.LT,
        SdVmOp.LE,
        SdVmOp.GT,
        SdVmOp.GE,
        -> {
          val right = stack[--stackSize]
          val left = stack[--stackSize]
          stack[stackSize++] = when (instruction.op) {
            SdVmOp.ADD -> checkedInt(left + right, instruction.pos)
            SdVmOp.SUB -> checkedInt(left - right, instruction.pos)
            SdVmOp.MUL -> checkedInt(left * right, instruction.pos)
            SdVmOp.DIV -> {
              if (right == 0L) throw runtimeError("E401", instruction.pos, "chia cho 0")
              checkedInt(left / right, instruction.pos)
            }
            SdVmOp.MOD -> {
              if (right == 0L) throw runtimeError("E401", instruction.pos, "chia dư % cho 0")
              checkedInt(left % right, instruction.pos)
            }
            SdVmOp.EQ -> if (left == right) 1L else 0L
            SdVmOp.NE -> if (left != right) 1L else 0L
            SdVmOp.LT -> if (left < right) 1L else 0L
            SdVmOp.LE -> if (left <= right) 1L else 0L
            SdVmOp.GT -> if (left > right) 1L else 0L
            SdVmOp.GE -> if (left >= right) 1L else 0L
            else -> error("Unreachable VM arithmetic operation")
          }
        }
        SdVmOp.NEG -> {
          stack[stackSize - 1] = checkedInt(-stack[stackSize - 1], instruction.pos)
        }
        SdVmOp.NOT -> {
          stack[stackSize - 1] = if (stack[stackSize - 1] == 0L) 1L else 0L
        }
        SdVmOp.JUMP -> {
          instructionPointer = instruction.intOperand
          continue
        }
        SdVmOp.JUMP_IF_FALSE -> {
          if (stack[--stackSize] == 0L) {
            instructionPointer = instruction.intOperand
            continue
          }
        }
        SdVmOp.JUMP_IF_TRUE -> {
          if (stack[--stackSize] != 0L) {
            instructionPointer = instruction.intOperand
            continue
          }
        }
        SdVmOp.PRINT_TEXT -> appendOutput(instruction.textOperand.orEmpty(), instruction.pos)
        SdVmOp.PRINT_VALUE -> {
          val value = stack[--stackSize]
          val text = if (instruction.intOperand == SdVmValueKind.BOOL.ordinal) {
            if (value == 0L) "false" else "true"
          } else {
            value.toString()
          }
          appendOutput(text, instruction.pos)
        }
        SdVmOp.PRINT_NEWLINE -> appendOutput("\n", instruction.pos)
        SdVmOp.RETURN -> {
          exitCode = stack[--stackSize]
          break
        }
        SdVmOp.HALT -> break
      }
      instructionPointer++
    }

    val visibleLocals = program.slots.indices
      .filter { program.slots[it].visibleAtEnd }
      .map { index ->
        val slot = program.slots[index]
        VarSnapshot(slot.name, slot.kind.name.lowercase(), locals[index].toString())
      }
    val snapshot = DebugSnapshot(
      line = currentLine,
      fileName = mainFileName,
      callStack = listOf(CallFrameInfo("main", currentLine, visibleLocals)),
      globals = emptyList(),
      heapBlocks = emptyList(),
      registers = emptyList(),
    )
    return SdInterpreter.ExecutionOutcome(
      output = output.toString(),
      exitCode = exitCode,
      warnings = emptyList(),
      finalSnapshot = snapshot,
      drawCommands = emptyList(),
      terminatedByUser = terminatedByUser,
    )
  }

  private fun checkedInt(value: Long, pos: SourcePos): Long =
    SdTypeUtils.checkSafeInt(value, pos.line, pos.col, pos.fileName)

  private fun appendOutput(text: String, pos: SourcePos) {
    if (output.length + text.length > SdInterpreter.MAX_OUTPUT_CHARS) {
      throw runtimeError(
        "E501",
        pos,
        "vượt quá giới hạn ${SdInterpreter.MAX_OUTPUT_CHARS} ký tự đầu ra",
      )
    }
    output.append(text)
    debugController?.onOutputChunk(text)
  }

  private fun runtimeError(code: String, pos: SourcePos, message: String) =
    SdRuntimeException(SdDiagnostic(code, 4, pos.line, pos.col, pos.fileName, message))
}
