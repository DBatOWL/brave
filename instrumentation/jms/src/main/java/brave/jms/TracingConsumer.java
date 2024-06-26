/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.jms;

import brave.Span;
import brave.Tracing;
import brave.internal.Nullable;
import brave.messaging.MessagingRequest;
import brave.propagation.TraceContext.Extractor;
import brave.propagation.TraceContext.Injector;
import brave.propagation.TraceContextOrSamplingFlags;
import brave.sampler.SamplerFunction;
import javax.jms.Destination;
import javax.jms.Message;

abstract class TracingConsumer<C> {
  final C delegate;
  final JmsTracing jmsTracing;
  final Tracing tracing;
  final Extractor<MessageConsumerRequest> extractor;
  final Injector<MessageConsumerRequest> injector;
  final SamplerFunction<MessagingRequest> sampler;
  @Nullable final String remoteServiceName;

  TracingConsumer(C delegate, JmsTracing jmsTracing) {
    this.delegate = delegate;
    this.jmsTracing = jmsTracing;
    this.tracing = jmsTracing.tracing;
    this.extractor = jmsTracing.messageConsumerExtractor;
    this.sampler = jmsTracing.consumerSampler;
    this.injector = jmsTracing.messageConsumerInjector;
    this.remoteServiceName = jmsTracing.remoteServiceName;
  }

  void handleReceive(Message message) {
    if (message == null || tracing.isNoop()) return;
    MessageConsumerRequest request = new MessageConsumerRequest(message, destination(message));

    TraceContextOrSamplingFlags extracted =
      jmsTracing.extractAndClearTraceIdProperties(extractor, request, message);
    Span span = jmsTracing.nextMessagingSpan(sampler, request, extracted);

    if (!span.isNoop()) {
      span.name("receive").kind(Span.Kind.CONSUMER);
      Destination destination = destination(message);
      if (destination != null) jmsTracing.tagQueueOrTopic(request, span);
      if (remoteServiceName != null) span.remoteServiceName(remoteServiceName);

      // incur timestamp overhead only once
      long timestamp = tracing.clock(span.context()).currentTimeMicroseconds();
      span.start(timestamp).finish(timestamp);
    }
    injector.inject(span.context(), request);
  }

  abstract @Nullable Destination destination(Message message);
}
