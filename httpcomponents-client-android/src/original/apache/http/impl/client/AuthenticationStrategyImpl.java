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

package original.apache.http.impl.client;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;

import org.kman.apache.http.logging.Logger;
import original.apache.http.FormattedHeader;
import original.apache.http.Header;
import original.apache.http.HttpHost;
import original.apache.http.HttpResponse;
import original.apache.http.annotation.Immutable;
import original.apache.http.auth.AuthOption;
import original.apache.http.auth.AuthScheme;
import original.apache.http.auth.AuthSchemeProvider;
import original.apache.http.auth.AuthScope;
import original.apache.http.auth.Credentials;
import original.apache.http.auth.MalformedChallengeException;
import original.apache.http.client.AuthCache;
import original.apache.http.client.AuthenticationStrategy;
import original.apache.http.client.CredentialsProvider;
import original.apache.http.client.config.AuthSchemes;
import original.apache.http.client.config.RequestConfig;
import original.apache.http.client.protocol.HttpClientContext;
import original.apache.http.config.Lookup;
import original.apache.http.protocol.HTTP;
import original.apache.http.protocol.HttpContext;
import original.apache.http.util.Args;
import original.apache.http.util.CharArrayBuffer;

@Immutable
abstract class AuthenticationStrategyImpl implements AuthenticationStrategy {

    private final static String TAG = "HttpClient";

    private static final List<String> DEFAULT_SCHEME_PRIORITY =
        Collections.unmodifiableList(Arrays.asList(AuthSchemes.SPNEGO,
                AuthSchemes.KERBEROS,
                AuthSchemes.NTLM,
                AuthSchemes.DIGEST,
                AuthSchemes.BASIC));

    private final int challengeCode;
    private final String headerName;

    AuthenticationStrategyImpl(final int challengeCode, final String headerName) {
        super();
        this.challengeCode = challengeCode;
        this.headerName = headerName;
    }

    public boolean isAuthenticationRequested(
            final HttpHost authhost,
            final HttpResponse response,
            final HttpContext context) {
        Args.notNull(response, "HTTP response");
        final int status = response.getStatusLine().getStatusCode();
        return status == this.challengeCode;
    }

    public Map<String, Header> getChallenges(
            final HttpHost authhost,
            final HttpResponse response,
            final HttpContext context) throws MalformedChallengeException {
        Args.notNull(response, "HTTP response");
        final Header[] headers = response.getHeaders(this.headerName);
        final Map<String, Header> map = new HashMap<String, Header>(headers.length);
        for (final Header header : headers) {
            final CharArrayBuffer buffer;
            int pos;
            if (header instanceof FormattedHeader) {
                buffer = ((FormattedHeader) header).getBuffer();
                pos = ((FormattedHeader) header).getValuePos();
            } else {
                final String s = header.getValue();
                if (s == null) {
                    throw new MalformedChallengeException("Header value is null");
                }
                buffer = new CharArrayBuffer(s.length());
                buffer.append(s);
                pos = 0;
            }
            while (pos < buffer.length() && HTTP.isWhitespace(buffer.charAt(pos))) {
                pos++;
            }
            final int beginIndex = pos;
            while (pos < buffer.length() && !HTTP.isWhitespace(buffer.charAt(pos))) {
                pos++;
            }
            final int endIndex = pos;
            final String s = buffer.substring(beginIndex, endIndex);
            map.put(s.toLowerCase(Locale.ENGLISH), header);
        }
        return map;
    }

    abstract Collection<String> getPreferredAuthSchemes(RequestConfig config);

    public Queue<AuthOption> select(
            final Map<String, Header> challenges,
            final HttpHost authhost,
            final HttpResponse response,
            final HttpContext context) throws MalformedChallengeException {
        Args.notNull(challenges, "Map of auth challenges");
        Args.notNull(authhost, "Host");
        Args.notNull(response, "HTTP response");
        Args.notNull(context, "HTTP context");
        final HttpClientContext clientContext = HttpClientContext.adapt(context);

        final Queue<AuthOption> options = new LinkedList<AuthOption>();
        final Lookup<AuthSchemeProvider> registry = clientContext.getAuthSchemeRegistry();
        if (registry == null) {
            if (Logger.isLoggable(TAG, Logger.DEBUG)) {
                Logger.d(TAG, "Auth scheme registry not set in the context");
            }
            return options;
        }
        final CredentialsProvider credsProvider = clientContext.getCredentialsProvider();
        if (credsProvider == null) {
            if (Logger.isLoggable(TAG, Logger.DEBUG)) {
                Logger.d(TAG, "Credentials provider not set in the context");
            }
            return options;
        }
        final RequestConfig config = clientContext.getRequestConfig();
        Collection<String> authPrefs = getPreferredAuthSchemes(config);
        if (authPrefs == null) {
            authPrefs = DEFAULT_SCHEME_PRIORITY;
        }
        if (Logger.isLoggable(TAG, Logger.DEBUG)) {
            Logger.d(TAG, "Authentication schemes in the order of preference: " + authPrefs);
        }

        for (final String id: authPrefs) {
            final Header challenge = challenges.get(id.toLowerCase(Locale.ENGLISH));
            if (challenge != null) {
                final AuthSchemeProvider authSchemeProvider = registry.lookup(id);
                if (authSchemeProvider == null) {
                    if (Logger.isLoggable(TAG, Logger.WARN)) {
                        Logger.w(TAG, "Authentication scheme " + id + " not supported");
                        // Try again
                    }
                    continue;
                }
                final AuthScheme authScheme = authSchemeProvider.create(context);
                authScheme.processChallenge(challenge);

                final AuthScope authScope = new AuthScope(
                        authhost.getHostName(),
                        authhost.getPort(),
                        authScheme.getRealm(),
                        authScheme.getSchemeName());

                final Credentials credentials = credsProvider.getCredentials(authScope);
                if (credentials != null) {
                    options.add(new AuthOption(authScheme, credentials));
                }
            } else {
                if (Logger.isLoggable(TAG, Logger.DEBUG)) {
                    Logger.d(TAG, "Challenge for " + id + " authentication scheme not available");
                    // Try again
                }
            }
        }
        return options;
    }

    public void authSucceeded(
            final HttpHost authhost, final AuthScheme authScheme, final HttpContext context) {
        Args.notNull(authhost, "Host");
        Args.notNull(authScheme, "Auth scheme");
        Args.notNull(context, "HTTP context");

        final HttpClientContext clientContext = HttpClientContext.adapt(context);

        if (isCachable(authScheme)) {
            AuthCache authCache = clientContext.getAuthCache();
            if (authCache == null) {
                authCache = new BasicAuthCache();
                clientContext.setAuthCache(authCache);
            }
            if (Logger.isLoggable(TAG, Logger.DEBUG)) {
                Logger.d(TAG, "Caching '" + authScheme.getSchemeName() +
                        "' auth scheme for " + authhost);
            }
            authCache.put(authhost, authScheme);
        }
    }

    protected boolean isCachable(final AuthScheme authScheme) {
        if (authScheme == null || !authScheme.isComplete()) {
            return false;
        }
        final String schemeName = authScheme.getSchemeName();
        return schemeName.equalsIgnoreCase(AuthSchemes.BASIC) ||
                schemeName.equalsIgnoreCase(AuthSchemes.DIGEST);
    }

    public void authFailed(
            final HttpHost authhost, final AuthScheme authScheme, final HttpContext context) {
        Args.notNull(authhost, "Host");
        Args.notNull(context, "HTTP context");

        final HttpClientContext clientContext = HttpClientContext.adapt(context);

        final AuthCache authCache = clientContext.getAuthCache();
        if (authCache != null) {
            if (Logger.isLoggable(TAG, Logger.DEBUG)) {
                Logger.d(TAG, "Clearing cached auth scheme for " + authhost);
            }
            authCache.remove(authhost);
        }
    }

}
