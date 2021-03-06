package spark.rdd

import spark._
import org.apache.hadoop.mapred.{FileInputFormat, SequenceFileInputFormat, JobConf, Reporter}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.{NullWritable, BytesWritable}
import org.apache.hadoop.util.ReflectionUtils
import org.apache.hadoop.fs.Path
import java.io.{File, IOException, EOFException}
import java.text.NumberFormat

private[spark] class CheckpointRDDSplit(val index: Int) extends Split {}

/**
 * This RDD represents a RDD checkpoint file (similar to HadoopRDD).
 */
private[spark]
class CheckpointRDD[T: ClassManifest](sc: SparkContext, val checkpointPath: String)
  extends RDD[T](sc, Nil) {

  @transient val fs = new Path(checkpointPath).getFileSystem(sc.hadoopConfiguration)

  @transient val splits_ : Array[Split] = {
    val dirContents = fs.listStatus(new Path(checkpointPath))
    val splitFiles = dirContents.map(_.getPath.toString).filter(_.contains("part-")).sorted
    val numSplits = splitFiles.size
    if (!splitFiles(0).endsWith(CheckpointRDD.splitIdToFile(0)) ||
        !splitFiles(numSplits-1).endsWith(CheckpointRDD.splitIdToFile(numSplits-1))) {
      throw new SparkException("Invalid checkpoint directory: " + checkpointPath)
    }
    Array.tabulate(numSplits)(i => new CheckpointRDDSplit(i))
  }

  checkpointData = Some(new RDDCheckpointData[T](this))
  checkpointData.get.cpFile = Some(checkpointPath)

  override def getSplits = splits_

  override def getPreferredLocations(split: Split): Seq[String] = {
    val status = fs.getFileStatus(new Path(checkpointPath))
    val locations = fs.getFileBlockLocations(status, 0, status.getLen)
    locations.headOption.toList.flatMap(_.getHosts).filter(_ != "localhost")
  }

  override def compute(split: Split, context: TaskContext): Iterator[T] = {
    val file = new Path(checkpointPath, CheckpointRDD.splitIdToFile(split.index))
    CheckpointRDD.readFromFile(file, context)
  }

  override def checkpoint() {
    // Do nothing. CheckpointRDD should not be checkpointed.
  }
}

private[spark] object CheckpointRDD extends Logging {

  def splitIdToFile(splitId: Int): String = {
    "part-%05d".format(splitId)
  }

  def writeToFile[T](path: String, blockSize: Int = -1)(ctx: TaskContext, iterator: Iterator[T]) {
    val outputDir = new Path(path)
    val fs = outputDir.getFileSystem(new Configuration())

    val finalOutputName = splitIdToFile(ctx.splitId)
    val finalOutputPath = new Path(outputDir, finalOutputName)
    val tempOutputPath = new Path(outputDir, "." + finalOutputName + "-attempt-" + ctx.attemptId)

    if (fs.exists(tempOutputPath)) {
      throw new IOException("Checkpoint failed: temporary path " +
        tempOutputPath + " already exists")
    }
    val bufferSize = System.getProperty("spark.buffer.size", "65536").toInt

    val fileOutputStream = if (blockSize < 0) {
      fs.create(tempOutputPath, false, bufferSize)
    } else {
      // This is mainly for testing purpose
      fs.create(tempOutputPath, false, bufferSize, fs.getDefaultReplication, blockSize)
    }
    val serializer = SparkEnv.get.serializer.newInstance()
    val serializeStream = serializer.serializeStream(fileOutputStream)
    serializeStream.writeAll(iterator)
    serializeStream.close()

    if (!fs.rename(tempOutputPath, finalOutputPath)) {
      if (!fs.exists(finalOutputPath)) {
        fs.delete(tempOutputPath, false)
        throw new IOException("Checkpoint failed: failed to save output of task: "
          + ctx.attemptId + " and final output path does not exist")
      } else {
        // Some other copy of this task must've finished before us and renamed it
        logInfo("Final output path " + finalOutputPath + " already exists; not overwriting it")
        fs.delete(tempOutputPath, false)
      }
    }
  }

  def readFromFile[T](path: Path, context: TaskContext): Iterator[T] = {
    val fs = path.getFileSystem(new Configuration())
    val bufferSize = System.getProperty("spark.buffer.size", "65536").toInt
    val fileInputStream = fs.open(path, bufferSize)
    val serializer = SparkEnv.get.serializer.newInstance()
    val deserializeStream = serializer.deserializeStream(fileInputStream)

    // Register an on-task-completion callback to close the input stream.
    context.addOnCompleteCallback(() => deserializeStream.close())

    deserializeStream.asIterator.asInstanceOf[Iterator[T]]
  }

  // Test whether CheckpointRDD generate expected number of splits despite
  // each split file having multiple blocks. This needs to be run on a
  // cluster (mesos or standalone) using HDFS.
  def main(args: Array[String]) {
    import spark._

    val Array(cluster, hdfsPath) = args
    val sc = new SparkContext(cluster, "CheckpointRDD Test")
    val rdd = sc.makeRDD(1 to 10, 10).flatMap(x => 1 to 10000)
    val path = new Path(hdfsPath, "temp")
    val fs = path.getFileSystem(new Configuration())
    sc.runJob(rdd, CheckpointRDD.writeToFile(path.toString, 1024) _)
    val cpRDD = new CheckpointRDD[Int](sc, path.toString)
    assert(cpRDD.splits.length == rdd.splits.length, "Number of splits is not the same")
    assert(cpRDD.collect.toList == rdd.collect.toList, "Data of splits not the same")
    fs.delete(path)
  }
}
