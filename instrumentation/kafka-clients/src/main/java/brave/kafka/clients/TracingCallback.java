/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.kafka.clients;

import brave.Span;
import brave.internal.Nullable;
import brave.propagation.CurrentTraceContext;
import brave.propagation.CurrentTraceContext.Scope;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.RecordMetadata;

/**
 * Decorates, then finishes a producer span. Allows tracing to record the duration between batching
 * for send and actual send.
 */
final class TracingCallback {
  static Callback create(@Nullable Callback delegate, Span span, CurrentTraceContext current) {
    if (delegate == null) return new FinishSpan(span);
    return new DelegateAndFinishSpan(delegate, span, current);
  }

  static class FinishSpan implements Callback {
    final Span span;

    FinishSpan(Span span) {
      this.span = span;
    }

    @Override public void onCompletion(RecordMetadata metadata, @Nullable Exception exception) {
      if (exception != null) span.error(exception);
      span.finish();
    }
  }

  static final class DelegateAndFinishSpan extends FinishSpan {
    final Callback delegate;
    final CurrentTraceContext current;

    DelegateAndFinishSpan(Callback delegate, Span span, CurrentTraceContext current) {
      super(span);
      this.delegate = delegate;
      this.current = current;
    }

    @Override public void onCompletion(RecordMetadata metadata, @Nullable Exception exception) {
      try (Scope scope = current.maybeScope(span.context())) {
        delegate.onCompletion(metadata, exception);
      } finally {
        super.onCompletion(metadata, exception);
      }
    }
  }
}
