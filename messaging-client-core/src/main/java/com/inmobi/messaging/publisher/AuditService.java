package com.inmobi.messaging.publisher;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.thrift.TException;
import org.apache.thrift.TSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.inmobi.audit.thrift.AuditMessage;
import com.inmobi.messaging.ClientConfig;
import com.inmobi.messaging.Message;
import com.inmobi.messaging.util.AuditUtil;

class AuditService {

  private static final String WINDOW_SIZE_KEY = "window.size.sec";
  private static final String AGGREGATE_WINDOW_KEY = "aggregate.window.sec";
  private static final int DEFAULT_WINDOW_SIZE = 60;
  private static final int DEFAULT_AGGREGATE_WINDOW_SIZE = 60;
  private int windowSize;
  private int aggregateWindowSize;
  final ConcurrentHashMap<String, AuditCounterAccumulator> topicAccumulatorMap = new ConcurrentHashMap<String, AuditCounterAccumulator>();
  private final String tier = "publisher";
  private ScheduledThreadPoolExecutor executor;
  private boolean isInit = false;
  private AuditWorker worker;

  private static final Logger LOG = LoggerFactory.getLogger(AuditService.class);
  private AbstractMessagePublisher publisher;
  private String hostname;

  class AuditWorker implements Runnable {
    private final TSerializer serializer = new TSerializer();
    @Override
    public void run() {
      try {
        LOG.info("Running the AuditWorker");
        for (Entry<String, AuditCounterAccumulator> entry : topicAccumulatorMap
            .entrySet()) {
          String topic = entry.getKey();
          AuditCounterAccumulator accumulator = entry.getValue();
          Map<Long, AtomicLong> received = accumulator.getReceived();
          Map<Long, AtomicLong> sent = accumulator.getSent();
          accumulator.reset(); // resetting before creating packet to make sure
                               // that during creation of packet no more writes
                               // should occur to previous counters
          if (received.size() == 0 && sent.size() == 0) {
            LOG.info("Not publishing audit packet as all the metric counters are 0");
            return;
          }
          AuditMessage packet = createPacket(topic, received, sent);
          publishPacket(packet);

        }
      } catch (Throwable e) {// catching general exception so that thread should
                             // not get aborted
        LOG.error("Error while publishing the audit message", e);
      }

    }

    private void publishPacket(AuditMessage packet) {
      try {
        LOG.debug("Publishing audit packet" + packet);
        publisher.publish(AuditUtil.AUDIT_STREAM_TOPIC_NAME,
            new Message(ByteBuffer.wrap(serializer.serialize(packet))));
      } catch (TException e) {
        LOG.error("Error while serializing the audit packet " + packet, e);
      }
    }

    private AuditMessage createPacket(String topic,
        Map<Long, AtomicLong> received, Map<Long, AtomicLong> sent) {
      Map<Long, Long> finalReceived = new HashMap<Long, Long>();
      Map<Long, Long> finalSent = new HashMap<Long, Long>();

      // TODO find a better way of converting Map<Long,AtomicLong> to
      // Map<Long,Long>;if any
      for (Entry<Long, AtomicLong> entry : received.entrySet()) {
        finalReceived.put(entry.getKey(), entry.getValue().get());
      }

      for (Entry<Long, AtomicLong> entry : sent.entrySet()) {
        finalSent.put(entry.getKey(), entry.getValue().get());
      }
      long currentTime = new Date().getTime();
      AuditMessage packet = new AuditMessage(currentTime, topic, tier,
          hostname, windowSize, finalReceived, finalSent, null, null);
      return packet;
    }

    public synchronized void flush() {
      run();
    }

  }

  AuditService(AbstractMessagePublisher publisher) {
    this.publisher = publisher;
  }

  public synchronized void init() throws IOException {
    if (isInit)
      return;
    init(new ClientConfig());
  }

  public synchronized void init(ClientConfig config) throws IOException {
    if (isInit)
      return;
    windowSize = config.getInteger(WINDOW_SIZE_KEY, DEFAULT_WINDOW_SIZE);
    aggregateWindowSize = config.getInteger(AGGREGATE_WINDOW_KEY,
        DEFAULT_AGGREGATE_WINDOW_SIZE);
    executor = new ScheduledThreadPoolExecutor(1);
    try {
      hostname = InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      LOG.error("Unable to find the hostanme of the local box,audit packets won't contain hostname");
      hostname = "";
    }
    worker = new AuditWorker();
    executor.scheduleWithFixedDelay(worker, aggregateWindowSize,
        aggregateWindowSize, TimeUnit.SECONDS);
    // setting init flag to true
    isInit = true;
  }

  private AuditCounterAccumulator getAccumulator(String topic) {
    if (!topicAccumulatorMap.containsKey(topic))
      topicAccumulatorMap.putIfAbsent(topic, new AuditCounterAccumulator(
          windowSize));
    return topicAccumulatorMap.get(topic);
  }

  public synchronized void close() {
    if (executor != null) {
    executor.shutdown();
    }
  }

  public synchronized void flush() {
    if (worker != null) {
      worker.flush(); // flushing the last audit packet during shutdown
    }
  }


  public void incrementSent(String topicName, Long timestamp) {
    AuditCounterAccumulator accumulator = getAccumulator(topicName);
    accumulator.incrementSent(timestamp);
  }

}
