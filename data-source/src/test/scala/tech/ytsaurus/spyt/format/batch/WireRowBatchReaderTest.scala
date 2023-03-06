package tech.ytsaurus.spyt.format.batch

import org.scalatest.{FlatSpec, Matchers}
import tech.ytsaurus.spyt._
import tech.ytsaurus.spyt.serializers.ArrayAnyDeserializer
import tech.ytsaurus.spyt.test.{LocalSpark, TmpDir}
import tech.ytsaurus.spyt.wrapper.YtWrapper
import tech.ytsaurus.spyt.wrapper.YtWrapper.formatPath
import tech.ytsaurus.core.cypress.YPath

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Random

class WireRowBatchReaderTest extends FlatSpec with Matchers with ReadBatchRows with LocalSpark with TmpDir {

  behavior of "WireRowBatchReaderTest"

  it should "read table in wire row format" in {
    import spark.implicits._
    val rowCount = 100
    val batchMaxSize = 10

    val r = new Random()
    val data = Seq.fill(rowCount)((r.nextInt, r.nextDouble))
    val df = data.toDF("a", "b")
    df.coalesce(1).write.yt(tmpPath)
    val rowIterator = YtWrapper.readTable(YPath.simple(formatPath(tmpPath)),
      ArrayAnyDeserializer.getOrCreate(df.schema), 10 seconds, None, _ => ())

    val reader = new WireRowBatchReader(rowIterator, batchMaxSize, df.schema)
    val res = readFully(reader, df.schema, batchMaxSize)
    val expected = df.collect()

    res should contain theSameElementsAs expected
  }

}
