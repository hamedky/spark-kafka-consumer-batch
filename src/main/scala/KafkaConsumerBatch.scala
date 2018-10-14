
import java.io.File

import java.nio.file._
import java.util.Properties
import scala.collection.JavaConversions._
import scala.collection.mutable.ListBuffer
import scala.util.control.Breaks._
import kafka.consumer.SimpleConsumer
import kafka.api.OffsetRequest
import kafka.api.OffsetResponse
import kafka.api.PartitionOffsetRequestInfo
import kafka.api.PartitionOffsetsResponse
import kafka.common.TopicAndPartition

import org.apache.kafka.clients.consumer._
import org.apache.kafka.clients.producer._
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkContext, SparkException}
import org.apache.spark.streaming._
import org.apache.spark.streaming.StreamingContext._
import org.apache.spark.streaming.kafka010._
import org.apache.spark.streaming.kafka010.LocationStrategies.PreferConsistent

import scala.language.higherKinds
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}



//--- Batch configuration ---//

// The offsetFilePath defines the absolute path of a file (with write-access) where the current offsets will be written by the job
// The autoResetPolicy defines the start offset reset policy to be used in case the offsetsFile is empty or not found. 2 possible values:
// - latest : in this case the last offset of the kafka partition is considered as start offset
// - earliest : in this case the first offset of the kafka partition is considered as start offset
// - none (default) : in this case the offset 0 is considered as start offset
case class KafkaConsumerConfig(brokers: String, topic: String, offsetFilePath: String, autoResetPolicy: String)




//--- Batch modules ---//

class KafkaConsumerBatch(kafkaConsumerConfig: KafkaConsumerConfig) {

  val kafkaParams = Map[String, String](
    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG -> kafkaConsumerConfig.brokers,
    "topic" -> kafkaConsumerConfig.topic,
    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG -> "org.apache.kafka.common.serialization.StringDeserializer",
    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG -> "org.apache.kafka.common.serialization.StringDeserializer"
  )

  // This method retrieves the earliest and latest offset available in the broker for each partition of the kafka topic
  def getKafkaEarliestLatestOffsets(): Array[OffsetRange] = {
    // Build a Properties object with the kafka config (to be used in the kafka producer)
    val configProperties = new Properties();
    configProperties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaConsumerConfig.brokers);
    configProperties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");
    configProperties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");

    // Instanciates a kafka producer to retrieve the list of partition for the given topic
    val producer = new KafkaProducer(configProperties)
    val topic = kafkaConsumerConfig.topic
    val partitionInfos = producer.partitionsFor(topic).toList;

    // For each of those partition :
    val offsetRangeList = new ListBuffer[OffsetRange]();
    for (partitionInfo <- partitionInfos) {
      // The breakable block is used to make break statements continuing to the next element of the for loop
      breakable {
        println("[DEBUG] Looking for offsets ranges for kafka partition "+partitionInfo.partition()+" in leader "+partitionInfo.leader().host()+":"+partitionInfo.leader().port());

        // Open a consumer instance to the leader broker of that partition
        val topicAndPartition = new TopicAndPartition(topic, partitionInfo.partition());
        val consumer = new SimpleConsumer(partitionInfo.leader().host(), partitionInfo.leader().port(), 10000, 64 * 1024, "kafka-replay");

        // Requests the latest offset available for this partition
        val requestInfoLatest = Map[TopicAndPartition, PartitionOffsetRequestInfo](topicAndPartition -> new PartitionOffsetRequestInfo(OffsetRequest.LatestTime, 1));
        val requestLatest = new OffsetRequest(requestInfoLatest, OffsetRequest.CurrentVersion, 0, "kafka-replay");
        val responseLatest = consumer.getOffsetsBefore(requestLatest);
        // If the query fails or if it does not contain the requested data we log an error and continue to the next partition
        if (responseLatest.hasError ||
          !responseLatest.offsetsGroupedByTopic.contains(topic) ||
          !responseLatest.offsetsGroupedByTopic(topic).contains(topicAndPartition) ||
          responseLatest.offsetsGroupedByTopic(topic)(topicAndPartition).offsets.size==0) {
          println("Error while getting latest offset for kafka partition "+partitionInfo.partition()+" in leader "+partitionInfo.leader().host()+":"+partitionInfo.leader().port());
          break;
        }
        val latestOffset = responseLatest.offsetsGroupedByTopic(topic)(topicAndPartition).offsets(0);

        val earliestOffset = kafkaConsumerConfig.autoResetPolicy match
        {
          case "latest" => latestOffset;
          case "earliest" => {
            // Requests the earliest offset available for this partition
            val requestInfoEarliest = Map[TopicAndPartition, PartitionOffsetRequestInfo](topicAndPartition -> new PartitionOffsetRequestInfo(OffsetRequest.EarliestTime, 1));
            val requestEarliest = new OffsetRequest(requestInfoEarliest, OffsetRequest.CurrentVersion, 0, "kafka-replay");
            val responseEarliest = consumer.getOffsetsBefore(requestEarliest);
            // If the query fails or if it does not contain the requested data we log an error and continue to the next partition
            if (responseEarliest.hasError ||
              !responseEarliest.offsetsGroupedByTopic.contains(topic) ||
              !responseEarliest.offsetsGroupedByTopic(topic).contains(topicAndPartition) ||
              responseEarliest.offsetsGroupedByTopic(topic)(topicAndPartition).offsets.size==0) {
              println("Error while getting earliest offset for kafka partition "+partitionInfo.partition()+" in leader "+partitionInfo.leader().host()+":"+partitionInfo.leader().port());
              break;
            }
            responseEarliest.offsetsGroupedByTopic(topic)(topicAndPartition).offsets(0);
          }
          case default => 0;
        }

        // Build a OffsetRange object with the earliest and latest offsets and add it to the list
        val offsetRange = OffsetRange(topic, partitionInfo.partition(), earliestOffset, latestOffset);
        offsetRangeList += offsetRange;
      }
    }
    // Return the array of the offset ranges
    offsetRangeList.toArray
  }


  // This method reads the latest offsets written in hdfs by the previous batch and fill the startOffsets of the kafkaOffsetRanges parameter
  def getHdfsLatestOffsets(inputFilePath: String, kafkaOffsetRanges: Array[OffsetRange]): Array[OffsetRange] = {

    try {
      // Read the offsets from hdfs file
      val hdfsOffsetMap = scala.io.Source.fromFile(inputFilePath)
        .getLines.toArray
        .map(_.split(":") match {case Array(i,j) => (i.toInt,j.toLong)})
        .toMap

      // Read from hdfs succeed : patch the kafka offset range and return it
      kafkaOffsetRanges.map (range =>
        if (hdfsOffsetMap.contains(range.partition)) {
          OffsetRange(range.topic, range.partition, hdfsOffsetMap.get(range.partition).get, range.untilOffset)
        }
        else {
          println("ERROR : Offset not found in HDFS for kafka partition " + range.partition);
          OffsetRange(range.topic, range.partition, range.fromOffset, range.untilOffset)
        })
    }
    catch {
      case e: Exception => {
        // Read from hdfs failed : return the kafka offset range as it is
        println("ERROR : Exception caught while reading offsets from HDFS file " + inputFilePath + ": " + e.getMessage());
        kafkaOffsetRanges
      }
    }
  }


  // This method saves the kafka latest offsets into hdfs so it can be used by the next batch
  def saveHdfsLatestOffsets(outputFilePath: String, kafkaOffsetRanges: Array[OffsetRange]) = {

    try {

      // Remove the file if it exists
      Files.deleteIfExists(Paths.get(outputFilePath));
      // Write the offsets into the file
      kafkaOffsetRanges.foreach(range => Files.write(Paths.get(outputFilePath), (range.partition+":"+range.untilOffset+"\n").getBytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND))

    }
    catch {
      case e: Exception => {
        // Save into hdfs failed
        println("ERROR : Exception caught while saving offsets into HDFS file " + outputFilePath + ": " + e.getMessage())
      }
    }
  }


  def retrieveOffsetRanges(): Array[OffsetRange] = {

    // Retrieve the earliest/latest offsets from kafka
    val kafkaOffsetRanges = getKafkaEarliestLatestOffsets
    println("[DEBUG] Kafka offset ranges : ")
    kafkaOffsetRanges foreach println

    // Patch the start offsets with the offsets saved in hdfs by the previous batch (latest offsets of last batch)
    val hdfsFilePath = kafkaConsumerConfig.offsetFilePath
    val patchedOffsetRanges = getHdfsLatestOffsets(hdfsFilePath, kafkaOffsetRanges)
    println("[DEBUG] Kafka offset ranges patched with HDFS : ")
    patchedOffsetRanges foreach println

    // Save the latest offsets in hdfs (to be used by the next batch)
    // TODO : For a better offset management, this save should be done after the process of RDD is done correctly. E.g. the RDD is saved successfully in HDFS.
    saveHdfsLatestOffsets(hdfsFilePath, patchedOffsetRanges)

    // Returns it
    patchedOffsetRanges
  }


  def execute(sparkCtx: SparkContext): RDD[(String, String)] = {

    // Get the offset ranges
    val offsetRanges = retrieveOffsetRanges()

    // Consume them from kafka
    val kafkaRdd : RDD[ConsumerRecord[String, String]] = KafkaUtils.createRDD(sparkCtx, kafkaParams, offsetRanges, PreferConsistent)
    kafkaRdd.map(r => (r.key,r.value))

  }
}
