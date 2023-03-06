package tech.ytsaurus.spyt.wrapper.table

import tech.ytsaurus.client.TableReader

import java.nio.ByteBuffer
import scala.annotation.tailrec
import scala.concurrent.duration.Duration


class TableCopyByteStream(reader: TableReader[ByteBuffer], timeout: Duration,
                          reportBytesRead: Long => Unit) extends YtArrowInputStream {
  private var _batch: ByteBuffer = _
  private var _batchBytesLeft = 0
  private val nextPageToken: Array[(Byte, Int)] = Array(-1, -1, -1, -1, 0, 0, 0, 0).map(_.toByte).zipWithIndex
  private val emptySchemaToken: Array[(Byte, Int)] = Array(0, 0, 0, 0, 0, 0, 0, 0).map(_.toByte).zipWithIndex

  override def read(): Int = ???

  override def read(b: Array[Byte]): Int = {
    read(b, 0, b.length)
  }

  override def read(b: Array[Byte], off: Int, len: Int): Int = {
    read(b, off, len, 0)
  }

  private def recognizeToken(token: Array[(Byte, Int)]): Boolean = {
    if (hasNext && _batchBytesLeft >= token.length) {
      val res = token.forall { case (b, i) => _batch.array()(_batch.arrayOffset() + _batch.position() + i) == b }
      if (res) {
        _batch.position(_batch.position() + token.length)
        _batchBytesLeft -= token.length
      }
      res
    } else false
  }

  override def isNextPage: Boolean = {
    recognizeToken(nextPageToken)
  }

  override def isEmptyPage: Boolean = {
    recognizeToken(emptySchemaToken)
  }

  @tailrec
  private def read(b: Array[Byte], off: Int, len: Int, readLen: Int): Int = len match {
    case 0 => readLen
    case _ =>
      if (hasNext) {
        val readBytes = Math.min(len, _batchBytesLeft)
        readFromBatch(b, off, readBytes)
        read(b, off + readBytes, len - readBytes, readLen + readBytes)
      } else readLen
  }

  private def hasNext: Boolean = {
    _batchBytesLeft > 0 || readNextBatch()
  }

  private def readFromBatch(b: Array[Byte], off: Int, len: Int): Unit = {
    System.arraycopy(_batch.array(), _batch.arrayOffset() + _batch.position(), b, off, len)
    _batch.position(_batch.position() + len)
    _batchBytesLeft -= len
    reportBytesRead(len)
  }

  private def readNextBatch(): Boolean = {
    if (reader.canRead) {
      reader.readyEvent().join()
      val res = reader.read()
      if (res != null) {
        _batch = res.get(0)
        _batchBytesLeft = _batch.limit() - _batch.position()
        true
      } else {
        false
      }
    } else {
      false
    }
  }

  override def close(): Unit = {
    if (reader.canRead) {
      reader.cancel()
    } else {
      reader.close().join()
    }
  }
}
