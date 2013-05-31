package com.inmobi.messaging.consumer.audit;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.thrift.TDeserializer;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.inmobi.audit.thrift.AuditMessage;
import com.inmobi.messaging.ClientConfig;
import com.inmobi.messaging.Message;
import com.inmobi.messaging.consumer.EndOfStreamException;
import com.inmobi.messaging.consumer.MessageConsumer;
import com.inmobi.messaging.consumer.MessageConsumerFactory;
import com.inmobi.messaging.util.AuditDBHelper;
import com.inmobi.messaging.util.AuditUtil;

/**
 * This class is responsible for reading audit packets,aggregating stats in
 * memory for some time and than performing batch update of the DB
 *
 * @author rohit.kochar
 *
 */
class AuditStatsFeeder implements Runnable {

  private class TupleKey {
    public TupleKey(Date timestamp, String tier, String topic, String hostname,
                    String cluster) {
      this.timestamp = timestamp;
      this.tier = tier;
      this.topic = topic;
      this.hostname = hostname;
      this.cluster = cluster;
    }

    @Override
    public int hashCode() {
      final int prime = 31;
      int result = 1;
      result = prime * result + getOuterType().hashCode();
      result = prime * result + ((cluster == null) ? 0 : cluster.hashCode());
      result = prime * result + ((hostname == null) ? 0 : hostname.hashCode());
      result = prime * result + ((tier == null) ? 0 : tier.hashCode());
      result = prime * result
          + ((timestamp == null) ? 0 : timestamp.hashCode());
      result = prime * result + ((topic == null) ? 0 : topic.hashCode());
      return result;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj)
        return true;
      if (obj == null)
        return false;
      if (getClass() != obj.getClass())
        return false;
      TupleKey other = (TupleKey) obj;
      if (!getOuterType().equals(other.getOuterType()))
        return false;
      if (cluster == null) {
        if (other.cluster != null)
          return false;
      } else if (!cluster.equals(other.cluster))
        return false;
      if (hostname == null) {
        if (other.hostname != null)
          return false;
      } else if (!hostname.equals(other.hostname))
        return false;
      if (tier == null) {
        if (other.tier != null)
          return false;
      } else if (!tier.equals(other.tier))
        return false;
      if (timestamp == null) {
        if (other.timestamp != null)
          return false;
      } else if (!timestamp.equals(other.timestamp))
        return false;
      if (topic == null) {
        if (other.topic != null)
          return false;
      } else if (!topic.equals(other.topic))
        return false;
      return true;
    }

    Date timestamp;
    String tier, topic, hostname, cluster;

    private AuditStatsFeeder getOuterType() {
      return AuditStatsFeeder.this;
    }
  }

  private Map<TupleKey, Tuple> tuples = new HashMap<TupleKey, Tuple>();
  private static final String MESSAGE_CLIENT_CONF_FILE = "audit-consumer-conf.properties";
  private static final String ROOT_DIR_KEY = "databus.consumer.rootdirs";
  private static final Logger LOG = LoggerFactory
      .getLogger(AuditStatsFeeder.class);
  private static final String CONSUMER_CLASSNAME = "com.inmobi.messaging.consumer.databus.DatabusConsumer";
  private static final String CONSUMER_NAME = "audit-consumer";
  private final String clusterName;
  private MessageConsumer consumer = null;

  private boolean isStop = false;
  private static final String MESSAGES_PER_BATCH_KEY = "messages.batch.num";
  private int DEFAULT_MSG_PER_BATCH = 1000;
  private int msgsPerBatch;
  private TDeserializer deserializer = new TDeserializer();
  private final ClientConfig config;
  private final static long RETRY_INTERVAL = 60000;
  private final static String CHECKPOINT_DIR = "/usr/local/databus/";
  private final static String CHECKPOINT_DIR_KEY = "messaging.consumer.checkpoint.dir";
  private final String rootDir;
  private Thread thread;
  private static final String START_TIME_KEY = "messaging.consumer.absolute.starttime";

  /**
   *
   * @param clusterName
   * @param fromTime
   * @param rootDir
   *          path of _audit stream till /databus/system
   * @throws IOException
   */
  public AuditStatsFeeder(String clusterName, String rootDir)
      throws IOException {
    this.clusterName = clusterName;
    config = ClientConfig.loadFromClasspath(MESSAGE_CLIENT_CONF_FILE);
    this.rootDir = rootDir;
    consumer = getConsumer(config);
    msgsPerBatch = config.getInteger(MESSAGES_PER_BATCH_KEY,
        DEFAULT_MSG_PER_BATCH);
    LOG.info("Messages per batch " + msgsPerBatch);

  }

  private long getLowerBoundary(long receivedTime, int windowSize) {
    long window = receivedTime - (receivedTime % (windowSize * 1000));
    return window;
  }

  private AuditMessage[] getAuditMessagesAlignedAtMinuteBoundary(
      AuditMessage message) {
    AuditMessage[] messages = new AuditMessage[2];
    int windowSize = message.getWindowSize();
    long receivedTime = message.getTimestamp();
    long lowerBoundary = getLowerBoundary(receivedTime, windowSize);
    long upperBoundary = lowerBoundary + windowSize * 1000;
    messages[0] = new AuditMessage();
    messages[1] = new AuditMessage();
    messages[0].setTimestamp(lowerBoundary);
    messages[1].setTimestamp(upperBoundary);
    for (AuditMessage msg : messages) {
      msg.setTier(message.getTier());
      msg.setHostname(message.getHostname());
      msg.setTopic(message.getTopic());
    }
    for (Entry<Long, Long> entry : message.getReceived().entrySet()) {
      long upperBoundaryTime = entry.getKey() + windowSize * 1000;
      if (upperBoundaryTime < receivedTime) {
        long received = entry.getValue();
        // dividing the recieved in proportion of offset of received time from
        // the minute boundary to total window size
        long received2 = ((receivedTime - lowerBoundary) / (windowSize * 1000))
            * received;
        long received1 = received = received2;
        messages[0].putToReceived(entry.getKey(), received1);
        messages[1].putToReceived(entry.getKey(), received2);
      } else {
        // all the received packets belong to same minute interval in which
        // audit is generated
        messages[1].putToReceived(entry.getKey(), entry.getValue());
      }
    }

    for (Entry<Long, Long> entry : message.getSent().entrySet()) {
      long upperBoundaryTime = entry.getKey() + windowSize * 1000;
      if (upperBoundaryTime < receivedTime) {
        long sent = entry.getValue();
        // dividing the recieved in proportion of offset of received time from
        // the minute boundary to total window size
        long sent2 = ((receivedTime - lowerBoundary) / (windowSize * 1000))
            * sent;
        long sent1 = sent = sent2;
        messages[0].putToSent(entry.getKey(), sent1);
        messages[1].putToSent(entry.getKey(), sent2);
      } else {
        // all the received packets belong to same minute interval in which
        // audit is generated
        messages[1].putToReceived(entry.getKey(), entry.getValue());
      }
    }
    return messages;
  }

  private void addTuples(AuditMessage msg) {
    if (msg == null)
      return;
    int windowSize = msg.getWindowSize();
    for (AuditMessage message : getAuditMessagesAlignedAtMinuteBoundary(msg)) {
      long messageReceivedTime = message.getTimestamp();
      for (long timestamp : message.getReceived().keySet()) {
        long upperBoundaryTime = timestamp + windowSize * 1000;
        long latency = messageReceivedTime - upperBoundaryTime;
        LatencyColumns latencyColumn = LatencyColumns.getLatencyColumn(latency);
        if (latency < 0) {
          LOG.error("Error scenario,check that time is in sync across tiers,audit"
              + "message has time stamp "+ messageReceivedTime+ " and source time is "
              + timestamp+ " for tier= "+ message.getTier());
          continue;
        }
        TupleKey key = new TupleKey(new Date(upperBoundaryTime),
            message.getTier(), message.getTopic(), message.getHostname(),
            clusterName);
        Map<LatencyColumns, Long> latencyCountMap = new HashMap<LatencyColumns, Long>();
        Tuple tuple;
        if (tuples.containsKey(key)) {
          tuple = tuples.get(key);
        } else {
          tuple = new Tuple(message.getHostname(), message.getTier(),
              clusterName, new Date(upperBoundaryTime), message.getTopic());
        }
        if (tuple.getLatencyCountMap() != null) {
          latencyCountMap.putAll(tuple.getLatencyCountMap());
        }
        long prevValue = 0l;
        if (latencyCountMap.get(latencyColumn) != null)
          prevValue = latencyCountMap.get(latencyColumn);
        latencyCountMap.put(latencyColumn, prevValue + latency);
        tuple.setLatencyCountMap(latencyCountMap);
        tuples.put(key, tuple);
      }
      for (long timestamp : message.getSent().keySet()) {
        long upperBoundaryTime = timestamp + windowSize * 1000;
        TupleKey key = new TupleKey(new Date(upperBoundaryTime),
            message.getTier(), message.getTopic(), message.getHostname(),
            clusterName);
        Tuple tuple;
        if (tuples.containsKey(key)) {
          tuple = tuples.get(key);
        } else {
          tuple = new Tuple(message.getHostname(), message.getTier(),
              clusterName, new Date(upperBoundaryTime), message.getTopic());
        }
        long sent = message.getSent().get(timestamp);
        tuple.setSent(tuple.getSent() + sent);
        tuples.put(key, tuple);
      }
    }
  }

  private MessageConsumer getConsumer(ClientConfig config) throws IOException {
    config.set(ROOT_DIR_KEY, rootDir);
    config.set(CHECKPOINT_DIR_KEY, CHECKPOINT_DIR);
    return MessageConsumerFactory.create(config, CONSUMER_CLASSNAME,
        AuditUtil.AUDIT_STREAM_TOPIC_NAME, CONSUMER_NAME);
  }

  private boolean updateDB(Set<Tuple> tuples) {
    return AuditDBHelper.update(tuples, null);
  }

  public void stop() {
    isStop = true;
    // Interrupting the thread in case its waiting for next message to be
    // available
    if (thread != null) {
      thread.interrupt();
    }
  }

  public void start() {
    thread = new Thread(this, "AuditStatsFeeder_" + clusterName);
    LOG.info("Starting thread " + thread.getName());
    thread.start();
  }

  public void join() {
    try {
      thread.join();
    } catch (InterruptedException e) {
      LOG.error("Exception while waiting for thread " + thread.getName()
          + " to join", e);
    }
  }

  @Override
  public void run() {
    LOG.info("Starting the run of audit feeder for cluster " + clusterName
        + " and start time " + config.getString(START_TIME_KEY));
    Message msg;
    AuditMessage auditMsg;
    try {
      while (!isStop) {
        int numOfMsgs = 0;
        while (!isStop && consumer == null) {
          // if a checkpoint is already present than from time would be ignored.
          try {
            consumer = getConsumer(config);
          } catch (IOException e) {
            LOG.error("Could not intialize the consumer,would re-try after "
                + RETRY_INTERVAL + "millis");
            try {
              Thread.sleep(RETRY_INTERVAL);
            } catch (InterruptedException e1) {
              LOG.error("Exception while sleeping", e1);
            }
          }
        }
        while (!isStop && numOfMsgs < msgsPerBatch) {
          try {
            msg = consumer.next();
            auditMsg = new AuditMessage();
            deserializer.deserialize(auditMsg, msg.getData().array());
            LOG.debug("Packet read is " + auditMsg);
            addTuples(auditMsg);
            numOfMsgs++;
          } catch (InterruptedException e) {
            LOG.error("Error while reading audit message ", e);
          } catch (TException e) {
            LOG.error("Exception in deserializing audit message");
          } catch (EndOfStreamException e) {
            LOG.info("End of stream reached,breaking the loop");
            break;
          }
        }
        Set<Tuple> tupleSet = new HashSet<Tuple>();
        tupleSet.addAll(tuples.values());
        if (updateDB(tupleSet)) {
          try {
            consumer.mark();
          } catch (IOException e) {
            LOG.error(
                "Failure in marking the consumer,Audit Messages  would be re processed",
                e);
          }
        } else {
          LOG.error("Updation to DB failed,resetting the consumer");
          try {
            consumer.reset();
          } catch (IOException e) {
            LOG.error("Exception while reseting the consumer,would re-intialize consumer in next run");
            consumer = null;
          }
        }
      }
    } finally {
      if (consumer != null)
        consumer.close();
    }
  }

  public String getClusterName() {
    return clusterName;
  }

}