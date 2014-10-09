/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.killrweather

import scala.concurrent.duration._
import akka.actor._
import akka.actor.SupervisorStrategy._
import akka.pattern.gracefulStop
import akka.util.Timeout
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming.StreamingContext
import org.apache.spark.streaming.kafka.KafkaUtils
import kafka.serializer.StringDecoder
import com.datastax.spark.connector.embedded.{Assertions, EmbeddedKafka}
import com.datastax.spark.connector.streaming._
import com.datastax.killrweather.api.WeatherApi

/**
 * NOTE: if [[NodeGuardian]] is ever put on an Akka router, multiple instances of the stream will
 * exist on the node. Might want to call 'union' on the streams in that case.
 */
class NodeGuardian(ssc: StreamingContext, kafka: EmbeddedKafka, settings: WeatherSettings)
  extends Actor with Assertions with ActorLogging {
  import com.datastax.killrweather.WeatherEvents._
  import Weather._
  import settings._

  implicit val timeout = Timeout(3.seconds)

  override val supervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1 minute) {
      case _: AssertionError           => Resume
      case _: NullPointerException     => Restart
      case _: IllegalArgumentException => Stop
      case _: Exception                => Escalate
    }

  /** Reads the 2014 data file and publish the raw data to Kafka for streaming. */
  context.actorOf(Props(new RawFeedActor(kafka.kafkaConfig, ssc, settings))) ! PublishFeed(5 to 5)

  /** Defines the work to be done on the raw data topic stream. */
  val rawInputStream = KafkaUtils.createStream[String, String, StringDecoder, StringDecoder](
    ssc, kafka.kafkaParams, Map(KafkaTopicRaw -> 1), StorageLevel.MEMORY_ONLY)

  ssc.checkpoint(SparkCheckpointDir)

  /** Streams in the raw data, converts to a type, saves to cassandra table. */
  rawInputStream.map { case (_,d) => d.split(",")}
    .map (RawWeatherData(_))
    .saveToCassandra(CassandraKeyspace, CassandraTableRaw)

  val temperature = context.actorOf(Props(new TemperatureActor(ssc, settings)), "temperature")

  val station = context.actorOf(Props(new WeatherStationActor(ssc, settings)), "weather-station")

  ssc.start()
  val tmp = context.actorOf(Props(new TemporaryReceiver(temperature, station))) // temporary
  tmp ! StartValidation

  override def postStop(): Unit = {
    log.info("Shutting down.")
    ssc.stop(stopSparkContext = true, stopGracefully = true)
  }

  def receive: Actor.Receive = {
    case TaskCompleted         =>
    case ValidationCompleted   => context stop tmp
    case e: GetTemperature     => temperature forward e
    case e: GetWeatherStation  => station forward e
    case e: GetSkyConditionLookup =>
    case e: TemperatureAggregate => log.info(s"Received $e")
    case PoisonPill            => gracefulShutdown()
  }

  def gracefulShutdown(): Unit = {
    context.children foreach (c => awaitCond(gracefulStop(c, timeout.duration).isCompleted, timeout.duration))
    log.info(s"Graceful stop completed.")
  }

}

// manual kickoff for today, hooking up REST calls
class TemporaryReceiver(temperature: ActorRef, weatherStation: ActorRef) extends Actor with ActorLogging with Assertions {
  import com.datastax.killrweather.WeatherEvents._

  var received = 0
  val expected = 2

  def receive: Actor.Receive = {
    case StartValidation =>
      temperature ! GetTemperature("010000:99999", 10, 2005)
      weatherStation ! GetWeatherStation("13280:99999")

    case e: Weather.TemperatureAggregate =>
      log.info(s"\n\nReceived $e"); received +=1; test()

    case e: Weather.WeatherStation =>
      log.info(s"\n\nReceived $e"); received +=1; test()
  }

  def test(): Unit =
    if(received == expected) context.parent ! ValidationCompleted
}
