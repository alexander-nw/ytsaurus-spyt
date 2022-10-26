package ru.yandex.spark.yt.format.batch

import org.apache.spark.sql.yson.YsonType
import org.scalatest.{FlatSpec, Matchers}
import ru.yandex.inside.yt.kosher.cypress.{CypressNodeType, YPath}
import ru.yandex.inside.yt.kosher.impl.ytree.builder.YTree
import ru.yandex.spark.yt.serializers.{InternalRowDeserializer, SchemaConverter}
import ru.yandex.spark.yt.test.{LocalSpark, TmpDir}
import ru.yandex.spark.yt.wrapper.YtWrapper
import ru.yandex.spark.yt.wrapper.YtWrapper.formatPath
import ru.yandex.yt.ytclient.`object`.UnversionedRowSerializer
import ru.yandex.yt.ytclient.proxy.request.{CreateNode, WriteTable}
import ru.yandex.yt.ytclient.tables.{ColumnValueType, TableSchema}
import ru.yandex.yt.ytclient.wire._

import scala.collection.JavaConverters._
import scala.language.postfixOps

class HorizontalAnyBatchReaderTest extends FlatSpec with Matchers with ReadBatchRows with LocalSpark with TmpDir {

  behavior of "HorizontalBatchReaderTest"

  it should "read different kinds of any using InternalRowDeserializer" in {
    val path = YPath.simple(formatPath(tmpPath))

    // Create table.

    val ytSchema = TableSchema.fromYTree(YTree.builder().beginList()
      .beginMap()
      .key("name").value("a")
      .key("type").value("any")
      .endMap().endList().build())
    yt.createNode(new CreateNode(path, CypressNodeType.TABLE, Map(
      "schema" -> ytSchema.toYTree,
      "optimize_for" -> YTree.builder().value("scan").build(),
    ).asJava)).join()

    // Write rows.

    val rows = List(
        new UnversionedRow(List(new UnversionedValue(0, ColumnValueType.INT64, false, 42.toLong)).asJava),
        new UnversionedRow(List(new UnversionedValue(0, ColumnValueType.STRING, false, "xyz".getBytes())).asJava),
        new UnversionedRow(List(new UnversionedValue(0, ColumnValueType.ANY, false, "{}".getBytes())).asJava),
        new UnversionedRow(List(new UnversionedValue(0, ColumnValueType.NULL, false, null)).asJava),
      ).asJava
    val writer = yt.writeTable(new WriteTable(path, new UnversionedRowSerializer)).join()

    writer.write(rows, ytSchema)
    writer.close().join()

    // Read rows.

    val sparkSchema = SchemaConverter.sparkSchema(ytSchema.toYTree)

    val iter = YtWrapper.readTable(
      path,
      InternalRowDeserializer.getOrCreate(sparkSchema), reportBytesRead = _ => ())

    val resultRows = iter.toArray

    // Ensure proper YSON Any representations.

    resultRows(0).get(0, YsonType).asInstanceOf[Array[Byte]] should contain inOrder(0x2, 84)
    resultRows(1).get(0, YsonType).asInstanceOf[Array[Byte]] should contain inOrder(0x1, 6, 120, 121, 122)
    resultRows(2).get(0, YsonType).asInstanceOf[Array[Byte]] should contain inOrder(123, 125)
    resultRows(3).get(0, YsonType) should be (null)
  }

}
