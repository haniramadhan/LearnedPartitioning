package org.apache.spark.sql.catalyst.expressions.dita.common.trajectory

import org.apache.spark.sql.catalyst.expressions.dita.common.shape.Point


case class Trajectory1(allString: String) {

  override def toString: String = {
    s"Trajectory=" + allString
    //s"Trajectory(points = ${points.mkString(",")}" + ";globalId " + globalId +"; localId" +localId
  }
}