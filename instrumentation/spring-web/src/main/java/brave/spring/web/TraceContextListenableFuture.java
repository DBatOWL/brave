/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.spring.web;

import brave.propagation.CurrentTraceContext;
import brave.propagation.CurrentTraceContext.Scope;
import brave.propagation.TraceContext;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.springframework.util.concurrent.FailureCallback;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.ListenableFutureCallback;
import org.springframework.util.concurrent.SuccessCallback;

/**
 * Ensures callbacks run in the invocation trace context.
 *
 * <p>Note: {@link #completable()} is not instrumented to propagate the invocation trace context.
 */
final class TraceContextListenableFuture<T> implements ListenableFuture<T> {
  final ListenableFuture<T> delegate;
  final CurrentTraceContext currentTraceContext;
  final TraceContext invocationContext;

  TraceContextListenableFuture(ListenableFuture<T> delegate,
    CurrentTraceContext currentTraceContext, TraceContext invocationContext) {
    this.delegate = delegate;
    this.currentTraceContext = currentTraceContext;
    this.invocationContext = invocationContext;
  }

  @Override public void addCallback(ListenableFutureCallback<? super T> callback) {
    delegate.addCallback(callback != null
      ? new TraceContextListenableFutureCallback(callback, this)
      : null
    );
  }

  // Do not use @Override annotation to avoid compatibility issue version < 4.1
  public void addCallback(SuccessCallback<? super T> successCallback,
    FailureCallback failureCallback) {
    delegate.addCallback(
      successCallback != null
        ? new TraceContextSuccessCallback(successCallback, this)
        : null,
      failureCallback != null
        ? new TraceContextFailureCallback(failureCallback, this)
        : null
    );
  }

  // Do not use @Override annotation to avoid compatibility issue version < 5.0
  // Only called when in JRE 1.8+
  public CompletableFuture<T> completable() {
    return delegate.completable(); // NOTE: trace context is not propagated
  }

  // Methods from java.util.concurrent.Future
  @Override public boolean cancel(boolean mayInterruptIfRunning) {
    return delegate.cancel(mayInterruptIfRunning);
  }

  @Override public boolean isCancelled() {
    return delegate.isCancelled();
  }

  @Override public boolean isDone() {
    return delegate.isDone();
  }

  @Override public T get() throws InterruptedException, ExecutionException {
    return delegate.get();
  }

  @Override
  public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException {
    return delegate.get();
  }

  static final class TraceContextListenableFutureCallback<T>
    implements ListenableFutureCallback<T> {
    final ListenableFutureCallback<T> delegate;
    final CurrentTraceContext currentTraceContext;
    final TraceContext invocationContext;

    TraceContextListenableFutureCallback(ListenableFutureCallback<T> delegate,
      TraceContextListenableFuture<?> future) {
      this.delegate = delegate;
      this.currentTraceContext = future.currentTraceContext;
      this.invocationContext = future.invocationContext;
    }

    @Override public void onSuccess(T result) {
      Scope scope = currentTraceContext.maybeScope(invocationContext);
      try {
        delegate.onSuccess(result);
      } finally {
        scope.close();
      }
    }

    @Override public void onFailure(Throwable ex) {
      Scope scope = currentTraceContext.maybeScope(invocationContext);
      try {
        delegate.onFailure(ex);
      } finally {
        scope.close();
      }
    }

    @Override public String toString() {
      return delegate.toString();
    }
  }

  static final class TraceContextSuccessCallback<T> implements SuccessCallback<T> {
    final SuccessCallback<T> delegate;
    final CurrentTraceContext currentTraceContext;
    final TraceContext invocationContext;

    TraceContextSuccessCallback(SuccessCallback<T> delegate,
      TraceContextListenableFuture<?> future) {
      this.delegate = delegate;
      this.currentTraceContext = future.currentTraceContext;
      this.invocationContext = future.invocationContext;
    }

    @Override public void onSuccess(T result) {
      Scope scope = currentTraceContext.maybeScope(invocationContext);
      try {
        delegate.onSuccess(result);
      } finally {
        scope.close();
      }
    }

    @Override public String toString() {
      return delegate.toString();
    }
  }

  static final class TraceContextFailureCallback implements FailureCallback {
    final FailureCallback delegate;
    final CurrentTraceContext currentTraceContext;
    final TraceContext invocationContext;

    TraceContextFailureCallback(FailureCallback delegate,
      TraceContextListenableFuture<?> future) {
      this.delegate = delegate;
      this.currentTraceContext = future.currentTraceContext;
      this.invocationContext = future.invocationContext;
    }

    @Override public void onFailure(Throwable ex) {
      Scope scope = currentTraceContext.maybeScope(invocationContext);
      try {
        delegate.onFailure(ex);
      } finally {
        scope.close();
      }
    }

    @Override public String toString() {
      return delegate.toString();
    }
  }
}
