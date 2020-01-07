package com.wavefront.agent.handlers;

import com.wavefront.common.SamplingLogger;
import com.wavefront.api.agent.ValidationConfiguration;
import com.wavefront.data.ReportableEntityType;
import org.apache.commons.lang.math.NumberUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import javax.annotation.Nonnull;

/**
 * Caching factory for {@link ReportableEntityHandler} objects. Makes sure there's only one handler
 * for each {@link HandlerKey}, which makes it possible to spin up handlers on demand at runtime,
 * as well as redirecting traffic to a different pipeline.
 *
 * @author vasily@wavefront.com
 */
public class ReportableEntityHandlerFactoryImpl implements ReportableEntityHandlerFactory {

  public static final Logger VALID_POINTS_LOGGER = new SamplingLogger(
      ReportableEntityType.POINT, Logger.getLogger("RawValidPoints"),
      getSystemPropertyAsDouble("wavefront.proxy.logpoints.sample-rate"),
      "true".equalsIgnoreCase(System.getProperty("wavefront.proxy.logpoints")));
  public static final Logger VALID_HISTOGRAMS_LOGGER = new SamplingLogger(
      ReportableEntityType.HISTOGRAM, Logger.getLogger("RawValidHistograms"),
      getSystemPropertyAsDouble("wavefront.proxy.logpoints.sample-rate"),
      "true".equalsIgnoreCase(System.getProperty("wavefront.proxy.logpoints")));
  private static final Logger VALID_SPANS_LOGGER = new SamplingLogger(
      ReportableEntityType.TRACE, Logger.getLogger("RawValidSpans"),
      getSystemPropertyAsDouble("wavefront.proxy.logspans.sample-rate"), false);
  private static final Logger VALID_SPAN_LOGS_LOGGER = new SamplingLogger(
      ReportableEntityType.TRACE_SPAN_LOGS, Logger.getLogger("RawValidSpanLogs"),
      getSystemPropertyAsDouble("wavefront.proxy.logspans.sample-rate"), false);
  private static final Logger VALID_EVENTS_LOGGER = new SamplingLogger(
      ReportableEntityType.EVENT, Logger.getLogger("RawValidEvents"),
      getSystemPropertyAsDouble("wavefront.proxy.logevents.sample-rate"), false);

  protected final Map<String, Map<ReportableEntityType, ReportableEntityHandler<?, ?>>> handlers =
      new HashMap<>();

  private final SenderTaskFactory senderTaskFactory;
  private final int blockedItemsPerBatch;
  private final ValidationConfiguration validationConfig;
  private final Logger blockedPointsLogger;
  private final Logger blockedHistogramsLogger;
  private final Logger blockedSpansLogger;

  /**
   * Create new instance.
   *
   * @param senderTaskFactory    SenderTaskFactory instance used to create SenderTasks
   *                             for new handlers.
   * @param blockedItemsPerBatch controls sample rate of how many blocked points are written
   *                             into the main log file.
   * @param validationConfig     validation configuration.
   */
  public ReportableEntityHandlerFactoryImpl(
      final SenderTaskFactory senderTaskFactory, final int blockedItemsPerBatch,
      @Nonnull final ValidationConfiguration validationConfig, final Logger blockedPointsLogger,
      final Logger blockedHistogramsLogger, final Logger blockedSpansLogger) {
    this.senderTaskFactory = senderTaskFactory;
    this.blockedItemsPerBatch = blockedItemsPerBatch;
    this.validationConfig = validationConfig;
    this.blockedPointsLogger = blockedPointsLogger;
    this.blockedHistogramsLogger = blockedHistogramsLogger;
    this.blockedSpansLogger = blockedSpansLogger;
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T, U> ReportableEntityHandler<T, U> getHandler(HandlerKey handlerKey) {
    return (ReportableEntityHandler<T, U>) handlers.computeIfAbsent(handlerKey.getHandle(),
        h -> new HashMap<>()).computeIfAbsent(handlerKey.getEntityType(), k -> {
      switch (handlerKey.getEntityType()) {
        case POINT:
          return new ReportPointHandlerImpl(handlerKey, blockedItemsPerBatch,
              senderTaskFactory.createSenderTasks(handlerKey),
              validationConfig, true, blockedPointsLogger, VALID_POINTS_LOGGER);
        case HISTOGRAM:
          return new ReportPointHandlerImpl(handlerKey, blockedItemsPerBatch,
              senderTaskFactory.createSenderTasks(handlerKey),
              validationConfig, false, blockedHistogramsLogger, VALID_HISTOGRAMS_LOGGER);
        case SOURCE_TAG:
          return new ReportSourceTagHandlerImpl(handlerKey, blockedItemsPerBatch,
              senderTaskFactory.createSenderTasks(handlerKey),
              blockedPointsLogger);
        case TRACE:
          return new SpanHandlerImpl(handlerKey, blockedItemsPerBatch,
              senderTaskFactory.createSenderTasks(handlerKey),
              validationConfig, blockedSpansLogger, VALID_SPANS_LOGGER);
        case TRACE_SPAN_LOGS:
          return new SpanLogsHandlerImpl(handlerKey, blockedItemsPerBatch,
              senderTaskFactory.createSenderTasks(handlerKey),
              blockedSpansLogger, VALID_SPAN_LOGS_LOGGER);
        case EVENT:
          return new EventHandlerImpl(handlerKey, blockedItemsPerBatch,
              senderTaskFactory.createSenderTasks(handlerKey),
              blockedPointsLogger, VALID_EVENTS_LOGGER);
        default:
          throw new IllegalArgumentException("Unexpected entity type " +
              handlerKey.getEntityType().name() + " for " + handlerKey.getHandle());
      }
    });
  }

  @Override
  public void shutdown(@Nonnull String handle) {
    if (handlers.containsKey(handle)) {
      handlers.get(handle).values().forEach(ReportableEntityHandler::shutdown);
    }
  }

  private static double getSystemPropertyAsDouble(String propertyName) {
    String sampleRateProperty = propertyName == null ? null : System.getProperty(propertyName);
    return NumberUtils.isNumber(sampleRateProperty) ? Double.parseDouble(sampleRateProperty) : 1.0d;
  }
}
