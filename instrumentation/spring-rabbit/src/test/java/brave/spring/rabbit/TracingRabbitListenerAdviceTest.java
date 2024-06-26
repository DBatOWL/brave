/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.spring.rabbit;

import brave.Tracing;
import brave.baggage.BaggagePropagation;
import brave.baggage.BaggagePropagationConfig;
import brave.handler.MutableSpan;
import brave.propagation.B3Propagation;
import brave.propagation.StrictCurrentTraceContext;
import brave.test.TestSpanHandler;
import java.util.Arrays;
import java.util.List;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;

import static brave.Span.Kind.CONSUMER;
import static brave.test.ITRemote.BAGGAGE_FIELD;
import static brave.test.ITRemote.BAGGAGE_FIELD_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TracingRabbitListenerAdviceTest {
  static String TRACE_ID = "463ac35c9f6413ad";
  static String PARENT_ID = "463ac35c9f6413ab";
  static String SPAN_ID = "48485a3953bb6124";
  static String SAMPLED = "1";

  static String TRACE_ID_2 = "463ac35c9f6413ae";
  static String PARENT_ID_2 = "463ac35c9f6413af";
  static String SPAN_ID_2 = "48485a3953bb6125";

  StrictCurrentTraceContext currentTraceContext = StrictCurrentTraceContext.create();
  TestSpanHandler spans = new TestSpanHandler();
  Tracing tracing = Tracing.newBuilder()
    .currentTraceContext(currentTraceContext)
    .addSpanHandler(spans)
    .propagationFactory(BaggagePropagation.newFactoryBuilder(B3Propagation.FACTORY)
      .add(BaggagePropagationConfig.SingleBaggageField.newBuilder(BAGGAGE_FIELD)
        .addKeyName(BAGGAGE_FIELD_KEY)
        .build()).build())
    .build();

  TracingRabbitListenerAdvice tracingRabbitListenerAdvice = new TracingRabbitListenerAdvice(
    SpringRabbitTracing.newBuilder(tracing).remoteServiceName("my-exchange").build()
  );
  MethodInvocation methodInvocation = mock(MethodInvocation.class);

  @AfterEach void close() {
    tracing.close();
    currentTraceContext.close();
  }

  @Test void starts_new_trace_if_none_exists() throws Throwable {
    Message message = MessageBuilder.withBody(new byte[0]).build();
    onMessageConsumed(message);

    assertThat(spans)
      .extracting(MutableSpan::kind)
      .containsExactly(CONSUMER, null);
  }

  @Test void consumer_and_listener_have_names() throws Throwable {
    Message message = MessageBuilder.withBody(new byte[0]).build();
    onMessageConsumed(message);

    assertThat(spans)
      .extracting(MutableSpan::name)
      .containsExactly("next-message", "on-message");
  }

  @Test void consumer_has_remote_service_name() throws Throwable {
    Message message = MessageBuilder.withBody(new byte[0]).build();
    onMessageConsumed(message);

    assertThat(spans)
      .extracting(MutableSpan::remoteServiceName)
      .containsExactly("my-exchange", null);
  }

  @Test void tags_consumer_span_but_not_listener() throws Throwable {
    MessageProperties properties = new MessageProperties();
    properties.setConsumerQueue("foo");

    Message message = MessageBuilder.withBody(new byte[0]).andProperties(properties).build();
    onMessageConsumed(message);

    assertThat(spans.get(0).tags())
      .containsExactly(entry("rabbit.queue", "foo"));
    assertThat(spans.get(1).tags())
      .isEmpty();
  }

  @Test void consumer_span_starts_before_listener() throws Throwable {
    Message message = MessageBuilder.withBody(new byte[0]).build();
    onMessageConsumed(message);

    // make sure one before the other
    assertThat(spans.get(0).startTimestamp())
      .isLessThan(spans.get(1).startTimestamp());

    // make sure they finished
    assertThat(spans.get(0).finishTimestamp())
      .isPositive();
    assertThat(spans.get(1).finishTimestamp())
      .isPositive();
  }

  @Test void continues_parent_trace() throws Throwable {
    MessageProperties props = new MessageProperties();
    props.setHeader("X-B3-TraceId", TRACE_ID);
    props.setHeader("X-B3-SpanId", SPAN_ID);
    props.setHeader("X-B3-ParentSpanId", PARENT_ID);
    props.setHeader("X-B3-Sampled", SAMPLED);

    Message message = MessageBuilder.withBody(new byte[0]).andProperties(props).build();
    onMessageConsumed(message);

    // cleared the headers to later work doesn't try to use the old parent
    assertThat(message.getMessageProperties().getHeaders()).isEmpty();

    assertThat(spans)
      .filteredOn(span -> span.kind() == CONSUMER)
      .extracting(MutableSpan::parentId)
      .contains(SPAN_ID);
  }

  @Test void continues_parent_trace_single_header() throws Throwable {
    MessageProperties props = new MessageProperties();
    props.setHeader("b3", TRACE_ID + "-" + SPAN_ID + "-" + SAMPLED);

    Message message = MessageBuilder.withBody(new byte[0]).andProperties(props).build();
    onMessageConsumed(message);

    // cleared the headers to later work doesn't try to use the old parent
    assertThat(message.getMessageProperties().getHeaders()).isEmpty();

    assertThat(spans)
      .filteredOn(span -> span.kind() == CONSUMER)
      .extracting(MutableSpan::parentId)
      .contains(SPAN_ID);
  }

  @Test void retains_baggage_headers() throws Throwable {
    MessageProperties props = new MessageProperties();
    props.setHeader("b3", TRACE_ID + "-" + SPAN_ID + "-" + SAMPLED);
    props.setHeader(BAGGAGE_FIELD_KEY, "");

    Message message = MessageBuilder.withBody(new byte[0]).andProperties(props).build();
    onMessageConsumed(message);

    assertThat(message.getMessageProperties().getHeaders())
      .hasSize(1) // clears b3
      .containsEntry(BAGGAGE_FIELD_KEY, "");
  }

  @Test void reports_span_if_consume_fails() throws Throwable {
    Message message = MessageBuilder.withBody(new byte[0]).build();
    RuntimeException error = new RuntimeException("Test exception");
    onMessageConsumeFailed(message, error);

    assertThat(spans)
      .extracting(MutableSpan::kind)
      .containsExactly(CONSUMER, null);

    assertThat(spans)
      .filteredOn(span -> span.kind() == null)
      .extracting(MutableSpan::error)
      .containsExactly(error);
  }

  @Test void reports_span_if_consume_fails_with_no_message() throws Throwable {
    Message message = MessageBuilder.withBody(new byte[0]).build();
    RuntimeException error = new RuntimeException("Test exception");
    onMessageConsumeFailed(message, error);

    assertThat(spans)
      .extracting(MutableSpan::kind)
      .containsExactly(CONSUMER, null);

    assertThat(spans)
      .filteredOn(span -> span.kind() == null)
      .extracting(MutableSpan::error)
      .containsExactly(error);
  }

  @Test void batch_starts_new_trace_if_none_exists() throws Throwable {
    // non traced that listener is a new trace
    onBatchMessageConsumed(Arrays.asList(MessageBuilder.withBody(new byte[0]).build(),
      MessageBuilder.withBody(new byte[0]).build()));

    assertThat(spans)
      .extracting(MutableSpan::kind)
      .containsExactly(CONSUMER, null);
  }

  @Test void batch_continue_parent_trace() throws Throwable {
    MessageProperties props = new MessageProperties();
    props.setHeader("X-B3-TraceId", TRACE_ID);
    props.setHeader("X-B3-SpanId", SPAN_ID);
    props.setHeader("X-B3-ParentSpanId", PARENT_ID);
    props.setHeader("X-B3-Sampled", SAMPLED);

    Message message = MessageBuilder.withBody(new byte[0]).andProperties(props).build();
    MessageProperties props2 = new MessageProperties();
    props2.setHeader("X-B3-TraceId", TRACE_ID_2);
    props2.setHeader("X-B3-SpanId", SPAN_ID_2);
    props2.setHeader("X-B3-ParentSpanId", PARENT_ID_2);
    props2.setHeader("X-B3-Sampled", SAMPLED);
    Message message2 = MessageBuilder.withBody(new byte[0]).andProperties(props2).build();
    onBatchMessageConsumed(Arrays.asList(message, message2));

    // cleared the headers to later work doesn't try to use the old parent
    assertThat(message.getMessageProperties().getHeaders()).isEmpty();

    // two traced that listener continues first trace but TODO we aren't handling the second.
    assertThat(spans.get(0))
      .extracting(MutableSpan::parentId)
      .isEqualTo(SPAN_ID);
  }

  @Test void batch_continue_first_traced() throws Throwable {
    MessageProperties props = new MessageProperties();
    props.setHeader("X-B3-TraceId", TRACE_ID);
    props.setHeader("X-B3-SpanId", SPAN_ID);
    props.setHeader("X-B3-ParentSpanId", PARENT_ID);
    props.setHeader("X-B3-Sampled", SAMPLED);

    Message message = MessageBuilder.withBody(new byte[0]).andProperties(props).build();
    Message message2 = MessageBuilder.withBody(new byte[0]).build();
    onBatchMessageConsumed(Arrays.asList(message, message2));

    // cleared the headers to later work doesn't try to use the old parent
    assertThat(message.getMessageProperties().getHeaders()).isEmpty();

    // first traced that listener continues that trace
    assertThat(spans.get(0))
      .extracting(MutableSpan::parentId)
      .isEqualTo(SPAN_ID);
  }

  @Test void batch_new_trace_last_traced() throws Throwable {
    MessageProperties props = new MessageProperties();
    props.setHeader("X-B3-TraceId", TRACE_ID_2);
    props.setHeader("X-B3-SpanId", SPAN_ID_2);
    props.setHeader("X-B3-ParentSpanId", PARENT_ID_2);
    props.setHeader("X-B3-Sampled", SAMPLED);

    Message message = MessageBuilder.withBody(new byte[0]).build();
    Message message2 = MessageBuilder.withBody(new byte[0]).andProperties(props).build();
    onBatchMessageConsumed(Arrays.asList(message, message2));

    // new trace
    assertThat(spans.get(0))
      .extracting(MutableSpan::parentId)
      .isNotEqualTo(SPAN_ID);
  }

  void onMessageConsumed(Message message) throws Throwable {
    when(methodInvocation.getArguments()).thenReturn(new Object[] {
      null, // AMQPChannel - doesn't matter
      message
    });
    when(methodInvocation.proceed()).thenReturn("doesn't matter");

    tracingRabbitListenerAdvice.invoke(methodInvocation);
  }

  void onMessageConsumeFailed(Message message, Throwable throwable) throws Throwable {
    when(methodInvocation.getArguments()).thenReturn(new Object[] {
      null, // AMQPChannel - doesn't matter
      message
    });
    when(methodInvocation.proceed()).thenThrow(throwable);

    try {
      tracingRabbitListenerAdvice.invoke(methodInvocation);
      fail("should have thrown exception");
    } catch (RuntimeException ex) {
    }
  }


  void onBatchMessageConsumed(List<Message> messages) throws Throwable {
    when(methodInvocation.getArguments()).thenReturn(new Object[] {
      null, // AMQPChannel - doesn't matter
      messages
    });
    when(methodInvocation.proceed()).thenReturn("doesn't matter");

    tracingRabbitListenerAdvice.invoke(methodInvocation);
  }
}
