package com.wavefront.agent.listeners.tracing;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.wavefront.agent.auth.TokenAuthenticatorBuilder;
import com.wavefront.agent.channel.NoopHealthCheckManager;
import com.wavefront.agent.handlers.MockReportableEntityHandlerFactory;
import com.wavefront.agent.handlers.ReportableEntityHandler;
import com.wavefront.sdk.entities.tracing.sampling.RateSampler;
import io.jaegertracing.thriftjava.Batch;
import io.jaegertracing.thriftjava.Log;
import io.jaegertracing.thriftjava.Process;
import io.jaegertracing.thriftjava.Tag;
import io.jaegertracing.thriftjava.TagType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import org.apache.thrift.TSerializer;
import org.easymock.EasyMock;
import org.junit.Test;
import wavefront.report.Annotation;
import wavefront.report.Span;
import wavefront.report.SpanLog;
import wavefront.report.SpanLogs;

import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.reset;
import static org.easymock.EasyMock.verify;

/**
 * Unit tests for {@link JaegerPortUnificationHandler}.
 *
 * @author Han Zhang (zhanghan@vmware.com)
 */
public class JaegerPortUnificationHandlerTest {
  private final static String DEFAULT_SOURCE = "jaeger";
  private ReportableEntityHandler<Span> mockTraceHandler =
      MockReportableEntityHandlerFactory.getMockTraceHandler();
  private ReportableEntityHandler<SpanLogs> mockTraceSpanLogsHandler =
      MockReportableEntityHandlerFactory.getMockTraceSpanLogsHandler();
  private ChannelHandlerContext mockCtx = createNiceMock(ChannelHandlerContext.class);

  private long startTime = System.currentTimeMillis();

  @Test
  public void testJaegerPortUnificationHandler() throws Exception {
    reset(mockTraceHandler, mockTraceSpanLogsHandler, mockCtx);
    mockTraceHandler.report(Span.newBuilder().setCustomer("dummy").setStartMillis(startTime)
        .setDuration(1234)
        .setName("HTTP GET")
        .setSource(DEFAULT_SOURCE)
        .setSpanId("00000000-0000-0000-0000-00000012d687")
        .setTraceId("00000000-4996-02d2-0000-011f71fb04cb")
        // Note: Order of annotations list matters for this unit test.
        .setAnnotations(ImmutableList.of(
            new Annotation("ip", "10.0.0.1"),
            new Annotation("service", "frontend"),
            new Annotation("component", "db"),
            new Annotation("application", "Jaeger"),
            new Annotation("cluster", "none"),
            new Annotation("shard", "none"),
            new Annotation("_spanLogs", "true")))
        .build());
    expectLastCall();

    mockTraceSpanLogsHandler.report(SpanLogs.newBuilder().
        setCustomer("default").
        setSpanId("00000000-0000-0000-0000-00000012d687").
        setTraceId("00000000-4996-02d2-0000-011f71fb04cb").
        setLogs(ImmutableList.of(
            SpanLog.newBuilder().
                setTimestamp(startTime * 1000).
                setFields(ImmutableMap.of("event", "error", "exception", "NullPointerException")).
                build()
        )).
        build());
    expectLastCall();

    mockTraceHandler.report(Span.newBuilder().setCustomer("dummy").setStartMillis(startTime)
        .setDuration(2345)
        .setName("HTTP GET /")
        .setSource(DEFAULT_SOURCE)
        .setSpanId("00000000-0000-0000-0000-00000023cace")
        .setTraceId("00000000-4996-02d2-0000-011f71fb04cb")
        // Note: Order of annotations list matters for this unit test.
        .setAnnotations(ImmutableList.of(
            new Annotation("ip", "10.0.0.1"),
            new Annotation("service", "frontend"),
            new Annotation("parent", "00000000-0000-0000-0000-00000012d687"),
            new Annotation("component", "db"),
            new Annotation("application", "Custom-JaegerApp"),
            new Annotation("cluster", "none"),
            new Annotation("shard", "none")))
        .build());
    expectLastCall();

    mockTraceHandler.report(Span.newBuilder().setCustomer("dummy").setStartMillis(startTime)
        .setDuration(3456)
        .setName("HTTP GET /")
        .setSource(DEFAULT_SOURCE)
        .setSpanId("00000000-0000-0000-9a12-b85901d53397")
        .setTraceId("00000000-0000-0000-fea4-87ee36e58cab")
        // Note: Order of annotations list matters for this unit test.
        .setAnnotations(ImmutableList.of(
            new Annotation("ip", "10.0.0.1"),
            new Annotation("service", "frontend"),
            new Annotation("parent", "00000000-0000-0000-fea4-87ee36e58cab"),
            new Annotation("application", "Jaeger"),
            new Annotation("cluster", "none"),
            new Annotation("shard", "none")))
        .build());
    expectLastCall();

    // Test filtering empty tags
    mockTraceHandler.report(Span.newBuilder().setCustomer("dummy").setStartMillis(startTime)
        .setDuration(3456)
        .setName("HTTP GET /test")
        .setSource(DEFAULT_SOURCE)
        .setSpanId("00000000-0000-0000-0000-0051759bfc69")
        .setTraceId("0000011e-ab2a-9944-0000-000049631900")
        // Note: Order of annotations list matters for this unit test.
        .setAnnotations(ImmutableList.of(
            new Annotation("ip", "10.0.0.1"),
            new Annotation("service", "frontend"),
            new Annotation("application", "Jaeger"),
            new Annotation("cluster", "none"),
            new Annotation("shard", "none")))
        .build());
    expectLastCall();

    expect(mockCtx.write(EasyMock.isA(FullHttpResponse.class))).andReturn(null).anyTimes();

    replay(mockTraceHandler, mockTraceSpanLogsHandler, mockCtx);

    JaegerPortUnificationHandler handler = new JaegerPortUnificationHandler("14268",
        TokenAuthenticatorBuilder.create().build(), new NoopHealthCheckManager(),
        mockTraceHandler, mockTraceSpanLogsHandler, null, () -> false, () -> false, null,
        new RateSampler(1.0D), false, null, null);

    Tag ipTag = new Tag("ip", TagType.STRING);
    ipTag.setVStr("10.0.0.1");

    Tag componentTag = new Tag("component", TagType.STRING);
    componentTag.setVStr("db");

    Tag customApplicationTag = new Tag("application", TagType.STRING);
    customApplicationTag.setVStr("Custom-JaegerApp");

    Tag emptyTag = new Tag("empty", TagType.STRING);
    emptyTag.setVStr("");

    io.jaegertracing.thriftjava.Span span1 = new io.jaegertracing.thriftjava.Span(1234567890123L, 1234567890L,
        1234567L, 0L, "HTTP GET", 1, startTime * 1000, 1234 * 1000);

    io.jaegertracing.thriftjava.Span span2 = new io.jaegertracing.thriftjava.Span(1234567890123L, 1234567890L,
        2345678L, 1234567L, "HTTP GET /", 1, startTime * 1000, 2345 * 1000);

    // check negative span IDs too
    io.jaegertracing.thriftjava.Span span3 = new io.jaegertracing.thriftjava.Span(-97803834702328661L, 0L,
        -7344605349865507945L, -97803834702328661L, "HTTP GET /", 1, startTime * 1000, 3456 * 1000);

    io.jaegertracing.thriftjava.Span span4 = new io.jaegertracing.thriftjava.Span(1231231232L, 1231232342340L,
        349865507945L, 0, "HTTP GET /test", 1, startTime * 1000, 3456 * 1000);

    span1.setTags(ImmutableList.of(componentTag));
    span2.setTags(ImmutableList.of(componentTag, customApplicationTag));
    span4.setTags(ImmutableList.of(emptyTag));

    Tag tag1 = new Tag("event", TagType.STRING);
    tag1.setVStr("error");
    Tag tag2 = new Tag("exception", TagType.STRING);
    tag2.setVStr("NullPointerException");
    span1.setLogs(ImmutableList.of(new Log(startTime * 1000,
        ImmutableList.of(tag1, tag2))));

    Batch testBatch = new Batch();
    testBatch.process = new Process();
    testBatch.process.serviceName = "frontend";
    testBatch.process.setTags(ImmutableList.of(ipTag));

    testBatch.setSpans(ImmutableList.of(span1, span2, span3, span4));

    ByteBuf content = Unpooled.copiedBuffer(new TSerializer().serialize(testBatch));

    FullHttpRequest httpRequest = new DefaultFullHttpRequest(
        HttpVersion.HTTP_1_1,
        HttpMethod.POST,
        "http://localhost:14268/api/traces",
        content,
        true
    );
    handler.handleHttpMessage(mockCtx, httpRequest);
    verify(mockTraceHandler, mockTraceSpanLogsHandler);
  }
}
