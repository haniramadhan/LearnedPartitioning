package org.apache.spark.sql.execution.dita.util
import java.io.{BufferedWriter, File, FileWriter}

class NormalFileWriter private(var bw:BufferedWriter,var start:String){

  def Write(text:String) = {
    bw.write(start+text)
    bw.newLine()
    bw.flush()
  }
  def Close()={
    bw.close()
  }

  def setBegin(sid:Int,threshold:Double)  ={
    start = ";"+sid+";"+threshold+";"
  }

}

object NormalFileWriter{
  private val _instance = new NormalFileWriter(new BufferedWriter(new FileWriter(new File("test.csv"),true)),";;;")
  def instance() =
    _instance
}