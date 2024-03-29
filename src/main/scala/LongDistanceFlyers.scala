import org.apache.kafka.clients.consumer.ConsumerConfig.AUTO_OFFSET_RESET_CONFIG
import org.apache.kafka.common.serialization.{Deserializer, Serde, Serializer}
import org.apache.kafka.streams.scala.ImplicitConversions._
import org.apache.kafka.streams.scala.kstream.Materialized
import org.apache.kafka.streams.scala.serialization.Serdes
import org.apache.kafka.streams.scala.{ByteArrayKeyValueStore, StreamsBuilder}
import org.apache.kafka.streams.state.Stores
import org.apache.kafka.streams.{KafkaStreams, StreamsConfig}

import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Properties
import scala.math.abs


case class Longbee(x: Int = 0, y: Int = 0, sum: Int = 0)

object Longbee {
    implicit object LongbeeSerde extends Serde[Longbee] {
        override def serializer(): Serializer[Longbee] = (_: String, data: Longbee) => {
            s"${data.x},${data.y},${data.sum}".getBytes
        }

        override def deserializer(): Deserializer[Longbee] = (_: String, data: Array[Byte]) => {
            val pattern = new String(data, StandardCharsets.UTF_8)
            val res = Option(pattern).filter(_.nonEmpty).map(_.split(",").map(_.toInt)).map(pair => (pair(0), pair(1), pair(2))).getOrElse(0, 0, 0)
            Longbee(x = res._1, y = res._2, sum = res._3)
        }
    }
}

object LongDistanceFlyers extends App {

    import Serdes._

    val props = new Properties()
    props.put(StreamsConfig.APPLICATION_ID_CONFIG, "velocity-application")
    props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092")
    props.put(AUTO_OFFSET_RESET_CONFIG, "earliest")
    props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.stringSerde.getClass)
    props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.stringSerde.getClass)
    props.put(StreamsConfig.CACHE_MAX_BYTES_BUFFERING_CONFIG, 0)


    val streams: KafkaStreams = new KafkaStreams(makeLongDistanceFlyersTopology(10, "events", "long-distance-travellers"), props)

    def makeLongDistanceFlyersTopology(K: Int, inputTopic: String, outputTopic: String) = {
        implicit val materializer: Materialized[String, Longbee, ByteArrayKeyValueStore] =
            Materialized.as[String, Longbee](Stores.inMemoryKeyValueStore("long-store"))

        val builder = new StreamsBuilder
        val stream = builder.stream[String, String](inputTopic)

        stream.groupByKey.aggregate(Longbee())(Function.untupled {
            case (_: String, data: String, Longbee(x, y, sum)) if sum <= 10 => Longbee(data.split(",")(1).toInt,
                data.split(",")(2).toInt, sum + abs(data.split(",")(1).toInt - x) * abs(data.split(",")(2).toInt - y))
            case (_: String, data: String, Longbee(x, y, sum)) if sum > 10 => Longbee(data.split(",")(1).toInt,
                data.split(",")(2).toInt, Integer.MIN_VALUE)
        })(materializer).toStream.filter((_: String, data: Longbee) => data.sum > 10).mapValues({
            case (key: String, data: Longbee) =>
                println(key)
                key
        }: (String, Longbee) => String).to(outputTopic)

        builder.build()
    }

    streams.start()

    sys.ShutdownHookThread {
        streams.close(Duration.ofSeconds(10))
    }

}


