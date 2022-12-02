package org.apache.spark.sql.v2

import org.apache.hadoop.fs.FileSystem
import org.apache.hadoop.mapreduce.{InputSplit, RecordReader, TaskAttemptContext}
import org.apache.spark.TaskContext
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.UnsafeProjection
import org.apache.spark.sql.connector.read.{InputPartition, PartitionReader}
import org.apache.spark.sql.execution.datasources.PartitionedFile
import org.apache.spark.sql.execution.datasources.v2.{FilePartitionReaderFactory, PartitionReaderWithPartitionValues}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types._
import org.apache.spark.sql.vectorized.{ColumnVector, ColumnarBatch, SingleValueColumnVector, YtVectorizedReader}
import org.apache.spark.util.SerializableConfiguration
import org.slf4j.LoggerFactory
import ru.yandex.spark.yt.common.utils.SegmentSet
import ru.yandex.spark.yt.format.conf.FilterPushdownConfig
import ru.yandex.spark.yt.format.conf.SparkYtConfiguration.Read.VectorizedCapacity
import ru.yandex.spark.yt.format.{YtInputSplit, YtPartitionedFile}
import ru.yandex.spark.yt.fs.YtClientConfigurationConverter.ytClientConfiguration
import ru.yandex.spark.yt.fs.{YtFileSystem, YtFileSystemBase, YtTableFileSystem}
import ru.yandex.spark.yt.fs.conf._
import ru.yandex.spark.yt.logger.{TaskInfo, YtDynTableLoggerConfig}
import ru.yandex.spark.yt.serializers.InternalRowDeserializer
import ru.yandex.spark.yt.wrapper.YtWrapper
import ru.yandex.spark.yt.wrapper.client.YtClientProvider
import tech.ytsaurus.client.{ApiServiceTransaction, CompoundClient}

case class YtPartitionReaderFactory(sqlConf: SQLConf,
                                    broadcastedConf: Broadcast[SerializableConfiguration],
                                    dataSchema: StructType,
                                    readDataSchema: StructType,
                                    partitionSchema: StructType,
                                    options: Map[String, String],
                                    pushedFilterSegments: SegmentSet,
                                    filterPushdownConf: FilterPushdownConfig,
                                    ytLoggerConfig: Option[YtDynTableLoggerConfig])
  extends FilePartitionReaderFactory with Logging {

  private val unsupportedTypes: Set[DataType] = Set(DateType, TimestampType, FloatType)

  private val resultSchema = StructType(readDataSchema.fields)
  private val ytClientConf = ytClientConfiguration(sqlConf)
  private val arrowEnabled: Boolean = {
    import ru.yandex.spark.yt.format.conf.{SparkYtConfiguration => SparkSettings, YtTableSparkSettings => TableSettings}
    import ru.yandex.spark.yt.fs.conf._
    val keyPartitioned = options.get(TableSettings.KeyPartitioned.name).exists(_.toBoolean)
    options.ytConf(TableSettings.ArrowEnabled) && sqlConf.ytConf(SparkSettings.Read.ArrowEnabled) && !keyPartitioned
  }
  private val readBatch: Boolean = {
    import ru.yandex.spark.yt.format.conf.{YtTableSparkSettings => TableSettings}
    val optimizedForScan = options.get(TableSettings.OptimizedForScan.name).exists(_.toBoolean)
    (optimizedForScan && arrowEnabled && arrowSchemaSupported(readDataSchema)) || readDataSchema.isEmpty
  }
  private val returnBatch: Boolean = {
    readBatch && sqlConf.wholeStageEnabled &&
      resultSchema.length <= sqlConf.wholeStageMaxNumFields &&
      resultSchema.forall(_.dataType.isInstanceOf[AtomicType])
  }
  private val batchMaxSize = sqlConf.ytConf(VectorizedCapacity)

  @transient private lazy val taskContext: ThreadLocal[TaskContext] = new ThreadLocal()


  override def setTaskContext(context: TaskContext): Unit = {
    taskContext.set(context)
  }

  override def supportColumnarReads(partition: InputPartition): Boolean = {
    returnBatch
  }

  override def buildReader(file: PartitionedFile): PartitionReader[InternalRow] = {
    implicit val yt: CompoundClient = YtClientProvider.ytClient(ytClientConf)
    buildLockedSplitReader(file) { case (split, transaction) =>
      val reader = if (readBatch) {
        createVectorizedReader(split, returnBatch = false, file.partitionValues)
      } else {
        createRowBaseReader(split)
      }

      val fileReader = new PartitionReader[InternalRow] {
        override def next(): Boolean = reader.nextKeyValue()

        override def get(): InternalRow = reader.getCurrentValue.asInstanceOf[InternalRow]

        override def close(): Unit = {
          transaction.foreach(_.commit().join())
          reader.close()
        }
      }

      new PartitionReaderWithPartitionValues(fileReader, readDataSchema,
        partitionSchema, file.partitionValues)
    }
  }

  override def buildColumnarReader(file: PartitionedFile): PartitionReader[ColumnarBatch] = {
    implicit val yt: CompoundClient = YtClientProvider.ytClient(ytClientConf)
    buildLockedSplitReader(file) { case (split, transaction) =>
      val vectorizedReader = createVectorizedReader(split, returnBatch = true, file.partitionValues)
      new PartitionReader[ColumnarBatch] {
        override def next(): Boolean = vectorizedReader.nextKeyValue()

        override def get(): ColumnarBatch = {
          val sourceBatch = vectorizedReader.getCurrentValue.asInstanceOf[ColumnarBatch]
          val capacity = sourceBatch.numRows()
          val schemaCols = sourceBatch.numCols()
          if (partitionSchema != null && partitionSchema.nonEmpty) {
            val columnVectors = new Array[ColumnVector](sourceBatch.numCols() + partitionSchema.fields.length)
            for (i <- 0 until schemaCols) {
              columnVectors(i) = sourceBatch.column(i)
            }
            partitionSchema.fields.zipWithIndex.foreach { case (field, index) =>
              columnVectors(index + schemaCols) = new SingleValueColumnVector(capacity, field.dataType,
                file.partitionValues, index)
            }
            new ColumnarBatch(columnVectors, capacity)
          }
          else
            sourceBatch
        }

        override def close(): Unit = {
          transaction.foreach(_.commit().join())
          vectorizedReader.close()
        }
      }
    }
  }

  private def buildLockedSplitReader[T](file: PartitionedFile)
                                       (splitReader: (YtInputSplit, Option[ApiServiceTransaction]) => PartitionReader[T])
                                       (implicit yt: CompoundClient): PartitionReader[T] = {
    file match {
      case ypf: YtPartitionedFile =>
        val split = createSplit(ypf)
        splitReader(split, None)
      case _ =>
        throw new IllegalArgumentException(s"Partitions of type ${file.getClass.getSimpleName} are not supported")
    }
  }

  private def createSplit(file: YtPartitionedFile): YtInputSplit = {
    val log = LoggerFactory.getLogger(getClass)
    val ytLoggerConfigWithTaskInfo = ytLoggerConfig.map(_.copy(taskContext = Some(TaskInfo(taskContext.get()))))
    val split = YtInputSplit(file, resultSchema, pushedFilterSegments, filterPushdownConf,
      ytLoggerConfigWithTaskInfo)

    log.info(s"Reading ${split.ytPath}, " +
      s"read batch: $readBatch, return batch: $returnBatch, arrowEnabled: $arrowEnabled, " +
      s"pushdown config: $filterPushdownConf, detailed yPath: ${split.ytPathWithFiltersDetailed}")

    split
  }

  private def createRowBaseReader(split: YtInputSplit)
                                 (implicit yt: CompoundClient): RecordReader[Void, InternalRow] = {
    val iter = YtWrapper.readTable(
      split.ytPathWithFiltersDetailed,
      InternalRowDeserializer.getOrCreate(resultSchema),
      ytClientConf.timeout, None,
      incrementBytesRead
    )
    val unsafeProjection = UnsafeProjection.create(resultSchema)

    new RecordReader[Void, InternalRow] {
      private var current: InternalRow = _

      override def initialize(split: InputSplit, context: TaskAttemptContext): Unit = {}

      override def nextKeyValue(): Boolean = {
        if (iter.hasNext) {
          current = unsafeProjection.apply(iter.next())
          true
        } else false
      }

      override def getCurrentKey: Void = {
        null
      }

      override def getCurrentValue: InternalRow = {
        current
      }

      override def getProgress: Float = 0.0f

      override def close(): Unit = {
        iter.close()
      }
    }
  }

  private def createVectorizedReader(split: YtInputSplit,
                                     returnBatch: Boolean,
                                     partitionValues: InternalRow)
                                    (implicit yt: CompoundClient): YtVectorizedReader = {
    new YtVectorizedReader(
      split = split,
      batchMaxSize = batchMaxSize,
      returnBatch = returnBatch,
      arrowEnabled = arrowEnabled,
      timeout = ytClientConf.timeout,
      incrementBytesRead
    )
  }

  private def incrementBytesRead: Long => Unit = {
    val fs = FileSystem.get(broadcastedConf.value.value)
    fs match {
      case yfs: YtFileSystem => yfs.internalStatistics.incrementBytesRead
      case ytfs: YtTableFileSystem => ytfs.internalStatistics.incrementBytesRead
      case _ =>
        log.warn(s"Unsupported fs: ${fs.getClass.getName}")
        _ => () //noop
    }
  }

  private def arrowSchemaSupported(dataSchema: StructType): Boolean = {
    dataSchema.fields.forall(f => !unsupportedTypes.contains(f.dataType))
  }

}
