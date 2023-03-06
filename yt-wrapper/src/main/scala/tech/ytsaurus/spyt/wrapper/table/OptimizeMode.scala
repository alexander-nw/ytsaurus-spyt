package tech.ytsaurus.spyt.wrapper.table

import tech.ytsaurus.ysontree.{YTreeBuilder, YTreeNode}

sealed abstract class OptimizeMode(val name: String) {
  def node: YTreeNode = new YTreeBuilder().value(name).build()
}

object OptimizeMode {
  case object Scan extends OptimizeMode("scan")
  case object Lookup extends OptimizeMode("lookup")

  def fromName(name: String): OptimizeMode = {
    Seq(Scan, Lookup).find(_.name == name).getOrElse(throw new IllegalArgumentException)
  }
}
