/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package original.apache.http.impl.execchain;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import original.apache.http.ConnectionReuseStrategy;
import original.apache.http.HttpClientConnection;
import original.apache.http.HttpEntity;
import original.apache.http.HttpException;
import original.apache.http.HttpHost;
import original.apache.http.HttpRequest;
import original.apache.http.HttpResponse;
import original.apache.http.ProtocolException;
import original.apache.http.annotation.Immutable;
import original.apache.http.client.config.RequestConfig;
import original.apache.http.client.methods.CloseableHttpResponse;
import original.apache.http.client.methods.HttpExecutionAware;
import original.apache.http.client.methods.HttpRequestWrapper;
import original.apache.http.client.methods.HttpUriRequest;
import original.apache.http.client.protocol.HttpClientContext;
import original.apache.http.client.protocol.RequestClientConnControl;
import original.apache.http.client.utils.URIUtilsHC4;
import original.apache.http.conn.ConnectionKeepAliveStrategy;
import original.apache.http.conn.ConnectionRequest;
import original.apache.http.conn.HttpClientConnectionManager;
import original.apache.http.conn.routing.HttpRoute;
import original.apache.http.impl.conn.ConnectionShutdownException;
import original.apache.http.protocol.HttpCoreContext;
import original.apache.http.protocol.HttpProcessor;
import original.apache.http.protocol.HttpRequestExecutor;
import original.apache.http.protocol.ImmutableHttpProcessor;
import original.apache.http.protocol.RequestContent;
import original.apache.http.protocol.RequestTargetHost;
import original.apache.http.protocol.RequestUserAgent;
import original.apache.http.util.Args;
import original.apache.http.util.VersionInfo;

/**
 * Request executor that implements the most fundamental aspects of
 * the HTTP specification and the most straight-forward request / response
 * exchange with the target server. This executor does not support
 * execution via proxy and will make no attempts to retry the request
 * in case of a redirect, authentication challenge or I/O error.
 *
 * @since 4.3
 */
@Immutable
public class MinimalClientExec implements ClientExecChain {

    private final HttpRequestExecutor requestExecutor;
    private final HttpClientConnectionManager connManager;
    private final ConnectionReuseStrategy reuseStrategy;
    private final ConnectionKeepAliveStrategy keepAliveStrategy;
    private final HttpProcessor httpProcessor;

    public MinimalClientExec(
            final HttpRequestExecutor requestExecutor,
            final HttpClientConnectionManager connManager,
            final ConnectionReuseStrategy reuseStrategy,
            final ConnectionKeepAliveStrategy keepAliveStrategy) {
        Args.notNull(requestExecutor, "HTTP request executor");
        Args.notNull(connManager, "Client connection manager");
        Args.notNull(reuseStrategy, "Connection reuse strategy");
        Args.notNull(keepAliveStrategy, "Connection keep alive strategy");
        this.httpProcessor = new ImmutableHttpProcessor(
                new RequestContent(),
                new RequestTargetHost(),
                new RequestClientConnControl(),
                new RequestUserAgent(VersionInfo.getUserAgent(
                        "Apache-HttpClient", "org.apache.http.client", getClass())));
        this.requestExecutor    = requestExecutor;
        this.connManager        = connManager;
        this.reuseStrategy      = reuseStrategy;
        this.keepAliveStrategy  = keepAliveStrategy;
    }

    static void rewriteRequestURI(
            final HttpRequestWrapper request,
            final HttpRoute route) throws ProtocolException {
        try {
            URI uri = request.getURI();
            if (uri != null) {
                // Make sure the request URI is relative
                if (uri.isAbsolute()) {
                    uri = URIUtilsHC4.rewriteURI(uri, null, true);
                } else {
                    uri = URIUtilsHC4.rewriteURI(uri);
                }
                request.setURI(uri);
            }
        } catch (final URISyntaxException ex) {
            throw new ProtocolException("Invalid URI: " + request.getRequestLine().getUri(), ex);
        }
    }

    public CloseableHttpResponse execute(
            final HttpRoute route,
            final HttpRequestWrapper request,
            final HttpClientContext context,
            final HttpExecutionAware execAware) throws IOException, HttpException {
        Args.notNull(route, "HTTP route");
        Args.notNull(request, "HTTP request");
        Args.notNull(context, "HTTP context");

        rewriteRequestURI(request, route);

        final ConnectionRequest connRequest = connManager.requestConnection(route, null);
        if (execAware != null) {
            if (execAware.isAborted()) {
                connRequest.cancel();
                throw new RequestAbortedException("Request aborted");
            } else {
                execAware.setCancellable(connRequest);
            }
        }

        final RequestConfig config = context.getRequestConfig();

        final HttpClientConnection managedConn;
        try {
            final int timeout = config.getConnectionRequestTimeout();
            managedConn = connRequest.get(timeout > 0 ? timeout : 0, TimeUnit.MILLISECONDS);
        } catch(final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RequestAbortedException("Request aborted", interrupted);
        } catch(final ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause == null) {
                cause = ex;
            }
            throw new RequestAbortedException("Request execution failed", cause);
        }

        final ConnectionHolder releaseTrigger = new ConnectionHolder(connManager, managedConn);
        try {
            if (execAware != null) {
                if (execAware.isAborted()) {
                    releaseTrigger.close();
                    throw new RequestAbortedException("Request aborted");
                } else {
                    execAware.setCancellable(releaseTrigger);
                }
            }

            if (!managedConn.isOpen()) {
                final int timeout = config.getConnectTimeout();
                this.connManager.connect(
                    managedConn,
                    route,
                    timeout > 0 ? timeout : 0,
                    context);
                this.connManager.routeComplete(managedConn, route, context);
            }
            final int timeout = config.getSocketTimeout();
            if (timeout >= 0) {
                managedConn.setSocketTimeout(timeout);
            }

            HttpHost target = null;
            final HttpRequest original = request.getOriginal();
            if (original instanceof HttpUriRequest) {
                final URI uri = ((HttpUriRequest) original).getURI();
                if (uri.isAbsolute()) {
                    target = new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme());
                }
            }
            if (target == null) {
                target = route.getTargetHost();
            }

            context.setAttribute(HttpCoreContext.HTTP_TARGET_HOST, target);
            context.setAttribute(HttpCoreContext.HTTP_REQUEST, request);
            context.setAttribute(HttpCoreContext.HTTP_CONNECTION, managedConn);
            context.setAttribute(HttpClientContext.HTTP_ROUTE, route);

            httpProcessor.process(request, context);
            final HttpResponse response = requestExecutor.execute(request, managedConn, context);
            httpProcessor.process(response, context);

            // The connection is in or can be brought to a re-usable state.
            if (reuseStrategy.keepAlive(response, context)) {
                // Set the idle duration of this connection
                final long duration = keepAliveStrategy.getKeepAliveDuration(response, context);
                releaseTrigger.setValidFor(duration, TimeUnit.MILLISECONDS);
                releaseTrigger.markReusable();
            } else {
                releaseTrigger.markNonReusable();
            }

            // check for entity, release connection if possible
            final HttpEntity entity = response.getEntity();
            if (entity == null || !entity.isStreaming()) {
                // connection not needed and (assumed to be) in re-usable state
                releaseTrigger.releaseConnection();
                return new HttpResponseProxy(response, null);
            } else {
                return new HttpResponseProxy(response, releaseTrigger);
            }
        } catch (final ConnectionShutdownException ex) {
            final InterruptedIOException ioex = new InterruptedIOException(
                    "Connection has been shut down");
            ioex.initCause(ex);
            throw ioex;
        } catch (final HttpException ex) {
            releaseTrigger.abortConnection();
            throw ex;
        } catch (final IOException ex) {
            releaseTrigger.abortConnection();
            throw ex;
        } catch (final RuntimeException ex) {
            releaseTrigger.abortConnection();
            throw ex;
        }
    }

}
