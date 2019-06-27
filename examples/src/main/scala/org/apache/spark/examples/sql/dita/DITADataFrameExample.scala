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

package org.apache.spark.examples.sql.dita

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.expressions.dita.TrajectorySimilarityFunction
import org.apache.spark.sql.catalyst.expressions.dita.common.DITAConfigConstants
import org.apache.spark.sql.catalyst.expressions.dita.common.shape.{Point, Rectangle}
import org.apache.spark.sql.catalyst.expressions.dita.common.trajectory.Trajectory
import org.apache.spark.sql.execution.dita.index.learned.LearnedModel
import org.apache.spark.sql.execution.dita.util.NormalFileWriter

object DITADataFrameExample {

  case class TrajectoryRecord(id: Long, traj: Array[Array[Double]])

  private def getTrajectory(line: (String, Long)): TrajectoryRecord = {
    val points = line._1.split(";").map(_.split(","))
      .map(x => x.map(_.toDouble))
    TrajectoryRecord(line._2, points)
  }

  def main(args: Array[String]) {
    val spark = SparkSession
      .builder()
      .master("local[*]")
      .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .getOrCreate()

    // For implicit conversions like converting RDDs to DataFrames
    import spark.implicits._

    val trajs = spark.sparkContext
      .textFile("examples/src/main/resources/trajectory.txt")
      .zipWithIndex().map(getTrajectory)
      .filter(_.traj.length >= DITAConfigConstants.TRAJECTORY_MIN_LENGTH)
      .filter(_.traj.length <= DITAConfigConstants.TRAJECTORY_MAX_LENGTH)

    System.out.println(trajs.toString())

    val df1 = trajs.toDF()
    df1.createOrReplaceTempView("traj1")
    df1.createTrieIndex(df1("traj"), "traj_index1")

    LearnedModel.getInstance().init("./IPYNB/saved_models",6,"./IPYNB/parameters.txt")

    /*System.out.println(df1.toString())

    val df2 = spark.sparkContext
      .textFile("examples/src/main/resources/trajectory.txt")
      .zipWithIndex().map(getTrajectory)
      .filter(_.traj.length >= DITAConfigConstants.TRAJECTORY_MIN_LENGTH)
      .filter(_.traj.length <= DITAConfigConstants.TRAJECTORY_MAX_LENGTH)
      .toDF()
    df2.createTrieIndex(df2("traj"), "traj_index2")*/
    val thresholds = List( 0.075, 0.03,0.4, 6)
    val sids = List(63,  710,  282,  683, 1456, 1460, 1698, 1858, 1028, 1934,
      682, 945,  879, 1611, 1676, 1681, 1735, 1856, 1761, 1031, 1967,  685,
      264, 1620,  824, 1961,  601,  677,  124, 1437,   57, 1779,   67, 1467,
      1533, 1847, 1092, 1738,  212, 1845, 1622, 1947,  325, 1831, 1978,  430,
      81,  462, 1806, 1527, 1455, 1424,  304, 1640,  185, 657,  205, 1597,  756,
      1033, 1286, 1438,  440, 1563, 1767, 1512, 1579,  386, 1491,  232, 1494,  371,
      91,  585,  312,  532, 1987, 182,  414,  503, 1998, 1247,  491, 1423,  347,  979,
      1878, 1874, 1749,  536, 1144,  280,  438,  770, 1628, 1921, 1706, 1747,  287, 164)

    var i = 0
    for (sid <- sids) {

        if(trajs.filter(t => t.id == sid).take(1).length >0){
          for (threshold <- thresholds) {

    //val threshold = 0.01
    //val sid = 982
            //System.out.println("CHECK ;"+sid+";"+threshold+";;")
            System.out.println(i + "/" + sids.length)
            NormalFileWriter.instance.setBegin(sid,threshold)
            val queryTrajectory = Trajectory(trajs.filter(t => t.id == sid).take(1).head.traj.map(Point))
            val q = df1.trajectorySimilarityWithThresholdSearch(queryTrajectory, df1("traj"),
              TrajectorySimilarityFunction.DTW, threshold)

    //q.show()
            q.write.json("test/" + sid + "/" + (threshold))
          }
      }
      i = i+1
    }
        //System.out.println(new java.io.File(".").getCanonicalPath)
/*
        df1.trajectorySimilarityWithKNNSearch(queryTrajectory, df1("traj"),
          TrajectorySimilarityFunction.DTW, 100).show()

        df1.trajectorySimilarityWithThresholdJoin(df2, df1("traj"), df2("traj"),
          TrajectorySimilarityFunction.DTW, 0.005).show()

        df1.trajectorySimilarityWithKNNJoin(df2, df1("traj"), df2("traj"),
          TrajectorySimilarityFunction.DTW, 100).show()

        df1.trajectorySimilarityWithKNNJoin(df2, df1("traj"), df2("traj"),
          TrajectorySimilarityFunction.DTW, 100).show()

        val mbr = Rectangle(Point(Array(39.8, 116.2)), Point(Array(40.0, 116.4)))
        df1.trajectoryMBRRangeSearch(mbr, df1("traj")).show()

        val center = Point(Array(39.9, 116.3))
        val radius = 0.1
        df1.trajectoryCircleRangeSearch(center, radius, df1("traj")).show()
    */
    spark.stop()
  }
}
