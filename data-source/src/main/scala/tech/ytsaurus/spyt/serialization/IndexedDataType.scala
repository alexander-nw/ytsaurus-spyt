package tech.ytsaurus.spyt.serialization

import org.apache.spark.sql.types
import org.apache.spark.sql.types.{DataType, NullType, StringType, ArrayType}

sealed abstract class IndexedDataType {
  def sparkDataType: DataType
}

object IndexedDataType {

  case class StructFieldMeta(index: Int, dataType: IndexedDataType, var isNull: Boolean) {
    def setNotNull(): Unit = {
      isNull = false
    }

    def setNull(): Unit = {
      isNull = true
    }
  }

  case class StructType(map: Map[String, StructFieldMeta], sparkDataType: types.StructType) extends IndexedDataType {
    def apply(index: Int): IndexedDataType = {
      val name = sparkDataType(index).name
      map(name).dataType
    }
  }

  case class ArrayType(element: IndexedDataType, sparkDataType: DataType) extends IndexedDataType

  case class MapType(keyType: IndexedDataType, valueType: IndexedDataType, sparkDataType: DataType) extends IndexedDataType

  case class TupleType(dataTypes: Seq[IndexedDataType], sparkDataType: types.StructType) extends IndexedDataType {
    def apply(index: Int): IndexedDataType = dataTypes(index)
    def length: Int = dataTypes.length
  }

  case class VariantOverStructType(inner: IndexedDataType.StructType) extends IndexedDataType {
    override def sparkDataType: DataType = inner.sparkDataType
  }

  case class VariantOverTupleType(inner: IndexedDataType.TupleType) extends IndexedDataType {
    override def sparkDataType: DataType = inner.sparkDataType
  }

  case class AtomicType(sparkDataType: DataType) extends IndexedDataType

  case object ScalaStringType extends IndexedDataType {
    override def sparkDataType: DataType = StringType
  }

  case object NoneType extends IndexedDataType {
    override def sparkDataType: DataType = NullType
  }

}
