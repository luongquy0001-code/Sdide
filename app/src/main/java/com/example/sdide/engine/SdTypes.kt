package com.example.sdide.engine

import java.util.Locale
import kotlin.math.abs

sealed class SdType {
  data object IntType : SdType() { override fun toString() = "int" }
  data object FloatType : SdType() { override fun toString() = "float" }
  data object StrType : SdType() { override fun toString() = "str" }
  data object BoolType : SdType() { override fun toString() = "bool" }
  data object VoidType : SdType() { override fun toString() = "void" }
  data class PtrType(val elemType: SdType? = null) : SdType() {
    override fun toString() = if (elemType == null) "ptr" else "ptr<$elemType>"
  }
  data class ArrayType(val elemType: SdType, val size: Int) : SdType() {
    override fun toString() = "$elemType[$size]"
  }
  data class StructType(val name: String, val fields: List<StructField>) : SdType() {
    override fun toString() = name
  }
  data object UnknownType : SdType() { override fun toString() = "unknown" }
}

data class StructField(val name: String, val type: SdType)

sealed class PtrTarget {
  data class VarRef(val box: MutableBox) : PtrTarget()
  data class HeapSlotRef(val blockId: Int, val offset: Int) : PtrTarget()
  data class FixedAddrRef(val addr: Long) : PtrTarget()
}

class MutableBox(var value: SdValue, val type: SdType, val isBorrowed: Boolean = false)

sealed class SdValue {
  data class IntVal(val value: Long) : SdValue() {
    override fun toString() = value.toString()
  }
  data class FloatVal(val value: Double) : SdValue() {
    override fun toString() = String.format(Locale.US, "%.2f", value)
  }
  data class StrVal(val value: String) : SdValue() {
    override fun toString() = value
  }
  data class BoolVal(val value: Boolean) : SdValue() {
    override fun toString() = if (value) "true" else "false"
  }
  data class PtrVal(val target: PtrTarget?) : SdValue() {
    override fun toString(): String = when (target) {
      null -> "null"
      is PtrTarget.VarRef -> "@${Integer.toHexString(System.identityHashCode(target.box))}"
      is PtrTarget.HeapSlotRef -> "heap#${target.blockId}+${target.offset}"
      is PtrTarget.FixedAddrRef -> "0x${target.addr.toString(16).uppercase()}"
    }
  }
  data class ArrayVal(val elements: MutableList<SdValue>, val elemType: SdType) : SdValue() {
    override fun toString() = "[${elements.joinToString(" ' ")}]"
  }
  data class StructVal(
    val typeName: String,
    val fields: LinkedHashMap<String, MutableBox>,
  ) : SdValue() {
    override fun toString(): String =
      "$typeName{${fields.entries.joinToString(", ") { "${it.key}=${it.value.value}" }}}"
  }
  data object UnitVal : SdValue() {
    override fun toString() = "void"
  }
}

object SdTypeUtils {
  const val SAFE_INT_MAX = 1L shl 53 // 2^53 = 9007199254740992L

  fun defaultValueFor(type: SdType, structs: Map<String, SdType.StructType>): SdValue = when (type) {
    SdType.IntType -> SdValue.IntVal(0L)
    SdType.FloatType -> SdValue.FloatVal(0.0)
    SdType.StrType -> SdValue.StrVal("")
    SdType.BoolType -> SdValue.BoolVal(false)
    is SdType.PtrType -> SdValue.PtrVal(null)
    is SdType.ArrayType -> SdValue.ArrayVal(
      MutableList(type.size) { defaultValueFor(type.elemType, structs) },
      type.elemType,
    )
    is SdType.StructType -> {
      val map = LinkedHashMap<String, MutableBox>()
      for (f in type.fields) {
        map[f.name] = MutableBox(defaultValueFor(f.type, structs), f.type)
      }
      SdValue.StructVal(type.name, map)
    }
    SdType.VoidType, SdType.UnknownType -> SdValue.UnitVal
  }

  fun deepCopyIfNeeded(value: SdValue): SdValue = when (value) {
    is SdValue.StructVal -> {
      val copy = LinkedHashMap<String, MutableBox>()
      for ((k, box) in value.fields) {
        copy[k] = MutableBox(deepCopyIfNeeded(box.value), box.type)
      }
      SdValue.StructVal(value.typeName, copy)
    }
    // Arrays in Sd 2 are passed/shared by reference per Ch 7 & Ch 20!
    else -> value
  }

  fun isAssignable(target: SdType, source: SdType): Boolean {
    if (target == SdType.UnknownType || source == SdType.UnknownType) return true
    if (target == source) return true
    // Implicit int -> float promotion (Ch 19.2)
    if (target == SdType.FloatType && source == SdType.IntType) return true
    // Naked ptr <-> typed ptr<T>, or sta.alloc returns PtrType(null) assignable to any ptr<T>
    if (target is SdType.PtrType && source is SdType.PtrType) {
      if (target.elemType == null || source.elemType == null) return true
      return target.elemType == source.elemType
    }
    if (target is SdType.ArrayType && source is SdType.ArrayType) {
      return target.size == source.size && isAssignable(target.elemType, source.elemType)
    }
    if (target is SdType.StructType && source is SdType.StructType) {
      return target.name == source.name
    }
    return false
  }

  fun checkSafeInt(value: Long, line: Int, col: Int, fileName: String?): Long {
    if (abs(value) > SAFE_INT_MAX) {
      throw SdRuntimeException(
        SdDiagnostic(
          code = "E405",
          phase = 4,
          line = line,
          col = col,
          fileName = fileName,
          message = "số nguyên tràn vượt ngưỡng an toàn 2^53 ($value)",
        )
      )
    }
    return value
  }
}
