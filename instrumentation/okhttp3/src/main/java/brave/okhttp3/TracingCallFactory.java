/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.okhttp3;

import brave.Tracer;
import brave.Tracing;
import brave.http.HttpTracing;
import brave.propagation.CurrentTraceContext;
import brave.propagation.TraceContext;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * This internally adds an interceptor which ensures whatever current span exists is available via
 * {@link Tracer#currentSpanCustomizer()} and {@link Tracer#currentSpan()}
 */
// NOTE: this is not an interceptor because the current span can get lost when there's a backlog.
// This will be completely different after https://github.com/square/okhttp/issues/270
public final class TracingCallFactory implements Call.Factory {
  /**
   * To save overhead, we use a null sentinel when there's no parent. This helps avoid looking at
   * the current trace context when there was no span in scope at invocation time.
   */
  static final TraceContext NULL_SENTINEL = TraceContext.newBuilder()
    .traceId(1L).spanId(1L).build();

  public static Call.Factory create(Tracing tracing, OkHttpClient ok) {
    return create(HttpTracing.create(tracing), ok);
  }

  public static Call.Factory create(HttpTracing httpTracing, OkHttpClient ok) {
    return new TracingCallFactory(httpTracing, ok);
  }

  final CurrentTraceContext currentTraceContext;
  final OkHttpClient ok;

  TracingCallFactory(HttpTracing httpTracing, OkHttpClient ok) {
    if (httpTracing == null) throw new NullPointerException("HttpTracing == null");
    if (ok == null) throw new NullPointerException("OkHttpClient == null");
    this.currentTraceContext = httpTracing.tracing().currentTraceContext();
    OkHttpClient.Builder builder = ok.newBuilder();
    builder.networkInterceptors().add(0, TracingInterceptor.create(httpTracing));
    this.ok = builder.build();
  }

  @Override public Call newCall(Request request) {
    TraceContext invocationContext = currentTraceContext.get();
    Call call = ok.newCall(request.newBuilder()
      .tag(TraceContext.class, invocationContext != null ? invocationContext : NULL_SENTINEL)
      .build());
    return new TraceContextCall(call, currentTraceContext, invocationContext);
  }
}
