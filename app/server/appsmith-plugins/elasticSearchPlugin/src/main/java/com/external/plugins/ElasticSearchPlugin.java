package com.external.plugins;

import com.appsmith.external.models.ActionConfiguration;
import com.appsmith.external.models.ActionExecutionResult;
import com.appsmith.external.models.AuthenticationDTO;
import com.appsmith.external.models.DatasourceConfiguration;
import com.appsmith.external.models.DatasourceTestResult;
import com.appsmith.external.models.Endpoint;
import com.appsmith.external.pluginExceptions.AppsmithPluginError;
import com.appsmith.external.pluginExceptions.AppsmithPluginException;
import com.appsmith.external.plugins.BasePlugin;
import com.appsmith.external.plugins.PluginExecutor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.StatusLine;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.message.BasicHeader;
import org.apache.http.nio.entity.NStringEntity;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.pf4j.Extension;
import org.pf4j.PluginWrapper;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ElasticSearchPlugin extends BasePlugin {

    public ElasticSearchPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Slf4j
    @Extension
    public static class ElasticSearchPluginExecutor implements PluginExecutor<RestClient> {

        @Override
        public Mono<ActionExecutionResult> execute(RestClient client,
                                                   DatasourceConfiguration datasourceConfiguration,
                                                   ActionConfiguration actionConfiguration) {
            final ActionExecutionResult result = new ActionExecutionResult();

            String body = actionConfiguration.getBody();

            final String path = actionConfiguration.getPath();
            final Request request = new Request(actionConfiguration.getHttpMethod().toString(), path);
            ContentType contentType = ContentType.APPLICATION_JSON;

            if (isBulkQuery(path)) {
                contentType = ContentType.create("application/x-ndjson");

                // If body is a JSON Array, convert it to an ND-JSON string.
                if (body != null && body.trim().startsWith("[")) {
                    final StringBuilder ndJsonBuilder = new StringBuilder();
                    try {
                        List<Object> commands = objectMapper.readValue(body, ArrayList.class);
                        for (Object object : commands) {
                            ndJsonBuilder.append(objectMapper.writeValueAsString(object)).append("\n");
                        }
                    } catch (IOException e) {
                        final String message = "Error converting array to ND-JSON: " + e.getMessage();
                        log.warn(message, e);
                        return Mono.error(new AppsmithPluginException(AppsmithPluginError.PLUGIN_ERROR, message));
                    }
                    body = ndJsonBuilder.toString();
                }
            }

            if (body != null) {
                request.setEntity(new NStringEntity(body, contentType));
            }

            try {
                final String responseBody = new String(
                        client.performRequest(request).getEntity().getContent().readAllBytes());
                result.setBody(objectMapper.readValue(responseBody, HashMap.class));
            } catch (IOException e) {
                final String message = "Error performing request: " + e.getMessage();
                log.warn(message, e);
                return Mono.error(new AppsmithPluginException(AppsmithPluginError.PLUGIN_ERROR, message));
            }

            result.setIsExecutionSuccess(true);
            return Mono.just(result);
        }

        private static boolean isBulkQuery(String path) {
            return path.split("\\?", 1)[0].matches(".*\\b_bulk$");
        }

        @Override
        public Mono<RestClient> datasourceCreate(DatasourceConfiguration datasourceConfiguration) {
            final List<HttpHost> hosts = new ArrayList<>();

            for (Endpoint endpoint : datasourceConfiguration.getEndpoints()) {
                hosts.add(new HttpHost(endpoint.getHost(), endpoint.getPort().intValue(), "http"));
            }

            final RestClientBuilder clientBuilder = RestClient.builder(hosts.toArray(new HttpHost[]{}));

            final AuthenticationDTO authentication = datasourceConfiguration.getAuthentication();
            if (authentication != null
                    && !StringUtils.isEmpty(authentication.getUsername())
                    && !StringUtils.isEmpty(authentication.getPassword())) {
                final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                credentialsProvider.setCredentials(
                        AuthScope.ANY,
                        new UsernamePasswordCredentials(authentication.getUsername(), authentication.getPassword())
                );

                clientBuilder
                        .setHttpClientConfigCallback(
                                httpClientBuilder -> httpClientBuilder
                                        .setDefaultCredentialsProvider(credentialsProvider)
                        );
            }

            if (!CollectionUtils.isEmpty(datasourceConfiguration.getHeaders())) {
                clientBuilder.setDefaultHeaders(
                        (Header[]) datasourceConfiguration.getHeaders()
                                .stream()
                                .map(h -> new BasicHeader(h.getKey(), h.getValue()))
                                .toArray()
                );
            }

            return Mono.just(clientBuilder.build());
        }

        @Override
        public void datasourceDestroy(RestClient client) {
            try {
                client.close();
            } catch (IOException e) {
                log.warn("Error closing connection to ElasticSearch.", e);
            }
        }

        @Override
        public Set<String> validateDatasource(DatasourceConfiguration datasourceConfiguration) {
            Set<String> invalids = new HashSet<>();

            if (CollectionUtils.isEmpty(datasourceConfiguration.getEndpoints())) {
                invalids.add("No endpoint provided. Please provide a host:port where ElasticSearch is reachable.");
            }

            return invalids;
        }

        @Override
        public Mono<DatasourceTestResult> testDatasource(DatasourceConfiguration datasourceConfiguration) {
            return datasourceCreate(datasourceConfiguration)
                    .map(client -> {
                        if (client == null) {
                            return new DatasourceTestResult("Null client object to ElasticSearch.");
                        }

                        // This HEAD request is to check if an index exists. It response with 200 if the index exists,
                        // 404 if it doesn't. We just check for either of these two.
                        // Ref: https://www.elastic.co/guide/en/elasticsearch/reference/current/indices-exists.html
                        Request request = new Request("HEAD", "/potentially-missing-index?local=true");

                        final Response response;
                        try {
                            response = client.performRequest(request);
                        } catch (IOException e) {
                            return new DatasourceTestResult("Error running HEAD request: " + e.getMessage());
                        }

                        final StatusLine statusLine = response.getStatusLine();

                        try {
                            client.close();
                        } catch (IOException e) {
                            log.warn("Error closing ElasticSearch client that was made for testing.", e);
                        }

                        if (statusLine.getStatusCode() != 404 && statusLine.getStatusCode() != 200) {
                            return new DatasourceTestResult(
                                    "Unexpected response from ElasticSearch: " + statusLine);
                        }

                        return new DatasourceTestResult();
                    })
                    .onErrorResume(error -> Mono.just(new DatasourceTestResult(error.getMessage())));
        }
    }
}
