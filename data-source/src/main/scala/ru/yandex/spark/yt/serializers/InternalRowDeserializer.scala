package ru.yandex.spark.yt.serializers

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.GenericInternalRow
import org.apache.spark.sql.sources.Filter
import org.apache.spark.sql.types._
import org.apache.spark.sql.yson.{UInt64Type, YsonType}
import org.apache.spark.unsafe.types.UTF8String
import ru.yandex.inside.yt.kosher.common.Decimal.binaryToText
import ru.yandex.inside.yt.kosher.impl.ytree.serialization.spark.YsonDecoder
import tech.ytsaurus.client.rows.{WireRowDeserializer, WireValueDeserializer}
import tech.ytsaurus.core.tables.ColumnValueType
import tech.ytsaurus.ysontree.{YTreeBinarySerializer, YTreeBuilder}

import java.io.ByteArrayOutputStream
import scala.collection.mutable

class InternalRowDeserializer(schema: StructType) extends WireRowDeserializer[InternalRow] with WireValueDeserializer[Any] {
  private var _values: Array[Any] = _
  private val indexedSchema = schema.fields.map(_.dataType).toIndexedSeq
  private val indexedDataTypes = schema.fields.map(f => SchemaConverter.indexedDataType(f.dataType))

  private var _currentType: ColumnValueType = ColumnValueType.THE_BOTTOM
  private var _index = 0

  override def onNewRow(columnCount: Int): WireValueDeserializer[_] = {
    _values = new Array[Any](schema.length)
    _index = 0
    _currentType = ColumnValueType.THE_BOTTOM
    this
  }

  override def onCompleteRow(): InternalRow = {
    new GenericInternalRow(_values)
  }

  override def onNullRow(): InternalRow = {
    throw new IllegalArgumentException("Null rows are not supported")
  }

  override def setId(id: Int): Unit = {
    _index = id
  }

  override def setType(`type`: ColumnValueType): Unit = {
    _currentType = `type`
  }

  override def setAggregate(aggregate: Boolean): Unit = {}

  override def setTimestamp(timestamp: Long): Unit = {}

  override def build(): Any = null

  private def addValue(value: Any): Unit = {
    if (_index < _values.length) {
      _values(_index) = value
    }
  }

  override def onEntity(): Unit = addValue(null)

  override def onInteger(value: Long): Unit = {
    if (_index < _values.length) {
      _currentType match {
        case ColumnValueType.INT64 | ColumnValueType.UINT64 =>
          indexedSchema(_index) match {
            case LongType => addValue(value)
            case IntegerType => addValue(value.toInt)
            case ShortType => addValue(value.toShort)
            case ByteType => addValue(value.toByte)
            case DateType => addValue(value.toInt)
            case TimestampType => addValue(value * 1000000)
            case YsonType => addValue(toYsonBytes(value))
            case UInt64Type => addValue(value)
            case _ => throwSchemaViolation()
          }
        case _ => throwValueTypeViolation("integer")
      }
    }
  }

  override def onBoolean(value: Boolean): Unit = {
    if (_index < _values.length) {
      _currentType match {
        case ColumnValueType.BOOLEAN =>
          indexedSchema(_index) match {
            case BooleanType => addValue(value)
            case YsonType => addValue(toYsonBytes(value))
            case _ => throwSchemaViolation()
          }
        case _ => throwValueTypeViolation("boolean")
      }
    }
  }

  override def onDouble(value: Double): Unit = {
    if (_index < _values.length) {
      _currentType match {
        case ColumnValueType.DOUBLE =>
          indexedSchema(_index) match {
            case FloatType => addValue(value.toFloat)
            case DoubleType => addValue(value)
            case YsonType => addValue(toYsonBytes(value))
            case _ => throwSchemaViolation()
          }
        case _ => throwValueTypeViolation("double")
      }
    }
  }

  override def onBytes(bytes: Array[Byte]): Unit = {
    if (_index < _values.length) {
      _currentType match {
        case ColumnValueType.STRING =>
          indexedSchema(_index) match {
            case BinaryType => addValue(bytes)
            case StringType => addValue(UTF8String.fromBytes(bytes))
            case YsonType => addValue(toYsonBytes(bytes))
            case d: DecimalType =>
              addValue(Decimal(BigDecimal(binaryToText(bytes, d.precision, d.scale)), d.precision, d.scale))
            case _ => throwSchemaViolation()
          }
        case ColumnValueType.ANY | ColumnValueType.COMPOSITE =>
          indexedSchema(_index) match {
            case YsonType => addValue(bytes)
            case _@(ArrayType(_, _) | StructType(_) | MapType(_, _, _)) =>
              addValue(YsonDecoder.decode(bytes, indexedDataTypes(_index)))
            case _ => throwSchemaViolation()
          }
        case _ => throwValueTypeViolation("string")
      }
    }
  }

  def toYsonBytes(value: Any): Array[Byte] = {
    val output = new ByteArrayOutputStream(64)
    val node = new YTreeBuilder().value(value).build()
    YTreeBinarySerializer.serialize(node, output)
    output.toByteArray
  }

  def throwSchemaViolation(): Unit = {
    throw new IllegalArgumentException(s"Value type ${_currentType} does not match schema data type ${indexedSchema(_index)}")
  }

  def throwValueTypeViolation(ysonType: String): Unit = {
    throw new IllegalArgumentException(s"Value of YSON type $ysonType does not match value type ${_currentType}")
  }
}

object InternalRowDeserializer {
  private val deserializers: ThreadLocal[mutable.Map[StructType, InternalRowDeserializer]] = ThreadLocal.withInitial(() => mutable.ListMap.empty)

  def getOrCreate(schema: StructType, filters: Array[Filter] = Array.empty): InternalRowDeserializer = {
    deserializers.get().getOrElseUpdate(schema, new InternalRowDeserializer(schema))
  }
}
