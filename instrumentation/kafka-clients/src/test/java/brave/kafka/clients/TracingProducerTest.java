/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.kafka.clients;

import brave.handler.MutableSpan;
import brave.propagation.CurrentTraceContext.Scope;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import static brave.Span.Kind.PRODUCER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

public class TracingProducerTest extends KafkaTest {
  MockProducer<String, String> mockProducer = new MockProducer<>(false, new StringSerializer(), new StringSerializer());
  TracingProducer<String, String> tracingProducer =
    (TracingProducer<String, String>) kafkaTracing.producer(mockProducer);

  @Test void should_add_b3_headers_to_records() {
    tracingProducer.send(producerRecord);

    List<String> headerKeys = mockProducer.history().stream()
      .flatMap(records -> Arrays.stream(records.headers().toArray()))
      .map(Header::key)
      .collect(Collectors.toList());

    assertThat(headerKeys).containsOnly("b3");
  }

  @Test void should_add_b3_headers_when_other_headers_exist() {
    ProducerRecord<String, String> record = new ProducerRecord<>(TEST_TOPIC, TEST_KEY, TEST_VALUE);
    record.headers().add("tx-id", "1".getBytes());
    tracingProducer.send(record);
    mockProducer.completeNext();

    MutableSpan producerSpan = spans.get(0);
    assertThat(producerSpan.kind()).isEqualTo(PRODUCER);
    assertThat(lastHeaders(mockProducer))
      .containsEntry("tx-id", "1")
      .containsEntry("b3", producerSpan.traceId() + "-" + producerSpan.id() + "-1");
  }

  @Test void should_inject_child_context() {
    try (Scope scope = currentTraceContext.newScope(parent)) {
      tracingProducer.send(new ProducerRecord<>(TEST_TOPIC, TEST_KEY, TEST_VALUE));
      mockProducer.completeNext();
    }

    MutableSpan producerSpan = spans.get(0);
    assertThat(producerSpan.kind()).isEqualTo(PRODUCER);
    assertChildOf(producerSpan, parent);
    assertThat(lastHeaders(mockProducer))
      .containsEntry("b3", producerSpan.traceId() + "-" + producerSpan.id() + "-1");
  }

  @Test void should_add_parent_trace_when_context_injected_on_headers() {
    ProducerRecord<String, String> record = new ProducerRecord<>(TEST_TOPIC, TEST_KEY, TEST_VALUE);
    tracingProducer.injector.inject(parent, new KafkaProducerRequest(record));
    tracingProducer.send(record);
    mockProducer.completeNext();

    MutableSpan producerSpan = spans.get(0);
    assertThat(producerSpan.kind()).isEqualTo(PRODUCER);
    assertChildOf(producerSpan, parent);
    assertThat(lastHeaders(mockProducer))
      .containsEntry("b3", producerSpan.traceId() + "-" + producerSpan.id() + "-1");
  }

  @Test void should_call_wrapped_producer() {
    tracingProducer.send(new ProducerRecord<>(TEST_TOPIC, TEST_KEY, TEST_VALUE));

    assertThat(mockProducer.history()).hasSize(1);
  }

  @Test void send_should_set_name() {
    tracingProducer.send(new ProducerRecord<>(TEST_TOPIC, TEST_KEY, TEST_VALUE));
    mockProducer.completeNext();

    MutableSpan producerSpan = spans.get(0);
    assertThat(producerSpan.kind()).isEqualTo(PRODUCER);
    assertThat(producerSpan.name()).isEqualTo("send");
  }

  @Test void send_should_tag_topic_and_key() {
    tracingProducer.send(new ProducerRecord<>(TEST_TOPIC, TEST_KEY, TEST_VALUE));
    mockProducer.completeNext();

    MutableSpan producerSpan = spans.get(0);
    assertThat(producerSpan.kind()).isEqualTo(PRODUCER);
    assertThat(producerSpan.tags())
      .containsOnly(entry("kafka.topic", TEST_TOPIC), entry("kafka.key", TEST_KEY));
  }

  @Test void send_shouldnt_tag_null_key() {
    tracingProducer.send(new ProducerRecord<>(TEST_TOPIC, null, TEST_VALUE));
    mockProducer.completeNext();

    MutableSpan producerSpan = spans.get(0);
    assertThat(producerSpan.kind()).isEqualTo(PRODUCER);
    assertThat(producerSpan.tags())
      .containsOnly(entry("kafka.topic", TEST_TOPIC));
  }

  @Test void send_shouldnt_tag_binary_key() {
    MockProducer<byte[], String> mockProducer = new MockProducer<>(false, new ByteArraySerializer(), new StringSerializer());
    TracingProducer<byte[], String> tracingProducer =
      (TracingProducer<byte[], String>) kafkaTracing.producer(mockProducer);
    tracingProducer.send(new ProducerRecord<>(TEST_TOPIC, new byte[1], TEST_VALUE));
    mockProducer.completeNext();

    MutableSpan producerSpan = spans.get(0);
    assertThat(producerSpan.kind()).isEqualTo(PRODUCER);
    assertThat(producerSpan.tags())
      .containsOnly(entry("kafka.topic", TEST_TOPIC));
  }

  @Test void should_not_error_if_headers_are_read_only() {
    final ProducerRecord<String, String> record = new ProducerRecord<>(TEST_TOPIC, TEST_KEY, TEST_VALUE);
    ((RecordHeaders) record.headers()).setReadOnly();
    tracingProducer.send(record);
  }
}
