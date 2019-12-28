package com.wavefront.agent;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.RecyclableRateLimiter;
import com.tdunning.math.stats.AgentDigest;
import com.tdunning.math.stats.AgentDigest.AgentDigestMarshaller;
import com.uber.tchannel.api.TChannel;
import com.uber.tchannel.channels.Connection;
import com.wavefront.agent.auth.TokenAuthenticatorBuilder;
import com.wavefront.agent.channel.CachingHostnameLookupResolver;
import com.wavefront.agent.channel.HealthCheckManager;
import com.wavefront.agent.channel.HealthCheckManagerImpl;
import com.wavefront.agent.channel.SharedGraphiteHostAnnotator;
import com.wavefront.agent.config.ConfigurationException;
import com.wavefront.agent.data.QueueingReason;
import com.wavefront.agent.formatter.GraphiteFormatter;
import com.wavefront.agent.handlers.DelegatingReportableEntityHandlerFactoryImpl;
import com.wavefront.agent.handlers.DeltaCounterAccumulationHandlerImpl;
import com.wavefront.agent.handlers.HandlerKey;
import com.wavefront.agent.handlers.HistogramAccumulationHandlerImpl;
import com.wavefront.agent.handlers.InternalProxyWavefrontClient;
import com.wavefront.agent.handlers.RecyclableRateLimiterFactory;
import com.wavefront.agent.handlers.RecyclableRateLimiterFactoryImpl;
import com.wavefront.agent.handlers.ReportableEntityHandler;
import com.wavefront.agent.handlers.ReportableEntityHandlerFactory;
import com.wavefront.agent.handlers.ReportableEntityHandlerFactoryImpl;
import com.wavefront.agent.handlers.SenderTaskFactory;
import com.wavefront.agent.handlers.SenderTaskFactoryImpl;
import com.wavefront.agent.histogram.MapLoader;
import com.wavefront.agent.histogram.PointHandlerDispatcher;
import com.wavefront.agent.histogram.Utils;
import com.wavefront.agent.histogram.Utils.HistogramKey;
import com.wavefront.agent.histogram.Utils.HistogramKeyMarshaller;
import com.wavefront.agent.histogram.accumulator.AccumulationCache;
import com.wavefront.agent.histogram.accumulator.Accumulator;
import com.wavefront.agent.histogram.accumulator.AgentDigestFactory;
import com.wavefront.agent.listeners.AdminPortUnificationHandler;
import com.wavefront.agent.listeners.ChannelByteArrayHandler;
import com.wavefront.agent.listeners.DataDogPortUnificationHandler;
import com.wavefront.agent.listeners.HttpHealthCheckEndpointHandler;
import com.wavefront.agent.listeners.JsonMetricsPortUnificationHandler;
import com.wavefront.agent.listeners.OpenTSDBPortUnificationHandler;
import com.wavefront.agent.listeners.RawLogsIngesterPortUnificationHandler;
import com.wavefront.agent.listeners.RelayPortUnificationHandler;
import com.wavefront.agent.listeners.WavefrontPortUnificationHandler;
import com.wavefront.agent.listeners.WriteHttpJsonPortUnificationHandler;
import com.wavefront.agent.listeners.tracing.JaegerPortUnificationHandler;
import com.wavefront.agent.listeners.tracing.JaegerTChannelCollectorHandler;
import com.wavefront.agent.listeners.tracing.TracePortUnificationHandler;
import com.wavefront.agent.listeners.tracing.ZipkinPortUnificationHandler;
import com.wavefront.agent.logsharvesting.FilebeatIngester;
import com.wavefront.agent.logsharvesting.LogsIngester;
import com.wavefront.agent.preprocessor.PreprocessorRuleMetrics;
import com.wavefront.agent.preprocessor.ReportPointAddPrefixTransformer;
import com.wavefront.agent.preprocessor.ReportPointTimestampInRangeFilter;
import com.wavefront.agent.preprocessor.SpanSanitizeTransformer;
import com.wavefront.agent.queueing.QueueingFactory;
import com.wavefront.agent.queueing.QueueingFactoryImpl;
import com.wavefront.agent.queueing.TaskQueueFactory;
import com.wavefront.agent.queueing.TaskQueueFactoryImpl;
import com.wavefront.agent.sampler.SpanSamplerUtils;
import com.wavefront.api.agent.AgentConfiguration;
import com.wavefront.common.NamedThreadFactory;
import com.wavefront.common.TaggedMetricName;
import com.wavefront.data.ReportableEntityType;
import com.wavefront.ingester.EventDecoder;
import com.wavefront.ingester.GraphiteDecoder;
import com.wavefront.ingester.HistogramDecoder;
import com.wavefront.ingester.OpenTSDBDecoder;
import com.wavefront.ingester.PickleProtocolDecoder;
import com.wavefront.ingester.ReportPointDecoderWrapper;
import com.wavefront.ingester.ReportSourceTagDecoder;
import com.wavefront.ingester.ReportableEntityDecoder;
import com.wavefront.ingester.SpanDecoder;
import com.wavefront.ingester.SpanLogsDecoder;
import com.wavefront.ingester.TcpIngester;
import com.wavefront.metrics.ExpectedAgentMetric;
import com.wavefront.sdk.common.WavefrontSender;
import com.wavefront.sdk.entities.tracing.sampling.CompositeSampler;
import com.wavefront.sdk.entities.tracing.sampling.Sampler;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Counter;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.bytes.ByteArrayDecoder;
import net.openhft.chronicle.map.ChronicleMap;
import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.HttpClientBuilder;
import org.logstash.beats.Server;
import wavefront.report.ReportPoint;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;
import static com.wavefront.agent.ProxyUtil.createInitializer;
import static com.wavefront.agent.Utils.lazySupplier;
import static com.wavefront.agent.handlers.RecyclableRateLimiterFactoryImpl.NO_RATE_LIMIT;

/**
 * Push-only Agent.
 *
 * @author Clement Pang (clement@wavefront.com)
 */
public class PushAgent extends AbstractAgent {

  protected final List<Thread> managedThreads = new ArrayList<>();
  protected final IdentityHashMap<ChannelOption<?>, Object> childChannelOptions =
      new IdentityHashMap<>();
  protected ScheduledExecutorService histogramExecutor;
  protected ScheduledExecutorService histogramFlushExecutor;
  protected final Counter bindErrors = Metrics.newCounter(
      ExpectedAgentMetric.LISTENERS_BIND_ERRORS.metricName);
  protected TaskQueueFactory taskQueueFactory;
  protected SharedGraphiteHostAnnotator remoteHostAnnotator;
  protected Function<InetAddress, String> hostnameResolver;
  protected SenderTaskFactory senderTaskFactory;
  protected QueueingFactory queueingFactory;
  protected ReportableEntityHandlerFactory handlerFactory;
  protected RecyclableRateLimiterFactory rateLimiterFactory;
  protected HealthCheckManager healthCheckManager;
  protected final Supplier<Map<ReportableEntityType, ReportableEntityDecoder<?, ?>>>
      decoderSupplier = lazySupplier(() ->
      ImmutableMap.<ReportableEntityType, ReportableEntityDecoder<?, ?>>builder().
          put(ReportableEntityType.POINT, new ReportPointDecoderWrapper(
              new GraphiteDecoder("unknown", proxyConfig.getCustomSourceTags()))).
          put(ReportableEntityType.SOURCE_TAG, new ReportSourceTagDecoder()).
          put(ReportableEntityType.HISTOGRAM, new ReportPointDecoderWrapper(
              new HistogramDecoder("unknown"))).
          put(ReportableEntityType.TRACE, new SpanDecoder("unknown")).
          put(ReportableEntityType.TRACE_SPAN_LOGS, new SpanLogsDecoder()).
          put(ReportableEntityType.EVENT, new EventDecoder()).build());
  private Logger blockedPointsLogger;
  private Logger blockedHistogramsLogger;
  private Logger blockedSpansLogger;

  public static void main(String[] args) throws IOException {
    // Start the ssh daemon
    new PushAgent().start(args);
  }

  public PushAgent() {
    super(false, true);
  }

  @Deprecated
  protected PushAgent(boolean reportAsPushAgent) {
    super(false, reportAsPushAgent);
  }

  @Override
  protected void setupMemoryGuard(double threshold) {
    new ProxyMemoryGuard(() ->
        senderTaskFactory.drainBuffersToQueue(QueueingReason.MEMORY_PRESSURE), threshold);
  }

  @Override
  protected void startListeners() {
    blockedPointsLogger = Logger.getLogger(proxyConfig.getBlockedPointsLoggerName());
    blockedHistogramsLogger = Logger.getLogger(proxyConfig.getBlockedHistogramsLoggerName());
    blockedSpansLogger = Logger.getLogger(proxyConfig.getBlockedHistogramsLoggerName());

    if (proxyConfig.getSoLingerTime() >= 0) {
      childChannelOptions.put(ChannelOption.SO_LINGER, proxyConfig.getSoLingerTime());
    }
    hostnameResolver = new CachingHostnameLookupResolver(proxyConfig.isDisableRdnsLookup(),
        ExpectedAgentMetric.RDNS_CACHE_SIZE.metricName);
    taskQueueFactory = new TaskQueueFactoryImpl(proxyConfig.getBufferFile(),
        proxyConfig.isPurgeBuffer());
    remoteHostAnnotator = new SharedGraphiteHostAnnotator(proxyConfig.getCustomSourceTags(),
        hostnameResolver);
    rateLimiterFactory = new RecyclableRateLimiterFactoryImpl(proxyConfig);
    queueingFactory = new QueueingFactoryImpl(apiContainer, agentId, taskQueueFactory,
        rateLimiterFactory, entityWrapper);
    senderTaskFactory = new SenderTaskFactoryImpl(apiContainer, agentId, taskQueueFactory,
        queueingFactory, rateLimiterFactory, entityWrapper);
    handlerFactory = new ReportableEntityHandlerFactoryImpl(senderTaskFactory,
        proxyConfig.getPushBlockedSamples(), proxyConfig.getFlushThreads(),
        () -> validationConfiguration, blockedPointsLogger, blockedHistogramsLogger,
        blockedSpansLogger);
    healthCheckManager = new HealthCheckManagerImpl(proxyConfig.getHttpHealthCheckPath(),
        proxyConfig.getHttpHealthCheckResponseContentType(),
        proxyConfig.getHttpHealthCheckPassStatusCode(),
        proxyConfig.getHttpHealthCheckPassResponseBody(),
        proxyConfig.getHttpHealthCheckFailStatusCode(),
        proxyConfig.getHttpHealthCheckFailResponseBody());

    shutdownTasks.add(() -> senderTaskFactory.shutdown());
    shutdownTasks.add(() -> senderTaskFactory.drainBuffersToQueue(null));

    if (proxyConfig.getAdminApiListenerPort() > 0) {
      startAdminListener(proxyConfig.getAdminApiListenerPort());
    }
    portIterator(proxyConfig.getHttpHealthCheckPorts()).forEachRemaining(strPort ->
        startHealthCheckListener(Integer.parseInt(strPort)));

    portIterator(proxyConfig.getPushListenerPorts()).forEachRemaining(strPort -> {
      startGraphiteListener(strPort, handlerFactory, remoteHostAnnotator);
      logger.info("listening on port: " + strPort + " for Wavefront metrics");
    });

    portIterator(proxyConfig.getDeltaCountersAggregationListenerPorts()).forEachRemaining(strPort ->
    {
      startDeltaCounterListener(strPort, remoteHostAnnotator, senderTaskFactory);
      logger.info("listening on port: " + strPort + " for Wavefront delta counter metrics");
    });

    {
      // Histogram bootstrap.
      Iterator<String> histMinPorts = portIterator(proxyConfig.getHistogramMinuteListenerPorts());
      Iterator<String> histHourPorts = portIterator(proxyConfig.getHistogramHourListenerPorts());
      Iterator<String> histDayPorts = portIterator(proxyConfig.getHistogramDayListenerPorts());
      Iterator<String> histDistPorts = portIterator(proxyConfig.getHistogramDistListenerPorts());

      int activeHistogramAggregationTypes = (histDayPorts.hasNext() ? 1 : 0) +
          (histHourPorts.hasNext() ? 1 : 0) + (histMinPorts.hasNext() ? 1 : 0) +
          (histDistPorts.hasNext() ? 1 : 0);
      if (activeHistogramAggregationTypes > 0) { /*Histograms enabled*/
        histogramExecutor = Executors.newScheduledThreadPool(
            1 + activeHistogramAggregationTypes, new NamedThreadFactory("histogram-service"));
        histogramFlushExecutor = Executors.newScheduledThreadPool(
            Runtime.getRuntime().availableProcessors() / 2,
            new NamedThreadFactory("histogram-flush"));
        managedExecutors.add(histogramExecutor);
        managedExecutors.add(histogramFlushExecutor);

        File baseDirectory = new File(proxyConfig.getHistogramStateDirectory());

        // Central dispatch
        ReportableEntityHandler<ReportPoint, String> pointHandler = handlerFactory.getHandler(
            HandlerKey.of(ReportableEntityType.HISTOGRAM, "histogram_ports"));

        startHistogramListeners(histMinPorts, pointHandler, remoteHostAnnotator,
            Utils.Granularity.MINUTE, proxyConfig.getHistogramMinuteFlushSecs(),
            proxyConfig.isHistogramMinuteMemoryCache(), baseDirectory,
            proxyConfig.getHistogramMinuteAccumulatorSize(),
            proxyConfig.getHistogramMinuteAvgKeyBytes(),
            proxyConfig.getHistogramMinuteAvgDigestBytes(),
            proxyConfig.getHistogramMinuteCompression(),
            proxyConfig.isHistogramMinuteAccumulatorPersisted());
        startHistogramListeners(histHourPorts, pointHandler, remoteHostAnnotator,
            Utils.Granularity.HOUR, proxyConfig.getHistogramHourFlushSecs(),
            proxyConfig.isHistogramHourMemoryCache(), baseDirectory,
            proxyConfig.getHistogramHourAccumulatorSize(),
            proxyConfig.getHistogramHourAvgKeyBytes(),
            proxyConfig.getHistogramHourAvgDigestBytes(),
            proxyConfig.getHistogramHourCompression(),
            proxyConfig.isHistogramHourAccumulatorPersisted());
        startHistogramListeners(histDayPorts, pointHandler, remoteHostAnnotator,
            Utils.Granularity.DAY, proxyConfig.getHistogramDayFlushSecs(),
            proxyConfig.isHistogramDayMemoryCache(), baseDirectory,
            proxyConfig.getHistogramDayAccumulatorSize(),
            proxyConfig.getHistogramDayAvgKeyBytes(),
            proxyConfig.getHistogramDayAvgDigestBytes(),
            proxyConfig.getHistogramDayCompression(),
            proxyConfig.isHistogramDayAccumulatorPersisted());
        startHistogramListeners(histDistPorts, pointHandler, remoteHostAnnotator,
            null, proxyConfig.getHistogramDistFlushSecs(), proxyConfig.isHistogramDistMemoryCache(),
            baseDirectory, proxyConfig.getHistogramDistAccumulatorSize(),
            proxyConfig.getHistogramDistAvgKeyBytes(), proxyConfig.getHistogramDistAvgDigestBytes(),
            proxyConfig.getHistogramDistCompression(),
            proxyConfig.isHistogramDistAccumulatorPersisted());
      }
    }

    if (StringUtils.isNotBlank(proxyConfig.getGraphitePorts()) ||
        StringUtils.isNotBlank(proxyConfig.getPicklePorts())) {
      if (tokenAuthenticator.authRequired()) {
        logger.warning("Graphite mode is not compatible with HTTP authentication, ignoring");
      } else {
        Preconditions.checkNotNull(proxyConfig.getGraphiteFormat(),
            "graphiteFormat must be supplied to enable graphite support");
        Preconditions.checkNotNull(proxyConfig.getGraphiteDelimiters(),
            "graphiteDelimiters must be supplied to enable graphite support");
        GraphiteFormatter graphiteFormatter = new GraphiteFormatter(proxyConfig.getGraphiteFormat(),
            proxyConfig.getGraphiteDelimiters(), proxyConfig.getGraphiteFieldsToRemove());
        portIterator(proxyConfig.getGraphitePorts()).forEachRemaining(strPort -> {
          preprocessors.getSystemPreprocessor(strPort).forPointLine().
              addTransformer(0, graphiteFormatter);
          startGraphiteListener(strPort, handlerFactory, null);
          logger.info("listening on port: " + strPort + " for graphite metrics");
        });
        portIterator(proxyConfig.getPicklePorts()).forEachRemaining(strPort ->
            startPickleListener(strPort, handlerFactory, graphiteFormatter));
      }
    }
    portIterator(proxyConfig.getOpentsdbPorts()).forEachRemaining(strPort ->
        startOpenTsdbListener(strPort, handlerFactory));
    if (proxyConfig.getDataDogJsonPorts() != null) {
      HttpClient httpClient = HttpClientBuilder.create().
          useSystemProperties().
          setUserAgent(proxyConfig.getHttpUserAgent()).
          setConnectionTimeToLive(1, TimeUnit.MINUTES).
          setRetryHandler(new DefaultHttpRequestRetryHandler(proxyConfig.getHttpAutoRetries(),
              true)).
          setDefaultRequestConfig(
              RequestConfig.custom().
                  setContentCompressionEnabled(true).
                  setRedirectsEnabled(true).
                  setConnectTimeout(proxyConfig.getHttpConnectTimeout()).
                  setConnectionRequestTimeout(proxyConfig.getHttpConnectTimeout()).
                  setSocketTimeout(proxyConfig.getHttpRequestTimeout()).build()).
          build();

      portIterator(proxyConfig.getDataDogJsonPorts()).forEachRemaining(strPort ->
          startDataDogListener(strPort, handlerFactory, httpClient));
    }
    // sampler for spans
    Sampler rateSampler = SpanSamplerUtils.getRateSampler(proxyConfig.getTraceSamplingRate());
    Sampler durationSampler = SpanSamplerUtils.getDurationSampler(
        proxyConfig.getTraceSamplingDuration());
    List<Sampler> samplers = SpanSamplerUtils.fromSamplers(rateSampler, durationSampler);
    Sampler compositeSampler = new CompositeSampler(samplers);

    portIterator(proxyConfig.getTraceListenerPorts()).forEachRemaining(strPort ->
        startTraceListener(strPort, handlerFactory, compositeSampler));
    portIterator(proxyConfig.getTraceJaegerListenerPorts()).forEachRemaining(strPort -> {
      PreprocessorRuleMetrics ruleMetrics = new PreprocessorRuleMetrics(
          Metrics.newCounter(new TaggedMetricName("point.spanSanitize", "count", "port", strPort)),
          null, null
      );
      preprocessors.getSystemPreprocessor(strPort).forSpan().addTransformer(
          new SpanSanitizeTransformer(ruleMetrics));
      startTraceJaegerListener(strPort, handlerFactory,
          new InternalProxyWavefrontClient(handlerFactory, strPort), compositeSampler);
    });
    portIterator(proxyConfig.getTraceJaegerHttpListenerPorts()).forEachRemaining(strPort -> {
      PreprocessorRuleMetrics ruleMetrics = new PreprocessorRuleMetrics(
          Metrics.newCounter(new TaggedMetricName("point.spanSanitize", "count", "port", strPort)),
          null, null
      );
      preprocessors.getSystemPreprocessor(strPort).forSpan().addTransformer(
          new SpanSanitizeTransformer(ruleMetrics));
      startTraceJaegerHttpListener(strPort, handlerFactory,
          new InternalProxyWavefrontClient(handlerFactory, strPort), compositeSampler);
    });
    portIterator(proxyConfig.getPushRelayListenerPorts()).forEachRemaining(strPort ->
        startRelayListener(strPort, handlerFactory, remoteHostAnnotator));
    portIterator(proxyConfig.getTraceZipkinListenerPorts()).forEachRemaining(strPort -> {
      PreprocessorRuleMetrics ruleMetrics = new PreprocessorRuleMetrics(
          Metrics.newCounter(new TaggedMetricName("point.spanSanitize", "count", "port", strPort)),
          null, null
      );
      preprocessors.getSystemPreprocessor(strPort).forSpan().addTransformer(
          new SpanSanitizeTransformer(ruleMetrics));
      startTraceZipkinListener(strPort, handlerFactory,
          new InternalProxyWavefrontClient(handlerFactory, strPort), compositeSampler);
    });
    portIterator(proxyConfig.getJsonListenerPorts()).forEachRemaining(strPort ->
        startJsonListener(strPort, handlerFactory));
    portIterator(proxyConfig.getWriteHttpJsonListenerPorts()).forEachRemaining(strPort ->
        startWriteHttpJsonListener(strPort, handlerFactory));

    // Logs ingestion.
    if (proxyConfig.getFilebeatPort() > 0 || proxyConfig.getRawLogsPort() > 0) {
      if (loadLogsIngestionConfig() != null) {
        logger.info("Initializing logs ingestion");
        try {
          final LogsIngester logsIngester = new LogsIngester(handlerFactory,
              this::loadLogsIngestionConfig, proxyConfig.getPrefix());
          logsIngester.start();

          if (proxyConfig.getFilebeatPort() > 0) {
            startLogsIngestionListener(proxyConfig.getFilebeatPort(), logsIngester);
          }
          if (proxyConfig.getRawLogsPort() > 0) {
            startRawLogsIngestionListener(proxyConfig.getRawLogsPort(), logsIngester);
          }
        } catch (ConfigurationException e) {
          logger.log(Level.SEVERE, "Cannot start logsIngestion", e);
        }
      } else {
        logger.warning("Cannot start logsIngestion: invalid configuration or no config specified");
      }
    }
  }

  protected void startJsonListener(String strPort, ReportableEntityHandlerFactory handlerFactory) {
    final int port = Integer.parseInt(strPort);
    registerTimestampFilter(strPort);
    if (proxyConfig.isHttpHealthCheckAllPorts()) healthCheckManager.enableHealthcheck(port);

    ChannelHandler channelHandler = new JsonMetricsPortUnificationHandler(strPort,
        tokenAuthenticator, healthCheckManager, handlerFactory, proxyConfig.getPrefix(),
        proxyConfig.getHostname(), preprocessors.get(strPort));

    startAsManagedThread(new TcpIngester(createInitializer(channelHandler, strPort,
        proxyConfig.getPushListenerMaxReceivedLength(), proxyConfig.getPushListenerHttpBufferSize(),
        proxyConfig.getListenerIdleConnectionTimeout()), port).
        withChildChannelOptions(childChannelOptions), "listener-plaintext-json-" + port);
    logger.info("listening on port: " + strPort + " for JSON metrics data");
  }

  protected void startWriteHttpJsonListener(String strPort,
                                            ReportableEntityHandlerFactory handlerFactory) {
    final int port = Integer.parseInt(strPort);
    registerPrefixFilter(strPort);
    registerTimestampFilter(strPort);
    if (proxyConfig.isHttpHealthCheckAllPorts()) healthCheckManager.enableHealthcheck(port);

    ChannelHandler channelHandler = new WriteHttpJsonPortUnificationHandler(strPort,
        tokenAuthenticator, healthCheckManager, handlerFactory, proxyConfig.getHostname(),
        preprocessors.get(strPort));

    startAsManagedThread(new TcpIngester(createInitializer(channelHandler, strPort,
        proxyConfig.getPushListenerMaxReceivedLength(), proxyConfig.getPushListenerHttpBufferSize(),
        proxyConfig.getListenerIdleConnectionTimeout()), port).
        withChildChannelOptions(childChannelOptions), "listener-plaintext-writehttpjson-" + port);
    logger.info("listening on port: " + strPort + " for write_http data");
  }

  protected void startOpenTsdbListener(final String strPort,
                                       ReportableEntityHandlerFactory handlerFactory) {
    int port = Integer.parseInt(strPort);
    registerPrefixFilter(strPort);
    registerTimestampFilter(strPort);
    if (proxyConfig.isHttpHealthCheckAllPorts()) healthCheckManager.enableHealthcheck(port);

    ReportableEntityDecoder<String, ReportPoint> openTSDBDecoder = new ReportPointDecoderWrapper(
        new OpenTSDBDecoder("unknown", proxyConfig.getCustomSourceTags()));

    ChannelHandler channelHandler = new OpenTSDBPortUnificationHandler(strPort, tokenAuthenticator,
        healthCheckManager, openTSDBDecoder, handlerFactory, preprocessors.get(strPort),
        hostnameResolver);

    startAsManagedThread(new TcpIngester(createInitializer(channelHandler, strPort,
        proxyConfig.getPushListenerMaxReceivedLength(), proxyConfig.getPushListenerHttpBufferSize(),
        proxyConfig.getListenerIdleConnectionTimeout()), port).
        withChildChannelOptions(childChannelOptions), "listener-plaintext-opentsdb-" + port);
    logger.info("listening on port: " + strPort + " for OpenTSDB metrics");
  }

  protected void startDataDogListener(final String strPort,
                                      ReportableEntityHandlerFactory handlerFactory,
                                      HttpClient httpClient) {
    if (tokenAuthenticator.authRequired()) {
      logger.warning("Port: " + strPort +
          " (DataDog) is not compatible with HTTP authentication, ignoring");
      return;
    }
    int port = Integer.parseInt(strPort);
    registerPrefixFilter(strPort);
    registerTimestampFilter(strPort);
    if (proxyConfig.isHttpHealthCheckAllPorts()) healthCheckManager.enableHealthcheck(port);

    ChannelHandler channelHandler = new DataDogPortUnificationHandler(strPort, healthCheckManager,
        handlerFactory, proxyConfig.isDataDogProcessSystemMetrics(),
        proxyConfig.isDataDogProcessServiceChecks(), httpClient,
        proxyConfig.getDataDogRequestRelayTarget(), preprocessors.get(strPort));

    startAsManagedThread(new TcpIngester(createInitializer(channelHandler, strPort,
        proxyConfig.getPushListenerMaxReceivedLength(), proxyConfig.getPushListenerHttpBufferSize(),
        proxyConfig.getListenerIdleConnectionTimeout()), port).
        withChildChannelOptions(childChannelOptions), "listener-plaintext-datadog-" + port);
    logger.info("listening on port: " + strPort + " for DataDog metrics");
  }

  protected void startPickleListener(String strPort,
                                     ReportableEntityHandlerFactory handlerFactory,
                                     GraphiteFormatter formatter) {
    if (tokenAuthenticator.authRequired()) {
      logger.warning("Port: " + strPort +
          " (pickle format) is not compatible with HTTP authentication, ignoring");
      return;
    }
    int port = Integer.parseInt(strPort);
    registerPrefixFilter(strPort);
    registerTimestampFilter(strPort);

    // Set up a custom handler
    ChannelHandler channelHandler = new ChannelByteArrayHandler(
        new PickleProtocolDecoder("unknown", proxyConfig.getCustomSourceTags(),
            formatter.getMetricMangler(), port),
        handlerFactory.getHandler(HandlerKey.of(ReportableEntityType.POINT, strPort)),
        preprocessors.get(strPort));

    startAsManagedThread(new TcpIngester(createInitializer(ImmutableList.of(
        () -> new LengthFieldBasedFrameDecoder(ByteOrder.BIG_ENDIAN, 1000000, 0, 4, 0, 4, false),
        ByteArrayDecoder::new, () -> channelHandler), strPort,
        proxyConfig.getListenerIdleConnectionTimeout()), port).
            withChildChannelOptions(childChannelOptions), "listener-binary-pickle-" + strPort);
    logger.info("listening on port: " + strPort + " for Graphite/pickle protocol metrics");
  }

  protected void startTraceListener(final String strPort,
                                    ReportableEntityHandlerFactory handlerFactory,
                                    Sampler sampler) {
    final int port = Integer.parseInt(strPort);
    registerPrefixFilter(strPort);
    registerTimestampFilter(strPort);
    if (proxyConfig.isHttpHealthCheckAllPorts()) healthCheckManager.enableHealthcheck(port);

    ChannelHandler channelHandler = new TracePortUnificationHandler(strPort, tokenAuthenticator,
        healthCheckManager, new SpanDecoder("unknown"), new SpanLogsDecoder(),
        preprocessors.get(strPort), handlerFactory, sampler,
        proxyConfig.isTraceAlwaysSampleErrors(), traceDisabled::get, spanLogsDisabled::get);

    startAsManagedThread(new TcpIngester(createInitializer(channelHandler, strPort,
        proxyConfig.getTraceListenerMaxReceivedLength(),
        proxyConfig.getTraceListenerHttpBufferSize(),
        proxyConfig.getListenerIdleConnectionTimeout()), port).
        withChildChannelOptions(childChannelOptions), "listener-plaintext-trace-" + port);
    logger.info("listening on port: " + strPort + " for trace data");
  }

  protected void startTraceJaegerListener(String strPort,
                                          ReportableEntityHandlerFactory handlerFactory,
                                          @Nullable WavefrontSender wfSender,
                                          Sampler sampler) {
    if (tokenAuthenticator.authRequired()) {
      logger.warning("Port: " + strPort + " is not compatible with HTTP authentication, ignoring");
      return;
    }
    startAsManagedThread(() -> {
      activeListeners.inc();
      try {
        TChannel server = new TChannel.Builder("jaeger-collector").
            setServerPort(Integer.parseInt(strPort)).
            build();
        server.
            makeSubChannel("jaeger-collector", Connection.Direction.IN).
            register("Collector::submitBatches", new JaegerTChannelCollectorHandler(strPort,
                handlerFactory, wfSender, traceDisabled::get, spanLogsDisabled::get,
                preprocessors.get(strPort), sampler, proxyConfig.isTraceAlwaysSampleErrors(),
                proxyConfig.getTraceJaegerApplicationName(),
                proxyConfig.getTraceDerivedCustomTagKeys()));
        server.listen().channel().closeFuture().sync();
        server.shutdown(false);
      } catch (InterruptedException e) {
        logger.info("Listener on port " + strPort + " shut down.");
      } catch (Exception e) {
        logger.log(Level.SEVERE, "Jaeger trace collector exception", e);
      } finally {
        activeListeners.dec();
      }
    }, "listener-jaeger-tchannel-" + strPort);
    logger.info("listening on port: " + strPort + " for trace data (Jaeger format over TChannel)");
  }

  protected void startTraceJaegerHttpListener(final String strPort,
                                              ReportableEntityHandlerFactory handlerFactory,
                                              @Nullable WavefrontSender wfSender,
                                              Sampler sampler) {
    final int port = Integer.parseInt(strPort);
    if (proxyConfig.isHttpHealthCheckAllPorts()) healthCheckManager.enableHealthcheck(port);

    ChannelHandler channelHandler = new JaegerPortUnificationHandler(strPort, tokenAuthenticator,
        healthCheckManager, handlerFactory, wfSender, traceDisabled::get, spanLogsDisabled::get,
        preprocessors.get(strPort), sampler, proxyConfig.isTraceAlwaysSampleErrors(),
        proxyConfig.getTraceJaegerApplicationName(), proxyConfig.getTraceDerivedCustomTagKeys());

    startAsManagedThread(new TcpIngester(createInitializer(channelHandler, strPort,
        proxyConfig.getTraceListenerMaxReceivedLength(),
        proxyConfig.getTraceListenerHttpBufferSize(),
        proxyConfig.getListenerIdleConnectionTimeout()),
        port).withChildChannelOptions(childChannelOptions), "listener-jaeger-http-" + port);
    logger.info("listening on port: " + strPort + " for trace data (Jaeger format over HTTP)");
  }

  protected void startTraceZipkinListener(String strPort,
                                          ReportableEntityHandlerFactory handlerFactory,
                                          @Nullable WavefrontSender wfSender,
                                          Sampler sampler) {
    final int port = Integer.parseInt(strPort);
    if (proxyConfig.isHttpHealthCheckAllPorts()) healthCheckManager.enableHealthcheck(port);
    ChannelHandler channelHandler = new ZipkinPortUnificationHandler(strPort, healthCheckManager,
        handlerFactory, wfSender, traceDisabled::get, spanLogsDisabled::get,
        preprocessors.get(strPort), sampler, proxyConfig.isTraceAlwaysSampleErrors(),
        proxyConfig.getTraceZipkinApplicationName(), proxyConfig.getTraceDerivedCustomTagKeys());
    startAsManagedThread(new TcpIngester(createInitializer(channelHandler, strPort,
        proxyConfig.getTraceListenerMaxReceivedLength(),
        proxyConfig.getTraceListenerHttpBufferSize(),
        proxyConfig.getListenerIdleConnectionTimeout()),
        port).withChildChannelOptions(childChannelOptions), "listener-zipkin-trace-" + port);
    logger.info("listening on port: " + strPort + " for trace data (Zipkin format)");
  }

  @VisibleForTesting
  protected void startGraphiteListener(String strPort,
                                       ReportableEntityHandlerFactory handlerFactory,
                                       SharedGraphiteHostAnnotator hostAnnotator) {
    final int port = Integer.parseInt(strPort);
    registerPrefixFilter(strPort);
    registerTimestampFilter(strPort);
    if (proxyConfig.isHttpHealthCheckAllPorts()) healthCheckManager.enableHealthcheck(port);

    WavefrontPortUnificationHandler wavefrontPortUnificationHandler =
        new WavefrontPortUnificationHandler(strPort, tokenAuthenticator, healthCheckManager,
            decoderSupplier.get(), handlerFactory, hostAnnotator, preprocessors.get(strPort));

    startAsManagedThread(new TcpIngester(createInitializer(wavefrontPortUnificationHandler, strPort,
        proxyConfig.getPushListenerMaxReceivedLength(), proxyConfig.getPushListenerHttpBufferSize(),
        proxyConfig.getListenerIdleConnectionTimeout()), port).
        withChildChannelOptions(childChannelOptions), "listener-graphite-" + port);
  }

  @VisibleForTesting
  protected void startDeltaCounterListener(String strPort, SharedGraphiteHostAnnotator hostAnnotator,
                                           SenderTaskFactory senderTaskFactory) {
    final int port = Integer.parseInt(strPort);
    registerPrefixFilter(strPort);
    registerTimestampFilter(strPort);
    if (proxyConfig.isHttpHealthCheckAllPorts()) healthCheckManager.enableHealthcheck(port);

    ReportableEntityHandlerFactory deltaCounterHandlerFactory = new ReportableEntityHandlerFactory() {
      private final Map<HandlerKey, ReportableEntityHandler<?, ?>> handlers = new HashMap<>();
      @Override
      public <T, U> ReportableEntityHandler<T, U> getHandler(HandlerKey handlerKey) {
        //noinspection unchecked
        return (ReportableEntityHandler<T, U>) handlers.computeIfAbsent(handlerKey,
            k -> new DeltaCounterAccumulationHandlerImpl(handlerKey.getHandle(),
                proxyConfig.getPushBlockedSamples(),
                senderTaskFactory.createSenderTasks(handlerKey, proxyConfig.getFlushThreads()),
                () -> validationConfiguration,
                proxyConfig.getDeltaCountersAggregationIntervalSeconds(), blockedPointsLogger));
      }
    };

    WavefrontPortUnificationHandler wavefrontPortUnificationHandler =
        new WavefrontPortUnificationHandler(strPort, tokenAuthenticator, healthCheckManager,
            decoderSupplier.get(), deltaCounterHandlerFactory, hostAnnotator,
            preprocessors.get(strPort));

    startAsManagedThread(new TcpIngester(createInitializer(wavefrontPortUnificationHandler, strPort,
        proxyConfig.getPushListenerMaxReceivedLength(), proxyConfig.getPushListenerHttpBufferSize(),
        proxyConfig.getListenerIdleConnectionTimeout()), port).
        withChildChannelOptions(childChannelOptions), "listener-deltaCounter-" + port);
  }

  @VisibleForTesting
  protected void startRelayListener(String strPort,
                                    ReportableEntityHandlerFactory handlerFactory,
                                    SharedGraphiteHostAnnotator hostAnnotator) {
    final int port = Integer.parseInt(strPort);
    registerPrefixFilter(strPort);
    registerTimestampFilter(strPort);
    if (proxyConfig.isHttpHealthCheckAllPorts()) healthCheckManager.enableHealthcheck(port);

    ReportableEntityHandlerFactory handlerFactoryDelegate = proxyConfig.
        isPushRelayHistogramAggregator() ?
        new DelegatingReportableEntityHandlerFactoryImpl(handlerFactory) {
          @Override
          public <T, U> ReportableEntityHandler<T, U> getHandler(HandlerKey handlerKey) {
            if (handlerKey.getEntityType() == ReportableEntityType.HISTOGRAM) {
              ChronicleMap<HistogramKey, AgentDigest> accumulator = ChronicleMap.
                  of(HistogramKey.class, AgentDigest.class).
                  keyMarshaller(HistogramKeyMarshaller.get()).
                  valueMarshaller(AgentDigestMarshaller.get()).
                  entries(proxyConfig.getPushRelayHistogramAggregatorAccumulatorSize()).
                  averageKeySize(proxyConfig.getHistogramDistAvgKeyBytes()).
                  averageValueSize(proxyConfig.getHistogramDistAvgDigestBytes()).
                  maxBloatFactor(1000).
                  create();
              AgentDigestFactory agentDigestFactory = new AgentDigestFactory(
                  proxyConfig.getPushRelayHistogramAggregatorCompression(),
                  TimeUnit.SECONDS.toMillis(proxyConfig.getPushRelayHistogramAggregatorFlushSecs()));
              AccumulationCache cachedAccumulator = new AccumulationCache(accumulator,
                  agentDigestFactory, 0, "histogram.accumulator.distributionRelay", null);
              //noinspection unchecked
              return (ReportableEntityHandler<T, U>) new HistogramAccumulationHandlerImpl(
                  handlerKey.getHandle(), cachedAccumulator, proxyConfig.getPushBlockedSamples(),
                  null, () -> validationConfiguration, true, blockedHistogramsLogger);
            }
            return delegate.getHandler(handlerKey);
          }
        } : handlerFactory;

    Map<ReportableEntityType, ReportableEntityDecoder<?, ?>> filteredDecoders =
        decoderSupplier.get().entrySet().stream().
            filter(x -> !x.getKey().equals(ReportableEntityType.SOURCE_TAG)).
            collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    ChannelHandler channelHandler = new RelayPortUnificationHandler(strPort, tokenAuthenticator,
        healthCheckManager, filteredDecoders, handlerFactoryDelegate, preprocessors.get(strPort),
        hostAnnotator, histogramDisabled::get, traceDisabled::get, spanLogsDisabled::get);
    startAsManagedThread(new TcpIngester(createInitializer(channelHandler, strPort,
        proxyConfig.getPushListenerMaxReceivedLength(), proxyConfig.getPushListenerHttpBufferSize(),
        proxyConfig.getListenerIdleConnectionTimeout()), port).
        withChildChannelOptions(childChannelOptions), "listener-relay-" + port);
  }

  protected void startLogsIngestionListener(int port, LogsIngester logsIngester) {
    if (tokenAuthenticator.authRequired()) {
      logger.warning("Filebeat log ingestion is not compatible with HTTP authentication, ignoring");
      return;
    }
    final Server filebeatServer = new Server("0.0.0.0", port,
        proxyConfig.getListenerIdleConnectionTimeout(), Runtime.getRuntime().availableProcessors());
    filebeatServer.setMessageListener(new FilebeatIngester(logsIngester,
        System::currentTimeMillis));
    startAsManagedThread(() -> {
      try {
        activeListeners.inc();
        filebeatServer.listen();
      } catch (InterruptedException e) {
        logger.info("Filebeat server on port " + port + " shut down");
      } catch (Exception e) {
        // ChannelFuture throws undeclared checked exceptions, so we need to handle it
        //noinspection ConstantConditions
        if (e instanceof BindException) {
          bindErrors.inc();
          logger.severe("Unable to start listener - port " + port + " is already in use!");
        } else {
          logger.log(Level.SEVERE, "Filebeat exception", e);
        }
      } finally {
        activeListeners.dec();
      }
    }, "listener-logs-filebeat-" + port);
    logger.info("listening on port: " + port + " for Filebeat logs");
  }

  @VisibleForTesting
  protected void startRawLogsIngestionListener(int port, LogsIngester logsIngester) {
    String strPort = String.valueOf(port);
    if (proxyConfig.isHttpHealthCheckAllPorts()) healthCheckManager.enableHealthcheck(port);
    ChannelHandler channelHandler = new RawLogsIngesterPortUnificationHandler(strPort, logsIngester,
        hostnameResolver, tokenAuthenticator, healthCheckManager, preprocessors.get(strPort));

    startAsManagedThread(new TcpIngester(createInitializer(channelHandler, strPort,
        proxyConfig.getRawLogsMaxReceivedLength(), proxyConfig.getRawLogsHttpBufferSize(),
        proxyConfig.getListenerIdleConnectionTimeout()), port).
        withChildChannelOptions(childChannelOptions), "listener-logs-raw-" + port);
    logger.info("listening on port: " + strPort + " for raw logs");
  }

  @VisibleForTesting
  protected void startAdminListener(int port) {
    ChannelHandler channelHandler = new AdminPortUnificationHandler(tokenAuthenticator,
        healthCheckManager, String.valueOf(port), proxyConfig.getAdminApiRemoteIpWhitelistRegex());

    startAsManagedThread(new TcpIngester(createInitializer(channelHandler, String.valueOf(port),
        proxyConfig.getPushListenerMaxReceivedLength(), proxyConfig.getPushListenerHttpBufferSize(),
        proxyConfig.getListenerIdleConnectionTimeout()), port).
        withChildChannelOptions(childChannelOptions), "listener-http-admin-" + port);
    logger.info("Admin port: " + port);
  }

  @VisibleForTesting
  protected void startHealthCheckListener(int port) {
    healthCheckManager.enableHealthcheck(port);
    ChannelHandler channelHandler = new HttpHealthCheckEndpointHandler(healthCheckManager, port);

    startAsManagedThread(new TcpIngester(createInitializer(channelHandler, String.valueOf(port),
        proxyConfig.getPushListenerMaxReceivedLength(), proxyConfig.getPushListenerHttpBufferSize(),
        proxyConfig.getListenerIdleConnectionTimeout()), port).
        withChildChannelOptions(childChannelOptions), "listener-http-healthcheck-" + port);
    logger.info("Health check port enabled: " + port);
  }

  protected void startHistogramListeners(Iterator<String> ports,
                                         ReportableEntityHandler<ReportPoint, String> pointHandler,
                                         SharedGraphiteHostAnnotator hostAnnotator,
                                         @Nullable Utils.Granularity granularity,
                                         int flushSecs, boolean memoryCacheEnabled,
                                         File baseDirectory, Long accumulatorSize, int avgKeyBytes,
                                         int avgDigestBytes, short compression, boolean persist) {
    if (!ports.hasNext()) return;
    String listenerBinType = Utils.Granularity.granularityToString(granularity);
    // Accumulator
    if (persist) {
      // Check directory
      checkArgument(baseDirectory.isDirectory(), baseDirectory.getAbsolutePath() +
          " must be a directory!");
      checkArgument(baseDirectory.canWrite(), baseDirectory.getAbsolutePath() +
          " must be write-able!");
    }

    MapLoader<HistogramKey, AgentDigest, HistogramKeyMarshaller, AgentDigestMarshaller> mapLoader =
        new MapLoader<>(
            HistogramKey.class,
            AgentDigest.class,
            accumulatorSize,
            avgKeyBytes,
            avgDigestBytes,
            HistogramKeyMarshaller.get(),
            AgentDigestMarshaller.get(),
            persist);
    File accumulationFile = new File(baseDirectory, "accumulator." + listenerBinType);
    ChronicleMap<HistogramKey, AgentDigest> accumulator = mapLoader.get(accumulationFile);

    histogramExecutor.scheduleWithFixedDelay(
        () -> {
          // warn if accumulator is more than 1.5x the original size,
          // as ChronicleMap starts losing efficiency
          if (accumulator.size() > accumulatorSize * 5) {
            logger.severe("Histogram " + listenerBinType + " accumulator size (" +
                accumulator.size() + ") is more than 5x higher than currently configured size (" +
                accumulatorSize + "), which may cause severe performance degradation issues " +
                "or data loss! If the data volume is expected to stay at this level, we strongly " +
                "recommend increasing the value for accumulator size in wavefront.conf and " +
                "restarting the proxy.");
          } else if (accumulator.size() > accumulatorSize * 2) {
            logger.warning("Histogram " + listenerBinType + " accumulator size (" +
                accumulator.size() + ") is more than 2x higher than currently configured size (" +
                accumulatorSize + "), which may cause performance issues. If the data volume is " +
                "expected to stay at this level, we strongly recommend increasing the value " +
                "for accumulator size in wavefront.conf and restarting the proxy.");
          }
        },
        10,
        10,
        TimeUnit.SECONDS);

    AgentDigestFactory agentDigestFactory = new AgentDigestFactory(compression,
        TimeUnit.SECONDS.toMillis(flushSecs));
    Accumulator cachedAccumulator = new AccumulationCache(accumulator, agentDigestFactory,
        (memoryCacheEnabled ? accumulatorSize : 0),
        "histogram.accumulator." + Utils.Granularity.granularityToString(granularity), null);

    // Schedule write-backs
    histogramExecutor.scheduleWithFixedDelay(
        cachedAccumulator::flush,
        proxyConfig.getHistogramAccumulatorResolveInterval(),
        proxyConfig.getHistogramAccumulatorResolveInterval(),
        TimeUnit.MILLISECONDS);

    PointHandlerDispatcher dispatcher = new PointHandlerDispatcher(cachedAccumulator, pointHandler,
        proxyConfig.getHistogramAccumulatorFlushMaxBatchSize() < 0 ? null :
            proxyConfig.getHistogramAccumulatorFlushMaxBatchSize(), granularity);

    histogramExecutor.scheduleWithFixedDelay(dispatcher,
        proxyConfig.getHistogramAccumulatorFlushInterval(),
        proxyConfig.getHistogramAccumulatorFlushInterval(), TimeUnit.MILLISECONDS);

    // gracefully shutdown persisted accumulator (ChronicleMap) on proxy exit
    shutdownTasks.add(() -> {
      try {
        logger.fine("Flushing in-flight histogram accumulator digests: " + listenerBinType);
        cachedAccumulator.flush();
        logger.fine("Shutting down histogram accumulator cache: " + listenerBinType);
        accumulator.close();
      } catch (Throwable t) {
        logger.log(Level.SEVERE, "Error flushing " + listenerBinType +
            " accumulator, possibly unclean shutdown: ", t);
      }
    });

    ReportableEntityHandlerFactory histogramHandlerFactory = new ReportableEntityHandlerFactory() {
      private final Map<HandlerKey, ReportableEntityHandler<?, ?>> handlers = new HashMap<>();
      @SuppressWarnings("unchecked")
      @Override
      public <T, U> ReportableEntityHandler<T, U> getHandler(HandlerKey handlerKey) {
          return (ReportableEntityHandler<T, U>) handlers.computeIfAbsent(handlerKey,
              k -> new HistogramAccumulationHandlerImpl(handlerKey.getHandle(), cachedAccumulator,
                  proxyConfig.getPushBlockedSamples(), granularity, () -> validationConfiguration,
                  granularity == null, blockedHistogramsLogger));
      }
    };

    ports.forEachRemaining(port -> {
      registerPrefixFilter(port);
      registerTimestampFilter(port);
      if (proxyConfig.isHttpHealthCheckAllPorts()) {
        healthCheckManager.enableHealthcheck(Integer.parseInt(port));
      }

      WavefrontPortUnificationHandler wavefrontPortUnificationHandler =
          new WavefrontPortUnificationHandler(port, tokenAuthenticator, healthCheckManager,
              decoderSupplier.get(), histogramHandlerFactory, hostAnnotator,
              preprocessors.get(port));
      startAsManagedThread(new TcpIngester(createInitializer(wavefrontPortUnificationHandler, port,
          proxyConfig.getHistogramMaxReceivedLength(), proxyConfig.getHistogramHttpBufferSize(),
          proxyConfig.getListenerIdleConnectionTimeout()), Integer.parseInt(port)).
          withChildChannelOptions(childChannelOptions), "listener-histogram-" + port);
      logger.info("listening on port: " + port + " for histogram samples, accumulating to the " +
          listenerBinType);
    });

  }

  private Iterator<String> portIterator(@Nullable String inputString) {
    return inputString == null ?
        Collections.emptyIterator() :
        Splitter.on(",").omitEmptyStrings().trimResults().split(inputString).iterator();
  }

  private void registerTimestampFilter(String strPort) {
    preprocessors.getSystemPreprocessor(strPort).forReportPoint().addFilter(
        new ReportPointTimestampInRangeFilter(proxyConfig.getDataBackfillCutoffHours(),
            proxyConfig.getDataPrefillCutoffHours()));
  }

  private void registerPrefixFilter(String strPort) {
    if (proxyConfig.getPrefix() != null && !proxyConfig.getPrefix().isEmpty()) {
      preprocessors.getSystemPreprocessor(strPort).forReportPoint().
          addTransformer(new ReportPointAddPrefixTransformer(proxyConfig.getPrefix()));
    }
  }

  /**
   * Push agent configuration during check-in by the collector.
   *
   * @param config The configuration to process.
   */
  @Override
  protected void processConfiguration(AgentConfiguration config) {
    try {
      apiContainer.getProxyV2API().proxyConfigProcessed(agentId);
      Long pointsPerBatch = config.getPointsPerBatch();
      if (BooleanUtils.isTrue(config.getCollectorSetsPointsPerBatch())) {
        if (pointsPerBatch != null) {
          // if the collector is in charge and it provided a setting, use it
          runtimeProperties.setItemsPerBatch(pointsPerBatch.intValue());
          logger.fine("Proxy push batch set to (remotely) " + pointsPerBatch);
        } // otherwise don't change the setting
      } else {
        // restores the agent setting
        runtimeProperties.setItemsPerBatch(runtimeProperties.getItemsPerBatchOriginal());
        logger.fine("Proxy push batch set to (locally) " + runtimeProperties.getItemsPerBatch());
      }

      updateRateLimiter("Proxy rate limit",
          rateLimiterFactory.getRateLimiter(HandlerKey.of(ReportableEntityType.POINT, "")),
          proxyConfig.getPushRateLimit(),
          runtimeProperties.getItemsPerBatch(), runtimeProperties.getItemsPerBatchOriginal(),
          config.getCollectorSetsRateLimit(), config.getCollectorRateLimit(),
          config.getGlobalCollectorRateLimit(), runtimeProperties::setItemsPerBatch);
      updateRateLimiter("Histogram rate limit",
          rateLimiterFactory.getRateLimiter(HandlerKey.of(ReportableEntityType.HISTOGRAM, "")),
          proxyConfig.getPushRateLimitHistograms(),
          runtimeProperties.getItemsPerBatchHistograms(),
          runtimeProperties.getItemsPerBatchHistogramsOriginal(),
          config.getCollectorSetsRateLimit(), config.getHistogramRateLimit(),
          config.getGlobalHistogramRateLimit(), runtimeProperties::setItemsPerBatchHistograms);
      updateRateLimiter("Source tag rate limit",
          rateLimiterFactory.getRateLimiter(HandlerKey.of(ReportableEntityType.SOURCE_TAG, "")),
          proxyConfig.getPushRateLimitSourceTags(),
          runtimeProperties.getItemsPerBatchSourceTags(),
          runtimeProperties.getItemsPerBatchSourceTagsOriginal(),
          config.getCollectorSetsRateLimit(), config.getSourceTagsRateLimit(),
          config.getGlobalSourceTagRateLimit(), runtimeProperties::setItemsPerBatchSourceTags);
      updateRateLimiter("Span rate limit",
          rateLimiterFactory.getRateLimiter(HandlerKey.of(ReportableEntityType.TRACE, "")),
          proxyConfig.getPushRateLimitSpans(),
          runtimeProperties.getItemsPerBatchSpans(),
          runtimeProperties.getItemsPerBatchSpansOriginal(),
          config.getCollectorSetsRateLimit(), config.getSpanRateLimit(),
          config.getGlobalSpanRateLimit(), runtimeProperties::setItemsPerBatchSpans);
      updateRateLimiter("Span log rate limit",
          rateLimiterFactory.getRateLimiter(HandlerKey.of(ReportableEntityType.TRACE_SPAN_LOGS, "")),
          proxyConfig.getPushRateLimitSpanLogs(),
          runtimeProperties.getItemsPerBatchSpanLogs(),
          runtimeProperties.getItemsPerBatchSpanLogsOriginal(),
          config.getCollectorSetsRateLimit(), config.getSpanLogsRateLimit(),
          config.getGlobalSpanLogsRateLimit(), runtimeProperties::setItemsPerBatchSpanLogs);
      updateRateLimiter("Event rate limit",
          rateLimiterFactory.getRateLimiter(HandlerKey.of(ReportableEntityType.EVENT, "")),
          proxyConfig.getPushRateLimitEvents(),
          runtimeProperties.getItemsPerBatchEvents(),
          runtimeProperties.getItemsPerBatchEventsOriginal(), config.getCollectorSetsRateLimit(),
          config.getEventsRateLimit(), config.getGlobalEventRateLimit(),
          runtimeProperties::setItemsPerBatchEvents);

      if (BooleanUtils.isTrue(config.getCollectorSetsRetryBackoff())) {
        if (config.getRetryBackoffBaseSeconds() != null) {
          // if the collector is in charge and it provided a setting, use it
          runtimeProperties.setRetryBackoffBaseSeconds(config.getRetryBackoffBaseSeconds());
          logger.fine("Proxy backoff base set to (remotely) " +
              config.getRetryBackoffBaseSeconds());
        } // otherwise don't change the setting
      } else {
        // restores the agent setting
        runtimeProperties.setRetryBackoffBaseSeconds(
            runtimeProperties.getRetryBackoffBaseSecondsOriginal());
        logger.fine("Proxy backoff base set to (locally) " +
            runtimeProperties.getRetryBackoffBaseSeconds());
      }

      histogramDisabled.set(BooleanUtils.toBoolean(config.getHistogramDisabled()));
      traceDisabled.set(BooleanUtils.toBoolean(config.getTraceDisabled()));
      spanLogsDisabled.set(BooleanUtils.toBoolean(config.getSpanLogsDisabled()));
    } catch (RuntimeException e) {
      // cannot throw or else configuration update thread would die.
    }
  }

  private void updateRateLimiter(String name,
                                 @Nullable RecyclableRateLimiter pushRateLimiter,
                                 Number initialRateLimit,
                                 int itemsPerBatch,
                                 int itemsPerBatchOriginal,
                                 @Nullable Boolean collectorSetsRateLimit,
                                 @Nullable Number collectorRateLimit,
                                 @Nullable Number globalRateLimit,
                                 @Nonnull Consumer<Integer> setItemsPerBatch) {
    if (pushRateLimiter != null) {
      if (BooleanUtils.isTrue(collectorSetsRateLimit)) {
        if (collectorRateLimit != null &&
            pushRateLimiter.getRate() != collectorRateLimit.doubleValue()) {
          pushRateLimiter.setRate(collectorRateLimit.doubleValue());
          setItemsPerBatch.accept(Math.min(collectorRateLimit.intValue(), itemsPerBatch));
          logger.warning(name + " set to " + collectorRateLimit + " remotely");
        }
      } else {
        double rateLimit = Math.min(initialRateLimit.doubleValue(),
            ObjectUtils.firstNonNull(globalRateLimit, NO_RATE_LIMIT).intValue());
        if (pushRateLimiter.getRate() != rateLimit) {
          pushRateLimiter.setRate(rateLimit);
          setItemsPerBatch.accept(itemsPerBatchOriginal);
          if (rateLimit >= NO_RATE_LIMIT) {
            logger.warning(name + " no longer enforced by remote");
          } else {
            if (hadSuccessfulCheckin) { // this will skip printing this message upon init
              logger.warning(name + " restored to " + rateLimit);
            }
          }
        }
      }
    }
  }

  @Override
  protected void configureTokenAuthenticator() {
    HttpClient httpClient = HttpClientBuilder.create().
        useSystemProperties().
        setUserAgent(proxyConfig.getHttpUserAgent()).
        setMaxConnPerRoute(10).
        setMaxConnTotal(10).
        setConnectionTimeToLive(1, TimeUnit.MINUTES).
        setRetryHandler(new DefaultHttpRequestRetryHandler(proxyConfig.getHttpAutoRetries(), true)).
        setDefaultRequestConfig(
            RequestConfig.custom().
                setContentCompressionEnabled(true).
                setRedirectsEnabled(true).
                setConnectTimeout(proxyConfig.getHttpConnectTimeout()).
                setConnectionRequestTimeout(proxyConfig.getHttpConnectTimeout()).
                setSocketTimeout(proxyConfig.getHttpRequestTimeout()).build()).
        build();

    this.tokenAuthenticator = TokenAuthenticatorBuilder.create().
        setTokenValidationMethod(proxyConfig.getAuthMethod()).
        setHttpClient(httpClient).
        setTokenIntrospectionServiceUrl(proxyConfig.getAuthTokenIntrospectionServiceUrl()).
        setTokenIntrospectionAuthorizationHeader(
            proxyConfig.getAuthTokenIntrospectionAuthorizationHeader()).
        setAuthResponseRefreshInterval(proxyConfig.getAuthResponseRefreshInterval()).
        setAuthResponseMaxTtl(proxyConfig.getAuthResponseMaxTtl()).
        setStaticToken(proxyConfig.getAuthStaticToken()).
        build();
  }

  protected void startAsManagedThread(Runnable target, @Nullable String threadName) {
    Thread thread = new Thread(target);
    if (threadName != null) {
      thread.setName(threadName);
    }
    managedThreads.add(thread);
    thread.start();
  }

  @Override
  public void stopListeners() {
    managedThreads.forEach(Thread::interrupt);
    managedThreads.forEach(thread -> {
      try {
        thread.join(TimeUnit.SECONDS.toMillis(10));
      } catch (InterruptedException e) {
        // ignore
      }
    });
  }
}
