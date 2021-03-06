package io.swagger.client;

import okhttp3.*;
import okio.Buffer;
import okio.BufferedSource;
import play.libs.ws.WSClient;
import play.libs.ws.WSRequest;
import play.libs.ws.WSResponse;
import play.libs.ws.WSRequestFilter;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Creates {@link Call} instances that invoke underlying {@link WSClient}
 */
public class Play25CallFactory implements okhttp3.Call.Factory {

    /** PlayWS http client */
    private final WSClient wsClient;

    /** Extra headers to add to request */
    private Map<String, String> extraHeaders = new HashMap<>();

    /** Extra query parameters to add to request */
    private List<Pair> extraQueryParams = new ArrayList<>();
    
    /** Filters (interceptors) */
    private List<WSRequestFilter> filters = new ArrayList<>();

    public Play25CallFactory(WSClient wsClient) {
        this.wsClient = wsClient;
    }

    public Play25CallFactory(WSClient wsClient, List<WSRequestFilter> filters) {
        this.wsClient = wsClient;
        this.filters.addAll(filters);
    }

    public Play25CallFactory(WSClient wsClient, Map<String, String> extraHeaders,
        List<Pair> extraQueryParams) {
        this.wsClient = wsClient;

        this.extraHeaders.putAll(extraHeaders);
        this.extraQueryParams.addAll(extraQueryParams);
    }

    @Override
    public Call newCall(Request request) {
        // add extra headers
        Request.Builder rb = request.newBuilder();
        for (Map.Entry<String, String> header : this.extraHeaders.entrySet()) {
            rb.addHeader(header.getKey(), header.getValue());
        }

        // add extra query params
        if (!this.extraQueryParams.isEmpty()) {
            String newQuery = request.url().uri().getQuery();
            for (Pair queryParam : this.extraQueryParams) {
                String param = String.format("%s=%s", queryParam.getName(), queryParam.getValue());
                if (newQuery == null) {
                    newQuery = param;
                } else {
                    newQuery += "&" + param;
                }
            }

            URI newUri;
            try {
                newUri = new URI(request.url().uri().getScheme(), request.url().uri().getAuthority(),
                        request.url().uri().getPath(), newQuery, request.url().uri().getFragment());
                rb.url(newUri.toURL());
            } catch (MalformedURLException | URISyntaxException e) {
                throw new RuntimeException("Error while updating an url", e);
            }
        }

        return new PlayWSCall(wsClient, this.filters, rb.build());
    }

    /**
     * Call implementation that delegates to Play WS Client
     */
    static class PlayWSCall implements Call {

        private final WSClient wsClient;
        private WSRequest wsRequest;
        private List<WSRequestFilter> filters;

        private final Request request;

        public PlayWSCall(WSClient wsClient, List<WSRequestFilter> filters, Request request) {
            this.wsClient = wsClient;
            this.request = request;
            this.filters = filters;
        }

        @Override
        public Request request() {
            return request;
        }

        @Override
        public void enqueue(final okhttp3.Callback responseCallback) {
            final Call call = this;
            final CompletionStage<WSResponse> promise = executeAsync();

            promise.whenCompleteAsync((v, t) -> {
                if (t != null) {
                    if (t instanceof IOException) {
                        responseCallback.onFailure(call, (IOException) t);
                    } else {
                        responseCallback.onFailure(call, new IOException(t));
                    }
                } else {
                    try {
                        responseCallback.onResponse(call, PlayWSCall.this.toWSResponse(v));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }, play.libs.concurrent.HttpExecution.defaultContext());
        }

        CompletionStage<WSResponse> executeAsync() {
            try {
                wsRequest = wsClient.url(request.url().uri().toString());
                addHeaders(wsRequest);
                if (request.body() != null) {
                    addBody(wsRequest);
                }
                filters.stream().forEach(f -> wsRequest.withRequestFilter(f));

                return wsRequest.execute(request.method());
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }

        private void addHeaders(WSRequest wsRequest) {
            for(Map.Entry<String, List<String>> entry : request.headers().toMultimap().entrySet()) {
                List<String> values = entry.getValue();
                for (String value : values) {
                    wsRequest.setHeader(entry.getKey(), value);
                }
            }
        }

        private void addBody(WSRequest wsRequest) throws IOException {
            Buffer buffer = new Buffer();
            request.body().writeTo(buffer);
            wsRequest.setBody(buffer.inputStream());

            MediaType mediaType = request.body().contentType();
            if (mediaType != null) {
                wsRequest.setContentType(mediaType.toString());
            }
        }

        private Response toWSResponse(final WSResponse r) {
            final Response.Builder builder = new Response.Builder();
            builder.request(request)
                   .code(r.getStatus())
                   .body(new ResponseBody() {

                       @Override
                       public MediaType contentType() {
                           return Optional.ofNullable(r.getHeader("Content-Type"))
                                          .map(MediaType::parse)
                                          .orElse(null);
                       }

                       @Override
                       public long contentLength() {
                           return r.asByteArray().length;
                       }

                       @Override
                       public BufferedSource source() {
                           return new Buffer().write(r.asByteArray());
                       }

                   });
                   
            for (Map.Entry<String, List<String>> entry : r.getAllHeaders().entrySet()) {
                for (String value : entry.getValue()) {
                    builder.addHeader(entry.getKey(), value);
                }
            }

            builder.protocol(Protocol.HTTP_1_1);
            return builder.build();
        }

        @Override
        public Response execute() throws IOException {
            throw new UnsupportedOperationException("Not supported");
        }

        @Override
        public void cancel() {
            throw new UnsupportedOperationException("Not supported");
        }

        @Override
        public PlayWSCall clone() {
            throw new UnsupportedOperationException("Not supported");
        }

        @Override
        public boolean isExecuted() {
            return false;
        }

        @Override
        public boolean isCanceled() {
            return false;
        }
    }
}
