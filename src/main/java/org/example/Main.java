package org.example;

import okhttp3.*;
import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Main {
    private static final String URL = "http://localhost:7777/"; // Replace with your API
    private static final int REQUEST_COUNT = 20_000; // Number of requests
    public static void main(String[] args) throws Exception {
        runInBuildHttpClientBenchMark();
        runApacheHttpAsyncBenchmark();
        runOkHttpBenchmark();
    }

    private static void runInBuildHttpClientBenchMark() {
        long startTime = System.currentTimeMillis();
        HttpClient client = HttpClient.newBuilder().executor(Executors.newFixedThreadPool(1)).build(); // 200 max connections
        List<CompletableFuture<HttpResponse<String>>> futures = new ArrayList<>();
        for (int i = 0; i < REQUEST_COUNT; i++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(URL))
                    .GET()
                    .build();
            futures.add(client.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
        }

        futures.forEach(futureResponse -> futureResponse.thenApply(response -> {
                    if(response.statusCode() >= 200 && response.statusCode() <= 300) {
                        return Optional.of(response.body());
                    }
                    return Optional.empty();
                }).thenAccept(optionalResponse -> {if (optionalResponse.isPresent()) {
//            System.out.println("Response: " + optionalResponse.get());
                } else {
                    System.out.println("Error: Response not successful");
                }}).whenComplete((result, error) -> {
                    if (error != null) {
                        System.out.println("Error: " + error.getMessage());
                    }
                })
                .exceptionally(e -> {
                    System.out.println("Error: " + e.getMessage());
                    return null;
                }));
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        long endTime = System.currentTimeMillis();
        System.out.println("Inbuild HttpAsyncClient Benchmark Completed in: " + (endTime - startTime) + " ms");
    }
    private static void runApacheHttpAsyncBenchmark() throws Exception {
        // ✅ Define Connection Pooling
        PoolingAsyncClientConnectionManager connectionManager = new PoolingAsyncClientConnectionManager();
        connectionManager.setMaxTotal(20); // 200 max connections
        connectionManager.setDefaultMaxPerRoute(20); // 200 per domain
        IOReactorConfig ioReactorConfig = IOReactorConfig.custom()
                .setSoTimeout(Timeout.ofMilliseconds(60*1000)) // Set socket timeout read time out.
                .setIoThreadCount(1) // Number of threads
                .setTcpNoDelay(true)  // Enable fast small packet transfer
                .build();
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(1)) // Connection Timeout (10s)
                .setResponseTimeout(Timeout.ofSeconds(1)) // Response Timeout (10s)
                .build();
        // ✅ Create HTTP Client with Connection Pool
        CloseableHttpAsyncClient client = HttpAsyncClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .setIOReactorConfig(ioReactorConfig)
                .setConnectionManager(connectionManager)
                .evictIdleConnections(TimeValue.ofSeconds(30)) // 30s keep-alive
                .build();
        client.start(); // Start the client

        CountDownLatch latch = new CountDownLatch(REQUEST_COUNT);
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < REQUEST_COUNT; i++) {
            SimpleHttpRequest request = SimpleHttpRequest.create("GET", URL);
            client.execute(request, new FutureCallback<SimpleHttpResponse>() {
                @Override
                public void completed(SimpleHttpResponse result) {
                    latch.countDown();
                }

                @Override
                public void failed(Exception ex) {
                    latch.countDown();
                }

                @Override
                public void cancelled() {
                    latch.countDown();
                }
            });
        }

        latch.await(); // Wait for all requests to complete
        client.close(); // Close client

        long endTime = System.currentTimeMillis();
        System.out.println("Apache HttpAsyncClient Benchmark Completed in: " + (endTime - startTime) + " ms");
    }


    /**
     * Runs benchmark using OkHttpClient
     */
    private static void runOkHttpBenchmark() throws InterruptedException {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectionPool(new ConnectionPool(20, 30, TimeUnit.SECONDS)) // 200 max connections, 30s keep-alive
                .build();

        CountDownLatch latch = new CountDownLatch(REQUEST_COUNT);
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < REQUEST_COUNT; i++) {
            Request request = new Request.Builder().url(URL).build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    latch.countDown();
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    response.close();
                    latch.countDown();
                }
            });
        }

        latch.await(); // Wait for all requests to finish
        long endTime = System.currentTimeMillis();
        System.out.println("OkHttp Benchmark Completed in: " + (endTime - startTime) + " ms");
    }
}