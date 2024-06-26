/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.internal.baggage;

import brave.baggage.BaggageField;
import brave.baggage.BaggageField.ValueUpdater;
import brave.internal.Nullable;
import brave.propagation.Propagation.Getter;
import brave.propagation.Propagation.Setter;
import brave.propagation.TraceContext;
import brave.propagation.TraceContext.Extractor;
import brave.propagation.TraceContext.Injector;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public interface BaggageCodec {
  /**
   * Use this when configuration results in no codec needed.
   */
  BaggageCodec NOOP = new BaggageCodec() {
    @Override public List<String> extractKeyNames() {
      return Collections.emptyList();
    }

    @Override public List<String> injectKeyNames() {
      return Collections.emptyList();
    }

    @Override public boolean decode(ValueUpdater valueUpdater, String value) {
      return false;
    }

    @Override public String encode(Map<String, String> values, TraceContext context) {
      return null;
    }

    @Override public String toString() {
      return "NoopBaggageCodec";
    }
  };

  /**
   * Ordered list of key names used during {@link Extractor#extract(Object)} with {@link
   * Getter#get(Object, Object)}.
   *
   * <p>{@link Getter#get(Object, Object)} will be called against these in order until a
   * non-{@code null} value result or there are no more keys.
   *
   * @since 5.12
   */
  List<String> extractKeyNames();

  /**
   * Ordered list of key names used during {@link Injector#inject(TraceContext, Object)} with {@link
   * Setter#put(Object, Object, String)}.
   *
   * @since 5.12
   */
  List<String> injectKeyNames();

  /**
   * Called on the first non-{@code null} value from an {@link #extractKeyNames() extract key}.
   * Decodes any field state from an extracted value or returns {@code null} if there were none.
   *
   * @param valueUpdater used to assign {@link BaggageField} values.
   * @param value a non-{@code null} result of {@link Getter#get(Object, Object)}
   * @see #extractKeyNames()
   */
  boolean decode(ValueUpdater valueUpdater, String value);

  /**
   * Encodes any state to a request value used by {@link Setter#put(Object, Object, String)}. When
   * not {@code null}, the value will be used for all {@link #injectKeyNames()}.
   *
   * <p>The {@code values} parameter is not thread safe. Only use this reference inside the encode
   * method and do not store it as a field.
   *
   * @param values a mapping of all remote {@link BaggageField#name()} to non-{@code null} values
   * @return an input to {@link Setter#put(Object, Object, String)}
   * @see #injectKeyNames()
   */
  @Nullable String encode(Map<String, String> values, TraceContext context);
}
