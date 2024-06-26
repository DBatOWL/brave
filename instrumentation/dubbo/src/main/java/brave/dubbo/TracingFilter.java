/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.dubbo;

import brave.Span;
import brave.Span.Kind;
import brave.propagation.CurrentTraceContext;
import brave.propagation.CurrentTraceContext.Scope;
import brave.propagation.TraceContext;
import brave.rpc.RpcClientHandler;
import brave.rpc.RpcServerHandler;
import brave.rpc.RpcTracing;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;

import static brave.internal.Throwables.propagateIfFatal;

@Activate(group = {CommonConstants.PROVIDER, CommonConstants.CONSUMER}, value = "tracing")
// http://dubbo.apache.org/en-us/docs/dev/impls/filter.html
// public constructor permitted to allow dubbo to instantiate this
public final class TracingFilter implements Filter {

  CurrentTraceContext currentTraceContext;
  RpcClientHandler clientHandler;
  RpcServerHandler serverHandler;
  volatile boolean isInit = false;

  /**
   * {@link ExtensionLoader} supplies the tracing implementation which must be named "rpcTracing".
   * For example, if using the {@link org.apache.dubbo.config.spring.extension.SpringExtensionInjector}, only a bean named "rpcTracing" will
   * be injected.
   *
   * <h3>Custom parsing</h3>
   * Custom parsers, such as {@link RpcTracing#clientRequestParser()}, can use Dubbo-specific types
   * {@link DubboRequest} and {@link DubboResponse} to get access such as the Java invocation or
   * result.
   */
  public void setRpcTracing(RpcTracing rpcTracing) {
    if (rpcTracing == null) throw new NullPointerException("rpcTracing == null");
    // we don't guard on init because we intentionally want to overwrite any call to setTracing
    currentTraceContext = rpcTracing.tracing().currentTraceContext();
    clientHandler = RpcClientHandler.create(rpcTracing);
    serverHandler = RpcServerHandler.create(rpcTracing);
    isInit = true;
  }

  @Override
  public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
    if (!isInit) return invoker.invoke(invocation);
    TraceContext invocationContext = currentTraceContext.get();

    RpcContext rpcContext = RpcContext.getContext();
    Kind kind = rpcContext.isProviderSide() ? Kind.SERVER : Kind.CLIENT;
    Span span;
    DubboRequest request;
    if (kind.equals(Kind.CLIENT)) {
      /*

       The purpose of the attachments is to store span information that is sent to remote services, which was previously saved in the invocationContext obtained by calling currentTraceContext.get().
       In Dubbo 2.7.x, calling RpcContext.getContext().getAttachments() returns an editable attachments map that will be added to the invocation in later processing stages.
       However, in Dubbo 3.x, the returned attachments map is a copy and will not be added to the invocation.
       Therefore, we should use invocation.getAttachments() instead to retrieve the map that will be added to the
       invocation.
       */
      Map<String, String> attachments = invocation.getAttachments();
      DubboClientRequest clientRequest = new DubboClientRequest(invoker, invocation, attachments);
      request = clientRequest;
      span = clientHandler.handleSendWithParent(clientRequest, invocationContext);
    } else {
      DubboServerRequest serverRequest = new DubboServerRequest(invoker, invocation);
      request = serverRequest;
      span = serverHandler.handleReceive(serverRequest);
    }

    boolean isSynchronous = true;
    Scope scope = currentTraceContext.newScope(span.context());
    Result result = null;
    Throwable error = null;
    try {
      result = invoker.invoke(invocation);
      error = result.getException();
      CompletableFuture<Object> future = rpcContext.getCompletableFuture();
      if (future != null) {
        isSynchronous = false;
        // NOTE: We don't currently instrument CompletableFuture, so callbacks will not see the
        // invocation context unless they use an executor instrumented by CurrentTraceContext
        // If we later instrument this, take care to use the correct context depending on RPC kind!
        future.whenComplete(FinishSpan.create(this, request, result, span));
      }
      return result;
    } catch (Throwable e) {
      propagateIfFatal(e);
      error = e;
      throw e;
    } finally {
      if (isSynchronous) FinishSpan.finish(this, request, result, error, span);
      scope.close();
    }
  }
}
