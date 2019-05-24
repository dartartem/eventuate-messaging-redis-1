package io.eventuate.messaging.redis.consumer;

import io.lettuce.core.RedisCommandExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

public class ChannelProcessor {
  private Logger logger = LoggerFactory.getLogger(getClass());

  private String subscriptionIdentificationInfo;
  private CountDownLatch stopCountDownLatch = new CountDownLatch(1);
  private AtomicBoolean running = new AtomicBoolean(false);

  private String subscriberId;
  private String channel;
  private RedisMessageHandler messageHandler;
  private RedisTemplate<String, String> redisTemplate;
  private long timeInMillisecondsToSleepWhenKeyDoesNotExist;
  private long blockStreamTimeInMilliseconds;

  public ChannelProcessor(RedisTemplate<String, String> redisTemplate,
                          String subscriberId,
                          String channel,
                          RedisMessageHandler messageHandler,
                          String subscriptionIdentificationInfo,
                          long timeInMillisecondsToSleepWhenKeyDoesNotExist,
                          long blockStreamTimeInMilliseconds) {

    this.redisTemplate = redisTemplate;
    this.subscriberId = subscriberId;
    this.channel = channel;
    this.messageHandler = messageHandler;
    this.subscriptionIdentificationInfo = subscriptionIdentificationInfo;
    this.timeInMillisecondsToSleepWhenKeyDoesNotExist = timeInMillisecondsToSleepWhenKeyDoesNotExist;
    this.blockStreamTimeInMilliseconds = blockStreamTimeInMilliseconds;

    logger.info("channel processor is created (channel = {}, {})", channel, subscriptionIdentificationInfo);
  }

  public void process() {
    try {
      logger.info("channel processor started processing (channel = {}, {})", channel, subscriptionIdentificationInfo);
      running.set(true);
      makeSureConsumerGroupExists();
      processPendingRecords();
      processRegularRecords();
      stopCountDownLatch.countDown();
      logger.info("channel processor finished processing (channel = {}, {})", channel, subscriptionIdentificationInfo);
    } catch (Throwable e) {
      logger.error("ChannelProcessor got error: " + channel  + ", " + subscriberId, e);
      throw e;
    }
  }

  public void stop() {
    running.set(false);
    try {
      stopCountDownLatch.await();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }

    logger.info("channel processor stopped processing (channel = {}, {})", channel, subscriptionIdentificationInfo);
  }

  private void makeSureConsumerGroupExists() {
    logger.trace("Ensuring consumer group exists {} {}", channel, subscriberId);
    while (running.get()) {
      try {
        logger.trace("Creating group {} {}", channel, subscriberId);
        redisTemplate.opsForStream().createGroup(channel, ReadOffset.from("0"), subscriberId);
        logger.trace("Ensured consumer group exists {}", channel);
        return;
      } catch (RedisSystemException e) {
        if (isKeyDoesNotExist(e)) {
          logger.trace("Stream {} does not exist!", channel);
          sleep(timeInMillisecondsToSleepWhenKeyDoesNotExist);
          continue;
        } else if (isGroupExistsAlready(e)) {
          logger.trace("Ensured consumer group exists {}", channel);
          return;
        }
        logger.trace("Got exception ensuring consumer group exists: " + channel, e);
        throw e;
      }
    }
  }

  private boolean isKeyDoesNotExist(RedisSystemException e) {
    return isRedisCommandExceptionContainingMessage(e, "ERR The XGROUP subcommand requires the key to exist");
  }

  private boolean isGroupExistsAlready(RedisSystemException e) {
    return isRedisCommandExceptionContainingMessage(e, "Consumer Group name already exists");
  }

  private boolean isRedisCommandExceptionContainingMessage(RedisSystemException e, String expectedMessage) {
    String message = e.getCause().getMessage();

    return e.getCause() instanceof RedisCommandExecutionException &&
            message != null &&
            message.contains(expectedMessage);
  }

  private void processPendingRecords() {
    logger.trace("Processing pending records {}", channel);
    while (running.get()) {
      List<MapRecord<String, Object, Object>> pendingRecords = getPendingRecords();

      if (pendingRecords.isEmpty()) {
        return;
      }

      processRecords(pendingRecords);
    }
  }

  private void processRegularRecords() {
    logger.trace("Processing regular records {}", channel);
    while (running.get()) {
      processRecords(getUnprocessedRecords());
    }
  }

  private void processRecords(List<MapRecord<String, Object, Object>> records) {
    records.forEach(entries ->
            entries
                    .getValue()
                    .values()
                    .forEach(v -> processMessage(v.toString(), entries.getId())));
  }

  private void processMessage(String message, RecordId recordId) {
    logger.trace("channel processor {} with channel {} got message: {}", subscriptionIdentificationInfo, channel, message);

    try {
      logger.trace("invoked message handler");
      messageHandler.accept(new RedisMessage(message));
      logger.trace("message handler invoked");
    } catch (Throwable t) {
      logger.error(t.getMessage(), t);
      stopCountDownLatch.countDown();
      throw t;
    }

    redisTemplate.opsForStream().acknowledge(channel, subscriberId, recordId);
  }

  private List<MapRecord<String, Object, Object>> getPendingRecords() {
    return getRecords(ReadOffset.from("0"), StreamReadOptions.empty());
  }

  private List<MapRecord<String, Object, Object>> getUnprocessedRecords() {
    return getRecords(ReadOffset.from(">"), StreamReadOptions.empty().block(Duration.ofMillis(blockStreamTimeInMilliseconds)));
  }

  private List<MapRecord<String, Object, Object>> getRecords(ReadOffset readOffset, StreamReadOptions options) {
    List<MapRecord<String, Object, Object>> records = redisTemplate
            .opsForStream()
            .read(Consumer.from(subscriberId, subscriberId), options, StreamOffset.create(channel, readOffset));

    if (records.size() > 0) {
      logger.trace("getRecords {} {} found {} records", channel, readOffset, records.size());
    }

    return records;
  }

  private void sleep(long timeoutInMilliseconds) {
    try {
      Thread.sleep(timeoutInMilliseconds);
    } catch (Exception e) {
      logger.error(e.getMessage(), e);
      throw new RuntimeException(e);
    }
  }
}
