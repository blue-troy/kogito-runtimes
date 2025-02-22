/*
 * Copyright 2022 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kie.kogito.serverless.workflow.io;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.kie.kogito.serverless.workflow.utils.ExpressionHandlerUtils;
import org.kogito.workitem.rest.auth.ClientOAuth2AuthDecorator;
import org.kogito.workitem.rest.auth.OAuth2AuthDecorator;
import org.kogito.workitem.rest.auth.PasswordOAuth2AuthDecorator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.serverlessworkflow.api.Workflow;
import io.serverlessworkflow.api.auth.AuthDefinition;
import io.serverlessworkflow.api.auth.BasicAuthDefinition;
import io.serverlessworkflow.api.auth.BearerAuthDefinition;
import io.serverlessworkflow.api.auth.OauthDefinition;

class HttpContentLoader extends FallbackContentLoader {

    private static final Logger logger = LoggerFactory.getLogger(HttpContentLoader.class);

    private URI uri;
    private Optional<Workflow> workflow;
    private String authRef;

    public HttpContentLoader(URI uri, Optional<URIContentLoader> fallback, Optional<Workflow> workflow, String authRef) {
        super(fallback);
        this.uri = uri;
        this.workflow = workflow;
        this.authRef = authRef;
    }

    private static class NoCopyByteArrayInputStream extends ByteArrayInputStream {
        public NoCopyByteArrayInputStream(byte[] buf) {
            super(buf);
        }

        @Override
        public byte[] readAllBytes() {
            // This is a minor optimization that avoids copying the whole array if no byte has been read
            if (pos == 0) {
                pos = count;
                return buf;
            } else {
                return super.readAllBytes();
            }
        }
    }

    @Override
    protected InputStream internalInputStream() {
        return new NoCopyByteArrayInputStream(ResourceCacheFactory.getCache().get(uri, this::loadURI));
    }

    private byte[] loadURI(URI u) {
        try {
            HttpURLConnection conn = (HttpURLConnection) u.toURL().openConnection();
            // some http servers required specific accept header (*/* is specified for those we do not care about accept) 
            conn.setRequestProperty("Accept", "application/json,application/yaml,application/yml,application/text,text/*,*/*");
            workflow.map(Workflow::getAuth)
                    .filter(auth -> Objects.equals(auth.getName(), authRef))
                    .ifPresent(auth -> addAuth(conn, auth));
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK) {
                try (InputStream is = conn.getInputStream()) {
                    return is.readAllBytes();
                }
            } else {
                try (InputStream is = conn.getErrorStream()) {
                    throw new IllegalArgumentException(String.format(
                            "Failed to fetch remote file: %s. Status code is %d and response: %n %s", u.toString(), code, new String(is.readAllBytes())));
                }
            }
        } catch (IOException io) {
            throw new IllegalStateException(io);
        }
    }

    private void addAuth(HttpURLConnection conn, AuthDefinition auth) {
        switch (auth.getScheme()) {
            case BASIC:
                basicAuth(conn, auth.getBasicauth());
                break;
            case BEARER:
                bearerAuth(conn, auth.getBearerauth());
                break;
            case OAUTH_2:
                oauth2Auth(conn, auth.getOauth());
                break;
        }
    }

    private void oauth2Auth(HttpURLConnection conn, OauthDefinition oauth) {
        OAuth2AuthDecorator<?> decorator;
        Map<String, Object> parameters = new HashMap<>();
        // TODO stop using metadata when spec is updated
        String tokenUrl = oauth.getMetadata().get("tokenURL");
        String refreshUrl = oauth.getMetadata().get("refreshURL");
        if (tokenUrl == null) {
            logger.warn("Need to add property tokenURL in metadata for oauth auth");
            return;
        }
        switch (oauth.getGrantType()) {
            case CLIENT_CREDENTIALS:
                decorator = new ClientOAuth2AuthDecorator(tokenUrl, refreshUrl);
                parameters.put(ClientOAuth2AuthDecorator.CLIENT_ID, eval(oauth.getClientId()));
                parameters.put(ClientOAuth2AuthDecorator.CLIENT_SECRET, eval(oauth.getClientSecret()));
                break;
            case PASSWORD:
                decorator = new PasswordOAuth2AuthDecorator(tokenUrl, refreshUrl);
                parameters.put(PasswordOAuth2AuthDecorator.USER, eval(oauth.getClientId()));
                parameters.put(PasswordOAuth2AuthDecorator.PASSWORD, eval(oauth.getClientSecret()));
                break;
            default:
                logger.warn("Unsupported grant type {}", oauth.getGrantType());
                return;
        }
        bearerAuth(conn, decorator.getToken(parameters));
    }

    private void bearerAuth(HttpURLConnection conn, BearerAuthDefinition bearerAuth) {
        bearerAuth(conn, eval(bearerAuth.getToken()));
    }

    private static void bearerAuth(HttpURLConnection conn, String token) {
        conn.setRequestProperty("Authorization", "Bearer " + token);
    }

    private String eval(String expr) {
        return ExpressionHandlerUtils.replaceExpr(workflow.get(), expr);
    }

    private void basicAuth(HttpURLConnection conn, BasicAuthDefinition basicAuth) {
        conn.setRequestProperty("Authorization", "Basic " + encode(eval(basicAuth.getUsername()) + ":" + eval(basicAuth.getPassword())));
    }

    private String encode(String str) {
        return Base64.getEncoder().encodeToString(str.getBytes());
    }
}
