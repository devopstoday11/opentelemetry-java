/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.opentracingshim.testbed.nestedcallbacks;

import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static io.opentelemetry.opentracingshim.testbed.TestUtils.finishedSpansSize;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.ReadableAttributes;
import io.opentelemetry.exporters.inmemory.InMemoryTracing;
import io.opentelemetry.opentracingshim.OpenTracingShim;
import io.opentelemetry.sdk.trace.TracerSdkProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

@SuppressWarnings("FutureReturnValueIgnored")
public final class NestedCallbacksTest {
  private final TracerSdkProvider sdk = TracerSdkProvider.builder().build();
  private final OpenTelemetry openTelemetry =
      OpenTelemetry.get().toBuilder().setTracerProvider(sdk).build();
  private final InMemoryTracing inMemoryTracing =
      InMemoryTracing.builder().setTracerSdkManagement(sdk).build();
  private final Tracer tracer = OpenTracingShim.createTracerShim(openTelemetry);
  private final ExecutorService executor = Executors.newCachedThreadPool();

  @Test
  void test() {

    Span span = tracer.buildSpan("one").start();
    submitCallbacks(span);

    await()
        .atMost(15, TimeUnit.SECONDS)
        .until(finishedSpansSize(inMemoryTracing.getSpanExporter()), equalTo(1));

    List<SpanData> spans = inMemoryTracing.getSpanExporter().getFinishedSpanItems();
    assertEquals(1, spans.size());
    assertEquals("one", spans.get(0).getName());

    ReadableAttributes attrs = spans.get(0).getAttributes();
    assertEquals(3, attrs.size());
    for (int i = 1; i <= 3; i++) {
      assertEquals(Integer.toString(i), spans.get(0).getAttributes().get(stringKey("key" + i)));
    }

    assertNull(tracer.scopeManager().activeSpan());
  }

  private void submitCallbacks(final Span span) {

    executor.submit(
        () -> {
          try (Scope scope = tracer.scopeManager().activate(span)) {
            span.setTag("key1", "1");

            executor.submit(
                () -> {
                  try (Scope scope12 = tracer.scopeManager().activate(span)) {
                    span.setTag("key2", "2");

                    executor.submit(
                        () -> {
                          try (Scope scope1 = tracer.scopeManager().activate(span)) {
                            span.setTag("key3", "3");
                          } finally {
                            span.finish();
                          }
                        });
                  }
                });
          }
        });
  }
}
