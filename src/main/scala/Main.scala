import org.apache.spark.SparkConf
import org.apache.spark.SparkContext

object Main extends App {

  val config = KafkaConsumerConfig(
    brokers="localhost:9092",
    topic="testtopic1",
    offsetFilePath="/user/osboxes/myoffsets.txt",
    autoResetPolicy="earliest"
  )
  val kafkaConsumer = new KafkaConsumerBatch(config)

  println("DEBUG: starting now")
  val conf = new SparkConf().setAppName("job-kafka-1")
  val sc = new SparkContext(conf)
  val myRdd = kafkaConsumer.execute(sc)
  println("DEBUG: counting rdd")
  println(myRdd.count())
  println("DEBUG: ending now")
}
