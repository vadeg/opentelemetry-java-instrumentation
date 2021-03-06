/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.couchbase.v2_6;

import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.couchbase.client.core.message.CouchbaseRequest;
import com.google.auto.service.AutoService;
import io.opentelemetry.OpenTelemetry;
import io.opentelemetry.javaagent.instrumentation.api.ContextStore;
import io.opentelemetry.javaagent.instrumentation.api.InstrumentationContext;
import io.opentelemetry.javaagent.tooling.Instrumenter;
import io.opentelemetry.trace.Span;
import io.opentelemetry.trace.Tracer;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class CouchbaseCoreInstrumentation extends Instrumenter.Default {

  public CouchbaseCoreInstrumentation() {
    super("couchbase");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("com.couchbase.client.core.CouchbaseCore");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("com.couchbase.client.core.message.CouchbaseRequest", Span.class.getName());
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {getClass().getName() + "$CouchbaseCoreAdvice"};
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isMethod()
            .and(isPublic())
            .and(takesArgument(0, named("com.couchbase.client.core.message.CouchbaseRequest")))
            .and(named("send")),
        CouchbaseCoreInstrumentation.class.getName() + "$CouchbaseCoreAdvice");
  }

  public static class CouchbaseCoreAdvice {
    public static final Tracer TRACER =
        OpenTelemetry.getTracer("io.opentelemetry.auto.couchbase-2.6");

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void addOperationIdToSpan(@Advice.Argument(0) CouchbaseRequest request) {

      Span parentSpan = TRACER.getCurrentSpan();
      if (parentSpan != null) {
        // The scope from the initial rxJava subscribe is not available to the networking layer
        // To transfer the span, the span is added to the context store

        ContextStore<CouchbaseRequest, Span> contextStore =
            InstrumentationContext.get(CouchbaseRequest.class, Span.class);

        Span span = contextStore.get(request);

        if (span == null) {
          span = parentSpan;
          contextStore.put(request, span);

          span.setAttribute("couchbase.operation_id", request.operationId());
        }
      }
    }
  }
}
