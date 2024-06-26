/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.internal.handler;

import brave.handler.MutableSpan;
import brave.handler.SpanHandler;
import brave.handler.SpanHandler.Cause;
import brave.propagation.TraceContext;
import java.util.concurrent.atomic.AtomicBoolean;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.failBecauseExceptionWasNotThrown;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NoopAwareSpanHandlerTest {
  TraceContext context = TraceContext.newBuilder().traceId(1).spanId(2).sampled(true).build();
  MutableSpan span = new MutableSpan();
  AtomicBoolean noop = new AtomicBoolean(false);

  @Mock SpanHandler one;
  @Mock SpanHandler two;
  @Mock SpanHandler three;

  @Test void create_emptyIsNoop() {
    assertThat(NoopAwareSpanHandler.create(new SpanHandler[0], noop))
        .isEqualTo(SpanHandler.NOOP);
  }

  @Test void create_single() {
    NoopAwareSpanHandler handler =
        (NoopAwareSpanHandler) NoopAwareSpanHandler.create(new SpanHandler[] {one}, noop);

    assertThat(handler.delegate).isSameAs(one);

    handler.end(context, span, Cause.FINISHED);
    verify(one).end(context, span, Cause.FINISHED);
  }

  @Test void honorsNoop() {
    SpanHandler handler = NoopAwareSpanHandler.create(new SpanHandler[] {one}, noop);

    noop.set(true);

    handler.end(context, span, Cause.FINISHED);
    verify(one, never()).end(context, span, Cause.FINISHED);
  }

  @Test void create_multiple() {
    SpanHandler[] handlers = new SpanHandler[2];
    handlers[0] = one;
    handlers[1] = two;
    SpanHandler handler = NoopAwareSpanHandler.create(handlers, noop);

    assertThat(handler).extracting("delegate.handlers")
        .asInstanceOf(InstanceOfAssertFactories.array(SpanHandler[].class))
        .containsExactly(one, two);
  }

  @Test void multiple_callInSequence() {
    SpanHandler[] handlers = new SpanHandler[2];
    handlers[0] = one;
    handlers[1] = two;
    SpanHandler handler = NoopAwareSpanHandler.create(handlers, noop);
    when(one.begin(eq(context), eq(span), isNull())).thenReturn(true);
    when(one.end(eq(context), eq(span), eq(Cause.FINISHED))).thenReturn(true);

    handler.begin(context, span, null);
    handler.end(context, span, Cause.FINISHED);

    verify(one).begin(context, span, null);
    verify(two).begin(context, span, null);
    verify(two).end(context, span, Cause.FINISHED);
    verify(one).end(context, span, Cause.FINISHED);
  }

  @Test void multiple_shortCircuitWhenFirstReturnsFalse() {
    SpanHandler[] handlers = new SpanHandler[2];
    handlers[0] = one;
    handlers[1] = two;
    SpanHandler handler = NoopAwareSpanHandler.create(handlers, noop);
    handler.end(context, span, Cause.FINISHED);

    verify(one).end(context, span, Cause.FINISHED);
    verify(two, never()).end(context, span, Cause.FINISHED);
  }

  @Test void multiple_abandoned() {
    SpanHandler[] handlers = new SpanHandler[3];
    handlers[0] = one;
    handlers[1] = two;
    handlers[2] = three;

    when(two.handlesAbandoned()).thenReturn(true);

    SpanHandler handler = NoopAwareSpanHandler.create(handlers, noop);
    assertThat(handler.handlesAbandoned()).isTrue();
    handler.end(context, span, Cause.ABANDONED);

    verify(one, never()).end(context, span, Cause.ABANDONED);
    verify(two).end(context, span, Cause.ABANDONED);
    verify(three, never()).end(context, span, Cause.FINISHED);
  }

  @Test void doesntCrashOnNonFatalThrowable() {
    Throwable[] toThrow = new Throwable[1];
    SpanHandler handler =
        NoopAwareSpanHandler.create(new SpanHandler[] {new SpanHandler() {
          @Override public boolean end(TraceContext context, MutableSpan span, Cause cause) {
            doThrowUnsafely(toThrow[0]);
            return true;
          }
        }}, noop);

    toThrow[0] = new RuntimeException();
    assertThat(handler.end(context, span, Cause.FINISHED)).isTrue();

    toThrow[0] = new Exception();
    assertThat(handler.end(context, span, Cause.FINISHED)).isTrue();

    toThrow[0] = new Error();
    assertThat(handler.end(context, span, Cause.FINISHED)).isTrue();

    toThrow[0] = new StackOverflowError(); // fatal
    try { // assertThatThrownBy doesn't work with StackOverflowError
      handler.end(context, span, Cause.FINISHED);
      failBecauseExceptionWasNotThrown(StackOverflowError.class);
    } catch (StackOverflowError e) {
    }
  }

  // Trick from Armeria: This black magic causes the Java compiler to believe E is unchecked.
  static <E extends Throwable> void doThrowUnsafely(Throwable cause) throws E {
    throw (E) cause;
  }
}
