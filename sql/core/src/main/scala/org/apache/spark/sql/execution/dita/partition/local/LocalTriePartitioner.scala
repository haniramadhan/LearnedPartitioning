/*
 *  Copyright 2017 by DITA Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.spark.sql.execution.dita.partition.local

import org.apache.spark.sql.catalyst.expressions.dita.common.DITAConfigConstants
import org.apache.spark.sql.catalyst.expressions.dita.common.shape.{Point, Shape}
import org.apache.spark.sql.catalyst.expressions.dita.common.trajectory.{Trajectory, TrajectorySimilarity}
import org.apache.spark.sql.catalyst.expressions.dita.common.trajectory.TrajectorySimilarity.{DTWDistance, EDRDistance, FrechetDistance, LCSSDistance}
import org.apache.spark.sql.execution.dita.partition.{STRPartitioner, TriePartitioner}

case class LocalTriePartitioner(partitioner: STRPartitioner,
                                childPartitioners: Array[LocalTriePartitioner],
                                level: Int,
                                count: Int, data: Option[Array[List[Trajectory]]])
  extends TriePartitioner(partitioner, childPartitioners, level) {
  override def indexedPivotCount: Int = DITAConfigConstants.LOCAL_INDEXED_PIVOT_COUNT

  override def getPartition(key: Any): Int = {
    val k = LocalTriePartitioner.getIndexedKey(key)
    val x = partitioner.getPartition(k.head)
    if (childPartitioners.nonEmpty) {
      val y = childPartitioners(x).getPartition(k.tail)
      totalPartitions(x) + y
    } else {
      x
    }
  }

  def getCandidates(key: Shape, threshold: Double): List[Trajectory] = {
    if (count <= DITAConfigConstants.LOCAL_MIN_NODE_SIZE) {
      return data.get.flatten.toList
    }


    val childPartitions = partitioner.getPartitionsWithThreshold(key, threshold)
    if (childPartitioners.isEmpty) {
      childPartitions.filter(_._2 != -1).flatMap(x => data.get(x._2))
    } else {
      childPartitions.flatMap { case (_, x) =>
        childPartitioners(x).getCandidates(key, threshold)
      }
    }
  }

  def getCandidates(key: Any, distanceFunction: TrajectorySimilarity, threshold: Double,
                    distanceAccu: Double, globalId: Int, ex: Int): List[(Trajectory, Double, Int)] = {
    val k = TriePartitioner.getSearchKey(key)
    if (count <= DITAConfigConstants.LOCAL_MIN_NODE_SIZE) {
      return data.get.flatten.toList.map((_, threshold,ex))
    }


    distanceFunction match {
      case DTWDistance | FrechetDistance =>
        if (level > indexedPivotCount) {
          val childPartitions = partitioner.getPartitionsWithThreshold(k.head, threshold)
          if (childPartitioners.isEmpty) {
            childPartitions.map{ case (shape, x) =>
              val distance = shape.minDist(k.head)
              val newDistanceAccu = distanceFunction.updateDistance(distanceAccu, distance)
              (x, newDistanceAccu)
            }.filter(_._2 < threshold).flatMap(x => data.get(x._1).map((_, x._2,x._1)))
          } else {
            childPartitions.flatMap{ case (shape, x) =>
              val distance = shape.minDist(k.head)
              val newDistanceAccu = distanceFunction.updateDistance(distanceAccu, distance)
              val newThreshold = distanceFunction.updateThreshold(threshold, distance)
              val c = childPartitioners(x).getCandidates(k.tail, distanceFunction, newThreshold, newDistanceAccu, globalId,x)

              c.map(c => (c._1,c._2,c._3))
            }
          }
        } else {
          partitioner.mbrBounds.toList.flatMap{ case (shape, x) =>
            val newK = k.dropWhile(p => shape.approxMinDist(p) > threshold)
            val distance = if (newK.isEmpty) DITAConfigConstants.THRESHOLD_LIMIT
            else newK.map(p => shape.approxMinDist(p)).min
            val newDistanceAccu = distanceFunction.updateDistance(distanceAccu, distance)
            if (newDistanceAccu > threshold) {
              List.empty[(Trajectory, Double, Int)]
            } else {
              val newThreshold = distanceFunction.updateThreshold(threshold, distance)
              if (childPartitioners.isEmpty) {
                data.get(x).map((_, newDistanceAccu, x))
              } else {
                val c = childPartitioners(x).getCandidates(newK, distanceFunction, newThreshold, newDistanceAccu, globalId,x)
                c.map(c => (c._1,c._2,x))
              }
            }
          }
        }
      case EDRDistance | LCSSDistance =>
        if (level > indexedPivotCount) {
          partitioner.mbrBounds.toList.flatMap{ case (shape, x) =>
            val distance = distanceFunction match {
              case LCSSDistance => LCSSDistance.subCost(shape, 0, k.head, 0)
              case EDRDistance => EDRDistance.subCost(shape, k.head)
            }
            val newDistanceAccu = distanceFunction.updateDistance(distanceAccu, distance)
            if (newDistanceAccu > threshold) {
              List.empty[(Trajectory, Double, Int)]
            } else {
              if (childPartitioners.isEmpty) {
                data.get(x).map((_, newDistanceAccu,x))
              } else {
                val newThreshold = distanceFunction.updateThreshold(threshold, distance)
                val c = childPartitioners(x).getCandidates(k.tail, distanceFunction,
                  newThreshold, newDistanceAccu, globalId,x)

                c.map(c => (c._1,c._2,x))
              }
            }
          }
        } else {
          partitioner.mbrBounds.toList.flatMap{ case (shape, x) =>
            val distance = distanceFunction match {
              case LCSSDistance =>
                if (k.map(p => shape.approxMinDist(p)).min <= LCSSDistance.EPSILON) 0 else 1
              case EDRDistance =>
                if (k.map(p => shape.approxMinDist(p)).min <= EDRDistance.EPSILON) 0 else 1
            }
            val newDistanceAccu = distanceFunction.updateDistance(distanceAccu, distance)
            if (newDistanceAccu > threshold) {
              List.empty[(Trajectory, Double, Int)]
            } else {
              if (childPartitioners.isEmpty) {
                data.get(x).map((_, newDistanceAccu, x))
              } else {
                val newThreshold = distanceFunction.updateThreshold(threshold, distance)
                val c = childPartitioners(x).getCandidates(k, distanceFunction,
                  newThreshold, newDistanceAccu, globalId,x)


                c.map(c => (c._1,c._2,x))
              }
            }
          }
        }
    }
  }
}

class EmptyLocalTriePartitioner(override val level: Int,
                                     override val count: Int,
                                     allData: List[Trajectory])
  extends LocalTriePartitioner(null, Array.empty[LocalTriePartitioner],
    level, count: Int, data = None) {

  override def numPartitions: Int = 1

  override def getPartition(key: Any): Int = 0

  override def getCandidates(key: Shape, threshold: Double): List[Trajectory] = allData

  override def getCandidates(key: Any, distanceFunction: TrajectorySimilarity, threshold: Double,
                             distanceAccu: Double, some: Int, ex: Int): List[(Trajectory, Double, Int)] = {
    allData.map((_, distanceAccu, -1))
  }
}

object LocalTriePartitioner {
  def partition(data: Array[Trajectory]):
  (Array[Array[Trajectory]], LocalTriePartitioner) = {
    // get tree partitioner
    val points = data.map(x => (LocalTriePartitioner.getIndexedKey(x), x))

    val totalLevels = DITAConfigConstants.LOCAL_INDEXED_PIVOT_COUNT + 2
    if (points.isEmpty) {
      return (Array.empty[Array[Trajectory]],
        new EmptyLocalTriePartitioner(totalLevels, 0, List.empty[Trajectory]))
    }
    val dimension = points.take(1).head._1.head.coord.length
    val partitioner = partitionByLevel(points, dimension, totalLevels)

    // shuffle
    val shuffled = data.groupBy(t => partitioner.getPartition(t))
    ((0 until partitioner.numPartitions).map(i =>
      shuffled.getOrElse(i, Array.empty)).toArray, partitioner)
  }

  private def getIndexedKey(key: Any): Array[Point] = {
    key match {
      case t: Trajectory => t.points.head +: t.points.last +: t.getLocalIndexedPivot
      case _ => key.asInstanceOf[Array[Point]]
    }
  }

  private def partitionByLevel(rdd: Array[(Array[Point], Trajectory)],
                               dimension: Int, level: Int): LocalTriePartitioner = {
    val numPartitions = if (level > DITAConfigConstants.LOCAL_INDEXED_PIVOT_COUNT) {
      DITAConfigConstants.LOCAL_NUM_PARTITIONS
    } else {
      DITAConfigConstants.LOCAL_PIVOT_NUM_PARTITIONS
    }

    if (rdd.length <= DITAConfigConstants.LOCAL_MIN_NODE_SIZE) {
      return new EmptyLocalTriePartitioner(level, rdd.length, rdd.map(_._2).toList)
    }

    if (level > 1) {
      val rddWithKey = rdd.map(x => (x._1.head, (x._1.tail, x._2)))
      // println(numPartitions)
      val (partitionedData, partitioner) = LocalSTRPartitioner.partition(rddWithKey, dimension, numPartitions)

      val childPartitioners = partitionedData.map(childRDD =>
        partitionByLevel(childRDD.map(_._2), dimension, level - 1))

      LocalTriePartitioner(partitioner, childPartitioners, level, rdd.length, None)
    } else {
      val rddWithoutKey = rdd.map(x => (x._1.head, x._2))
      val (partitionedData, partitioner) = LocalSTRPartitioner.partition(rddWithoutKey,
        dimension, numPartitions)

      LocalTriePartitioner(partitioner, Array.empty, level,
        rdd.length, Some(partitionedData.map(x => x.map(_._2).toList)))
    }
  }
}