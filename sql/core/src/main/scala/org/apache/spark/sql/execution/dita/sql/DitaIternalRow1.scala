package org.apache.spark.sql.execution.dita.sql

import org.apache.spark.sql.catalyst.expressions.UnsafeRow
import org.apache.spark.sql.catalyst.expressions.dita.common.shape.Point
import org.apache.spark.sql.catalyst.expressions.dita.common.trajectory.Trajectory1

class DITAIternalRow1(unsafeRow: UnsafeRow, allString: String)
  extends Trajectory1(allString) {
  var row: UnsafeRow = unsafeRow
}