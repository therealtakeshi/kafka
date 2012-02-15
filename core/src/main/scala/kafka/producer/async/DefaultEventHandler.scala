/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.producer.async

import kafka.api.ProducerRequest
import kafka.serializer.Encoder
import java.util.Properties
import kafka.producer._
import kafka.utils.{ZKConfig, Utils, Logging}
import kafka.cluster.{Partition, Broker}
import collection.mutable.{ListBuffer, HashMap}
import scala.collection.Map
import kafka.common.{FailedToSendMessageException, InvalidPartitionException, NoBrokersForPartitionException}
import kafka.message.{Message, NoCompressionCodec, ByteBufferMessageSet}

class DefaultEventHandler[K,V](config: ProducerConfig,                               // this api is for testing
                               private val partitioner: Partitioner[K],              // use the other constructor
                               private val encoder: Encoder[V],
                               private val producerPool: ProducerPool,
                               private val populateProducerPool: Boolean,
                               private var brokerPartitionInfo: BrokerPartitionInfo)
  extends EventHandler[K,V] with Logging {

  private val lock = new Object()
  private val zkEnabled = Utils.propertyExists(config.zkConnect)
  if(brokerPartitionInfo == null) {
    zkEnabled match {
      case true =>
        val zkProps = new Properties()
        zkProps.put("zk.connect", config.zkConnect)
        zkProps.put("zk.sessiontimeout.ms", config.zkSessionTimeoutMs.toString)
        zkProps.put("zk.connectiontimeout.ms", config.zkConnectionTimeoutMs.toString)
        zkProps.put("zk.synctime.ms", config.zkSyncTimeMs.toString)
        brokerPartitionInfo = new ZKBrokerPartitionInfo(new ZKConfig(zkProps), producerCbk)
      case false =>
        brokerPartitionInfo = new ConfigBrokerPartitionInfo(config)
    }
  }

  // pool of producers, one per broker
  if(populateProducerPool) {
    val allBrokers = brokerPartitionInfo.getAllBrokerInfo
    allBrokers.foreach(b => producerPool.addProducer(new Broker(b._1, b._2.host, b._2.host, b._2.port)))
  }

  /**
   * Callback to add a new producer to the producer pool. Used by ZKBrokerPartitionInfo
   * on registration of new broker in zookeeper
   * @param bid the id of the broker
   * @param host the hostname of the broker
   * @param port the port of the broker
   */
  private def producerCbk(bid: Int, host: String, port: Int) =  {
    if(populateProducerPool) producerPool.addProducer(new Broker(bid, host, host, port))
    else debug("Skipping the callback since populateProducerPool = false")
  }

  def handle(events: Seq[ProducerData[K,V]]) {
    lock synchronized {
     val serializedData = serialize(events)
     handleSerializedData(serializedData, config.producerRetries)
    }
  }

  private def handleSerializedData(messages: Seq[ProducerData[K,Message]], requiredRetries: Int) {
      val partitionedData = partitionAndCollate(messages)
      for ( (brokerid, eventsPerBrokerMap) <- partitionedData) {
        if (logger.isTraceEnabled)
          eventsPerBrokerMap.foreach(partitionAndEvent => trace("Handling event for Topic: %s, Broker: %d, Partition: %d"
            .format(partitionAndEvent._1, brokerid, partitionAndEvent._2)))
        val messageSetPerBroker = groupMessagesToSet(eventsPerBrokerMap)

        try {
          send(brokerid, messageSetPerBroker)
        }
        catch {
          case t =>
            warn("error sending data to broker " + brokerid, t)
            var numRetries = 0
            val eventsPerBroker = new ListBuffer[ProducerData[K,Message]]
            eventsPerBrokerMap.foreach(e => eventsPerBroker.appendAll(e._2))
            while (numRetries < requiredRetries) {
              numRetries +=1
              Thread.sleep(config.producerRetryBackoffMs)
              try {
                brokerPartitionInfo.updateInfo
                handleSerializedData(eventsPerBroker, 0)
                return
              }
              catch {
                case t => warn("error sending data to broker " + brokerid + " in " + numRetries + " retry", t)
              }
            }
            throw new FailedToSendMessageException("can't send data after " + numRetries + " retries", t)
        }
      }
  }

  def serialize(events: Seq[ProducerData[K,V]]): Seq[ProducerData[K,Message]] = {
    events.map(e => new ProducerData[K,Message](e.getTopic, e.getKey, e.getData.map(m => encoder.toMessage(m))))
  }

  def partitionAndCollate(events: Seq[ProducerData[K,Message]]): Map[Int, Map[(String, Int), Seq[ProducerData[K,Message]]]] = {
    val ret = new HashMap[Int, Map[(String, Int), Seq[ProducerData[K,Message]]]]
    for (event <- events) {
      val topicPartitionsList = getPartitionListForTopic(event)
      val totalNumPartitions = topicPartitionsList.length

      val partitionIndex = getPartition(event.getKey, totalNumPartitions)
      val brokerPartition = topicPartitionsList(partitionIndex)

      var dataPerBroker: HashMap[(String, Int), Seq[ProducerData[K,Message]]] = null
      ret.get(brokerPartition.brokerId) match {
        case Some(element) =>
          dataPerBroker = element.asInstanceOf[HashMap[(String, Int), Seq[ProducerData[K,Message]]]]
        case None =>
          dataPerBroker = new HashMap[(String, Int), Seq[ProducerData[K,Message]]]
          ret.put(brokerPartition.brokerId, dataPerBroker)
      }

      val topicAndPartition = (event.getTopic, brokerPartition.partId)
      var dataPerTopicPartition: ListBuffer[ProducerData[K,Message]] = null
      dataPerBroker.get(topicAndPartition) match {
        case Some(element) =>
          dataPerTopicPartition = element.asInstanceOf[ListBuffer[ProducerData[K,Message]]]
        case None =>
          dataPerTopicPartition = new ListBuffer[ProducerData[K,Message]]
          dataPerBroker.put(topicAndPartition, dataPerTopicPartition)
      }
      dataPerTopicPartition.append(event)
    }
    ret
  }

  private def getPartitionListForTopic(pd: ProducerData[K,Message]): Seq[Partition] = {
    debug("Getting the number of broker partitions registered for topic: " + pd.getTopic)
    val topicPartitionsList = brokerPartitionInfo.getBrokerPartitionInfo(pd.getTopic).toSeq
    debug("Broker partitions registered for topic: " + pd.getTopic + " = " + topicPartitionsList)
    val totalNumPartitions = topicPartitionsList.length
    if(totalNumPartitions == 0) throw new NoBrokersForPartitionException("Partition = " + pd.getKey)
    topicPartitionsList
  }

  /**
   * Retrieves the partition id and throws an InvalidPartitionException if
   * the value of partition is not between 0 and numPartitions-1
   * @param key the partition key
   * @param numPartitions the total number of available partitions
   * @returns the partition id
   */
  private def getPartition(key: K, numPartitions: Int): Int = {
    if(numPartitions <= 0)
      throw new InvalidPartitionException("Invalid number of partitions: " + numPartitions +
              "\n Valid values are > 0")
    val partition = if(key == null) Utils.getNextRandomInt(numPartitions)
                    else partitioner.partition(key , numPartitions)
    if(partition < 0 || partition >= numPartitions)
      throw new InvalidPartitionException("Invalid partition id : " + partition +
              "\n Valid values are in the range inclusive [0, " + (numPartitions-1) + "]")
    partition
  }

  private def send(brokerId: Int, messagesPerTopic: Map[(String, Int), ByteBufferMessageSet]) {
    if(messagesPerTopic.size > 0) {
      val requests = messagesPerTopic.map(f => new ProducerRequest(f._1._1, f._1._2, f._2)).toArray
      val syncProducer = producerPool.getProducer(brokerId)
      syncProducer.multiSend(requests)
      trace("kafka producer sent messages for topics %s to broker %s:%d"
        .format(messagesPerTopic, syncProducer.config.host, syncProducer.config.port))
    }
  }

  private def groupMessagesToSet(eventsPerTopicAndPartition: Map[(String,Int), Seq[ProducerData[K,Message]]]): Map[(String, Int), ByteBufferMessageSet] = {
    /** enforce the compressed.topics config here.
     *  If the compression codec is anything other than NoCompressionCodec,
     *    Enable compression only for specified topics if any
     *    If the list of compressed topics is empty, then enable the specified compression codec for all topics
     *  If the compression codec is NoCompressionCodec, compression is disabled for all topics
     */

    val messagesPerTopicPartition = eventsPerTopicAndPartition.map { e =>
      {
        val topicAndPartition = e._1
        val produceData = e._2
        val messages = new ListBuffer[Message]
        produceData.map(p => messages.appendAll(p.getData))

        ( topicAndPartition,
          config.compressionCodec match {
            case NoCompressionCodec =>
              trace("Sending %d messages with no compression to topic %s on partition %d"
                  .format(messages.size, topicAndPartition._1, topicAndPartition._2))
              new ByteBufferMessageSet(NoCompressionCodec, messages: _*)
            case _ =>
              config.compressedTopics.size match {
                case 0 =>
                  trace("Sending %d messages with compression codec %d to topic %s on partition %d"
                      .format(messages.size, config.compressionCodec.codec, topicAndPartition._1, topicAndPartition._2))
                  new ByteBufferMessageSet(config.compressionCodec, messages: _*)
                case _ =>
                  if(config.compressedTopics.contains(topicAndPartition._1)) {
                    trace("Sending %d messages with compression codec %d to topic %s on partition %d"
                        .format(messages.size, config.compressionCodec.codec, topicAndPartition._1, topicAndPartition._2))
                    new ByteBufferMessageSet(config.compressionCodec, messages: _*)
                  }
                  else {
                    trace("Sending %d messages to topic %s and partition %d with no compression as %s is not in compressed.topics - %s"
                        .format(messages.size, topicAndPartition._1, topicAndPartition._2, topicAndPartition._1,
                        config.compressedTopics.toString))
                    new ByteBufferMessageSet(NoCompressionCodec, messages: _*)
                  }
              }
          }
        )
      }
    }
    messagesPerTopicPartition
  }

  def close() {
    if (producerPool != null)
      producerPool.close    
    if (brokerPartitionInfo != null)
      brokerPartitionInfo.close
  }
}