/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.okhttp3;

import brave.Span;
import brave.propagation.CurrentTraceContext.Scope;
import brave.propagation.SamplingFlags;
import brave.propagation.TraceContext;
import brave.test.http.ITHttpAsyncClient;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dispatcher;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ITTracingCallFactory extends ITHttpAsyncClient<Call.Factory> { // public for src/it
  Dispatcher dispatcher = new Dispatcher();
  ExecutorService executorService = dispatcher.executorService();

  @AfterEach @Override public void close() throws Exception {
    executorService.shutdown();
    executorService.awaitTermination(1, TimeUnit.SECONDS);
    super.close();
  }

  @Override protected Call.Factory newClient(int port) {
    return TracingCallFactory.create(httpTracing, new OkHttpClient.Builder()
      .connectTimeout(1, TimeUnit.SECONDS)
      .readTimeout(1, TimeUnit.SECONDS)
      .retryOnConnectionFailure(false)
      .dispatcher(dispatcher)
      .build()
    );
  }

  @Override protected void closeClient(Call.Factory client) {
    // done in close()
  }

  @Override protected void get(Call.Factory client, String pathIncludingQuery) throws IOException {
    client.newCall(new Request.Builder().url(url(pathIncludingQuery)).build()).execute();
  }

  @Override protected void options(Call.Factory client, String path) throws IOException {
    client.newCall(new Request.Builder().method("OPTIONS", null).url(url(path)).build())
      .execute();
  }

  @Override protected void post(Call.Factory client, String pathIncludingQuery, String body)
    throws IOException {
    client.newCall(new Request.Builder().url(url(pathIncludingQuery))
      // intentionally deprecated method so that the v3.x tests can compile
      .post(RequestBody.create(MediaType.parse("text/plain"), body)).build())
      .execute();
  }

  @Override
  protected void get(Call.Factory client, String path, BiConsumer<Integer, Throwable> callback) {
    client.newCall(new Request.Builder().url(url(path)).build())
      .enqueue(new Callback() {
        @Override public void onFailure(Call call, IOException e) {
          callback.accept(null, e);
        }

        @Override public void onResponse(Call call, Response response) {
          callback.accept(response.code(), null);
        }
      });
  }

  @Test void currentSpanVisibleToUserInterceptors() throws IOException {
    server.enqueue(new MockResponse());
    closeClient(client);

    client = TracingCallFactory.create(httpTracing, new OkHttpClient.Builder()
      .addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
        .addHeader("my-id", currentTraceContext.get().traceIdString())
        .build()))
      .dispatcher(dispatcher)
      .build());

    TraceContext parent = newTraceContext(SamplingFlags.SAMPLED);
    try (Scope scope = currentTraceContext.newScope(parent)) {
      get(client, "/foo");
    }

    RecordedRequest request = takeRequest();
    assertThat(request.getHeader("x-b3-traceId"))
      .isEqualTo(request.getHeader("my-id"));

    testSpanHandler.takeRemoteSpan(Span.Kind.CLIENT);
  }
}
