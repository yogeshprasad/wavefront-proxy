package com.wavefront.agent.data;

import avro.shaded.com.google.common.base.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Throwables;
import com.wavefront.agent.data.EntityWrapper.EntityProperties;
import com.wavefront.agent.queueing.TaskQueue;
import com.wavefront.common.TaggedMetricName;
import com.wavefront.data.ReportableEntityType;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Histogram;
import com.yammer.metrics.core.MetricName;
import com.yammer.metrics.core.TimerContext;

import javax.annotation.Nullable;
import javax.net.ssl.SSLHandshakeException;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.wavefront.agent.Utils.isWavefrontResponse;

/**
 * A base class for data submission tasks.
 *
 * @param <T> task type
 *
 * @author vasily@wavefront.com.
 */
abstract class AbstractDataSubmissionTask<T extends DataSubmissionTask<T>>
    implements DataSubmissionTask<T> {
  private static final Logger log =
      Logger.getLogger(AbstractDataSubmissionTask.class.getCanonicalName());

  @JsonProperty
  private Long createdMillis;
  @JsonProperty
  protected Long enqueuedTimeMillis = null;
  @JsonProperty
  protected int attempts = 0;
  @JsonProperty
  protected String handle;
  @JsonProperty
  protected ReportableEntityType entityType;

  protected transient Histogram timeSpentInQueue;
  protected transient Supplier<Long> timeProvider;
  protected transient EntityProperties properties;
  protected transient TaskQueue<T> backlog;

  AbstractDataSubmissionTask() {
  }

  /**
   * @param properties   entity-specific wrapper for runtime properties.
   * @param backlog      backing queue.
   * @param handle       port/handle
   * @param entityType   entity type
   * @param timeProvider time provider (in millis)
   */
  AbstractDataSubmissionTask(EntityProperties properties,
                             TaskQueue<T> backlog,
                             String handle,
                             ReportableEntityType entityType,
                             @Nullable Supplier<Long> timeProvider) {
    this.properties = properties;
    this.backlog = backlog;
    this.handle = handle;
    this.entityType = entityType;
    this.timeProvider = Objects.firstNonNull(timeProvider, System::currentTimeMillis);
    this.createdMillis = this.timeProvider.get();
  }

  @Override
  public int getAttempts() {
    return attempts;
  }

  @Override
  public long getCreatedMillis() {
    return createdMillis;
  }

  @Override
  public ReportableEntityType getEntityType() {
    return entityType;
  }

  abstract Response doExecute();

  public TaskResult execute() {
    if (enqueuedTimeMillis != null) {
      if (timeSpentInQueue == null) {
        timeSpentInQueue = Metrics.newHistogram(new TaggedMetricName("buffer", "queue-time",
            "port", handle, "content", entityType.toString()));
      }
      timeSpentInQueue.update(timeProvider.get() - enqueuedTimeMillis);
    }
    attempts += 1;
    TimerContext timer = Metrics.newTimer(new MetricName("push." + handle, "", "duration"),
        TimeUnit.MILLISECONDS, TimeUnit.MINUTES).time();
    try (Response response = doExecute()) {
      Metrics.newCounter(new TaggedMetricName("push", handle + ".http." +
          response.getStatus() + ".count")).inc();
      if (response.getStatus() >= 200 && response.getStatus() < 300) {
        Metrics.newCounter(new MetricName(entityType + "." + handle, "", "delivered")).
            inc(this.weight());
        return TaskResult.DELIVERED;
      }
      switch (response.getStatus()) {
        case 406:
        case 429:
          if (enqueuedTimeMillis == null) {
            if (properties.getTaskQueueLevel().isLessThan(TaskQueueLevel.PUSHBACK)) {
              return TaskResult.RETRY_LATER;
            }
            enqueue(QueueingReason.PUSHBACK);
            return TaskResult.PERSISTED;
          }
          if (properties.isSplitPushWhenRateLimited()) {
            List<T> splitTasks =
                splitTask(properties.getMinBatchSplitSize(), properties.getItemsPerBatch());
            if (splitTasks.size() == 1) return TaskResult.RETRY_LATER;
            splitTasks.forEach(x -> x.enqueue(null));
            return TaskResult.PERSISTED;
          }
          return TaskResult.RETRY_LATER;
        case 401:
        case 403:
          log.warning("[" + handle + "] HTTP " + response.getStatus() + ": " +
              "Please verify that \"" + entityType + "\" is enabled for your account!");
          return checkStatusAndQueue(QueueingReason.AUTH, false);
        case 407:
        case 408:
          if (isWavefrontResponse(response)) {
            log.warning("[" + handle + "] HTTP " + response.getStatus() + " (Unregistered proxy) " +
                "received while sending data to Wavefront - please verify that your token is " +
                "valid and has Proxy Management permissions!");
          } else {
            log.warning("[" + handle + "] HTTP " + response.getStatus() + ": " +
                "received while sending data to Wavefront - please verify your network/HTTP proxy" +
                " settings!");
          }
          return checkStatusAndQueue(QueueingReason.RETRY, false);
        case 413:
          splitTask(1, properties.getItemsPerBatch()).
              forEach(x -> x.enqueue(enqueuedTimeMillis == null ? QueueingReason.SPLIT : null));
          return TaskResult.PERSISTED_RETRY;
        default:
          log.info("[" + handle + "] HTTP " + response.getStatus() + " received while sending " +
              "data to Wavefront, retrying");
          return checkStatusAndQueue(QueueingReason.RETRY, true);
      }
    } catch (ProcessingException ex) {
      Throwable rootCause = Throwables.getRootCause(ex);
      if (rootCause instanceof UnknownHostException) {
        log.warning("[" + handle + "] Error sending data to Wavefront: Unknown host " +
            rootCause.getMessage() + ", please check your network!");
      } else if (rootCause instanceof ConnectException ||
          rootCause instanceof SocketTimeoutException) {
        log.warning("[" + handle + "] Error sending data to Wavefront: " + rootCause.getMessage() +
            ", please verify your network/HTTP proxy settings!");
      } else if (ex.getCause() instanceof SSLHandshakeException) {
        log.warning("[" + handle + "] Error sending data to Wavefront: " + ex.getCause() +
            ", please verify that your environment has up-to-date root certificates!");
      } else {
        log.warning("[" + handle + "] Error sending data to Wavefront: " + rootCause);
      }
      if (log.isLoggable(Level.FINE)) {
        log.log(Level.FINE, "Full stacktrace: ", ex);
      }
      return checkStatusAndQueue(QueueingReason.RETRY, true);
    } catch (Exception ex) {
      log.warning("[" + handle + "] Error sending data to Wavefront: " +
          Throwables.getRootCause(ex));
      if (log.isLoggable(Level.FINE)) {
        log.log(Level.FINE, "Full stacktrace: ", ex);
      }
      return checkStatusAndQueue(QueueingReason.RETRY, true);
    } finally {
      timer.stop();
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  public void enqueue(@Nullable QueueingReason reason) {
    enqueuedTimeMillis = timeProvider.get();
    try {
      backlog.add((T) this);
      if (reason != null) {
        Metrics.newCounter(new TaggedMetricName(entityType + "." + handle, "queued",
            "reason", reason.toString())).inc(this.weight());
      }
    } catch (IOException e) {
      Metrics.newCounter(new TaggedMetricName("buffer", "failures", "port", handle)).inc();
      log.severe("[" + handle + "] CRITICAL (Losing data): WF-1: Error adding task to the queue: " +
          e.getMessage());
    }
  }

  private TaskResult checkStatusAndQueue(QueueingReason reason,
                                         boolean requeue) {
    if (enqueuedTimeMillis == null) {
      if (properties.getTaskQueueLevel().isLessThan(TaskQueueLevel.ANY_ERROR)) {
        return TaskResult.RETRY_LATER;
      }
      enqueue(reason);
      return TaskResult.PERSISTED;
    }
    if (requeue) {
      enqueue(null);
      return TaskResult.PERSISTED_RETRY;
    } else {
      return TaskResult.RETRY_LATER;
    }
  }
}
