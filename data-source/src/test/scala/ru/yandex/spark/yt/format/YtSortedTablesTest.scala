package ru.yandex.spark.yt.format

import org.scalatest.{FlatSpec, Matchers}
import ru.yandex.bolts.collection.MapF
import ru.yandex.inside.yt.kosher.ytree.YTreeNode
import ru.yandex.spark.yt.test.{LocalSpark, TmpDir}
import ru.yandex.spark.yt.wrapper.{YtJavaConverters, YtWrapper}
import ru.yandex.spark.yt._

import scala.collection.JavaConverters._

class YtSortedTablesTest extends FlatSpec with Matchers with LocalSpark with TmpDir {
  import spark.implicits._

  "YtFileFormat" should "write sorted table" in {
    val df = (1 to 9).toDF.coalesce(3)

    df.write.sortedBy("value").yt(tmpPath)

    sortColumns(tmpPath) should contain theSameElementsAs Seq("value")
    uniqueKeys(tmpPath) shouldEqual false
    ytSchema(tmpPath) should contain theSameElementsAs Seq(
      Map("name" -> Some("value"), "type" -> Some("int32"), "sort_order" -> Some("ascending"))
    )
  }

  it should "change columns order in sorted table" in {
    val df = (1 to 9).zip(9 to 1 by -1).toDF("a", "b")

    spark.conf.set("spark.sql.adaptive.enabled", "false")
    df.sort("b").coalesce(3).write.sortedBy("b").yt(tmpPath)

    sortColumns(tmpPath) should contain theSameElementsAs Seq("b")
    uniqueKeys(tmpPath) shouldEqual false
    ytSchema(tmpPath) should contain theSameElementsAs Seq(
      Map("name" -> Some("b"), "type" -> Some("int32"), "sort_order" -> Some("ascending")),
      Map("name" -> Some("a"), "type" -> Some("int32"), "sort_order" -> None)
    )
  }

  it should "abort transaction if failed to create sorted table" in {
    val df = (1 to 9).toDF("my_name").coalesce(3)
    an[Exception] shouldBe thrownBy {
      df.write.sortedBy("bad_name").yt(tmpPath)
    }
    noException shouldBe thrownBy {
      df.write.sortedBy("my_name").yt(tmpPath)
    }
  }

  it should "write table with 'unique_keys' attribute" in {
    val df = Seq(1, 2, 3).toDF()

    df.write.sortedByUniqueKeys("value").yt(tmpPath)

    sortColumns(tmpPath) should contain theSameElementsAs Seq("value")
    uniqueKeys(tmpPath) shouldEqual true
    ytSchema(tmpPath) should contain theSameElementsAs Seq(
      Map("name" -> Some("value"), "type" -> Some("int32"), "sort_order" -> Some("ascending"))
    )
  }

  it should "fail if 'unique_keys' attribute is set true, but keys are not unique" in {
    val df = Seq(1, 1, 1).toDF()

    an [Exception] shouldBe thrownBy {
      df.write.sortedByUniqueKeys("value").yt(tmpPath)
    }
  }

  it should "fail if 'unique_keys' attribute is set true, but table is not sorted" in {
    val df = Seq(1, 2, 3).toDF()

    an [Exception] shouldBe thrownBy {
      df.write.uniqueKeys.yt(tmpPath)
    }
  }

  it should "fail if 'sorted_by' option is set, but table is not sorted" in {
    val df = Seq(2, 3, 1).toDF().coalesce(1)

    an [Exception] shouldBe thrownBy {
      df.write.sortedBy("value").yt(tmpPath)
    }
  }

  def sortColumns(path: String): Seq[String] = {
    YtWrapper.attribute(path, "sorted_by").asList().asScala.map(_.stringValue())
  }

  def uniqueKeys(path: String): Boolean = {
    YtWrapper.attribute(path, "schema").getAttribute("unique_keys").get().boolValue()
  }

  def ytSchema(path: String): Seq[Map[String, Option[String]]] = {
    import ru.yandex.spark.yt.wrapper.YtJavaConverters._
    val schemaFields = Seq("name", "type", "sort_order")
    YtWrapper.attribute(path, "schema").asList().asScala.map { field =>
      val map = field.asMap()
      schemaFields.map(n => n -> map.getOption(n).map(_.stringValue())).toMap
    }
  }

}
