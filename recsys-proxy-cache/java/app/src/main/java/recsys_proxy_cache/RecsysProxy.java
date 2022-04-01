/*
 * Copyright 2022 Carl McGraw c@rlmcgraw.io
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included
 *  in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package recsys_proxy_cache;

import com.google.common.collect.Maps;
import com.google.common.collect.Streams;
import io.grpc.EquivalentAddressGroup;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.NameResolver;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import java.util.AbstractMap;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UnknownFormatConversionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tensorflow.framework.DataType;
import org.tensorflow.framework.TensorProto;
import org.tensorflow.framework.TensorShapeProto;
import org.tensorflow.framework.TensorShapeProto.Dim;
import recsys_proxy_cache.protos.Context;
import tensorflow.serving.Model;
import tensorflow.serving.Predict.PredictRequest;
import tensorflow.serving.PredictionServiceGrpc;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.random.RandomGenerator;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Function;
import tensorflow.serving.PredictionServiceGrpc.PredictionServiceFutureStub;

public class RecsysProxy {
    private static final String TARGET = System.getenv("RECSYS_TARGET");
    private static final RandomGenerator random = RandomGenerator.getDefault();
    private static final ExecutorService CHANNEL_THREADPOOL = Executors.newCachedThreadPool();
    private static final ExecutorService RPC_THREADPOOL = Executors.newCachedThreadPool();
    private static final ScheduledExecutorService REFRESH_EXECUTOR = Executors.newSingleThreadScheduledExecutor();
    private static final Logger log = LoggerFactory.getLogger(RecsysProxy.class.getName());
    private static ManagedChannel CHANNEL;
    private static PredictionServiceGrpc.PredictionServiceFutureStub TFSERVING_STUB;

    private static PredictionServiceGrpc.PredictionServiceFutureStub getPredictionStub() {
        if (TFSERVING_STUB != null && !CHANNEL.isShutdown() && !CHANNEL.isTerminated()) {
            return TFSERVING_STUB;
        }

        if (TARGET == null || TARGET.length() <= 0) {
            log.error("invalid RECSYS_TAGET found/given. Please ensure target is set correctly in envvars");
            throw new StatusRuntimeException(
                    Status.INTERNAL
            );
        }

        log.warn("setting up new channel for target={}", TARGET);
        CHANNEL = ManagedChannelBuilder
                .forTarget(TARGET)
                .usePlaintext()
                .executor(CHANNEL_THREADPOOL)
                .enableFullStreamDecompression()
                .build();

        var state = CHANNEL.getState(true);
        log.info("channel state={}", state);
        TFSERVING_STUB = PredictionServiceGrpc
                .newFutureStub(CHANNEL)
                .withExecutor(RPC_THREADPOOL)
                .withCompression("gzip")
                .withWaitForReady();
        return TFSERVING_STUB;
    }

    private final String modelName;
    private final Context mlModelContext;
    private final PredictionServiceFutureStub predictStub;

    private RecsysProxy(String modelName, Context mlModelContext, PredictionServiceFutureStub predictStub) {
        this.modelName = modelName;
        this.mlModelContext = mlModelContext;
        this.predictStub = predictStub;
    }

    public Map<Long, Double> score(Collection<Long> items) throws StatusException, ExecutionException, InterruptedException, TimeoutException {
        return switch (modelName.toLowerCase()) {
            case "random" -> getRandomScores(items);
            default -> getTfServingScores(items);
        };
    }

    private Map<Long, Double> getTfServingScores(Collection<Long> items) throws ExecutionException, InterruptedException, TimeoutException {
        var tfServingModelSpec = Model.ModelSpec
                .newBuilder()
                .setName(modelName)
                .build();

        var predictRequestBuilder = PredictRequest.newBuilder()
                .setModelSpec(tfServingModelSpec)
                .putInputs(
                        // item id is hardcoded here and expected to be present in all models
                        "item_id",
                        TensorProto
                                .newBuilder()
                                .setDtype(DataType.DT_INT64)
                                .setTensorShape(TensorShapeProto
                                        .newBuilder()
                                        .addDim(Dim.newBuilder().setSize(1))
                                        .addDim(Dim.newBuilder().setSize(items.size()))
                                )
                                .addAllInt64Val(items)
                                .build()
                );

        // process and add context
        for (var entry : mlModelContext.getFieldsMap().entrySet()) {
            predictRequestBuilder.putInputs(
                    entry.getKey(),
                    TensorProto
                            .newBuilder()
                            .setDtype(DataType.DT_STRING)
                            .setTensorShape(TensorShapeProto
                                    .newBuilder()
                                    .addDim(Dim
                                            .newBuilder()
                                            .setSize(entry.getValue().getValuesCount())
                                    )
                            )
                            .addAllStringVal(entry
                                    .getValue()
                                    .getValuesList()
                                    .asByteStringList()
                            )
                            .build()
            );
        }

        var fut = predictStub
                .withDeadlineAfter(500, TimeUnit.MILLISECONDS)
                .predict(predictRequestBuilder.build());
        var response = fut.get(150, TimeUnit.MILLISECONDS);

        var scores = response.getOutputsMap().get("scores") .getDoubleValList();
        var itemsToScores = Streams.zip(
                items.stream(),
                scores.stream(),
                SimpleImmutableEntry::new)
                .collect(Collectors.toMap(
                        AbstractMap.SimpleImmutableEntry::getKey,
                        AbstractMap.SimpleImmutableEntry::getValue
                ));

        return itemsToScores;
    }

    private Map<Long, Double> getRandomScores(Collection<Long> items) {
        var randomScores = Maps.<Long, Double>newHashMapWithExpectedSize(items.size());
        for(var item: items) {
            randomScores.put(item, random.nextDouble());
        }
        return randomScores;
    }

    /**
     * Java inner builder pattern
     *
     * https://blogs.oracle.com/javamagazine/post/exploring-joshua-blochs-builder-design-pattern-in-java
     *
     * This class is mainly used to build the above classes internal state.
     *
     * This allows the main classes constructor to be private, and forces
     * callers to allow injection of a builder, which helps with tests.
     *
     * This pattern was adapted from guava, googles code bases, protobuf
     * implementations and usage from my experience developing some in
     * apache beam
     */
    static class Builder {
        public static Builder newBuilder() {
            return new Builder();
        }

        private String modelName;
        private recsys_proxy_cache.protos.Context modelContext;
        private PredictionServiceFutureStub stub;
        private Builder() {}

        public Builder withModelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder withContext(Context modelContext) {
            this.modelContext = modelContext;
            return this;
        }

        public Builder withStub(PredictionServiceFutureStub stub) {
            this.stub = stub;
            return this;
        }

        public RecsysProxy build() {
            if (stub == null) {
                stub = getPredictionStub();
            }
            return new RecsysProxy(
                    modelName,
                    modelContext,
                    stub
            );
        }
    }

    static class AtgSwarmLookasideLoadBalancer extends NameResolver {
        private static final Duration MAX_WAIT_FOR_COMPLETION_TIME = Duration.ofSeconds(2);

        private final Function<HttpResponse<String>, Set<String>> parseResponse;
        private final Duration refreshTime;
        private final Duration httpTimeout;

        private final HttpClient httpClient;
        private final HttpRequest httpRequest;
        private final Executor executor;
        private final URI target;

        private ScheduledExecutorService refreshExecutor;
        private Set<String> knownTargets;
        private CompletableFuture<Void> pendingRequest;
        private long timeOfLastCache;
        private boolean shouldClearCacheWhenAvailable = false;
        private Listener2 lastKnownListener;

        AtgSwarmLookasideLoadBalancer(
                Executor executor,
                URI target,
                Function<HttpResponse<String>, Set<String>> parseResponse,
                Duration refreshTime,
                Duration httpTimeout
        ) {
            this.refreshTime = refreshTime;
            this.httpTimeout = httpTimeout;
            this.parseResponse = parseResponse;
            this.target = target;
            this.knownTargets = new HashSet<>();
            this.timeOfLastCache = 0L;
            this.executor = executor;

            httpClient = HttpClient
                    .newBuilder()
                    .executor(executor)
                    .connectTimeout(httpTimeout)
                    .build();

            httpRequest = HttpRequest
                    .newBuilder()
                    .GET()
                    .uri(target)
                    .build();

            if (refreshTime.toMillis() > 0) {
                refreshExecutor = Executors.newSingleThreadScheduledExecutor();
                refreshExecutor.scheduleAtFixedRate(
                        this::refresh,
                        refreshTime.toMillis(),
                        refreshTime.toMillis(),
                        TimeUnit.MILLISECONDS
                );
            }
        }

        @Override
        public void start(NameResolver.Listener2 listener) {
            resolve();
            this.lastKnownListener = listener;
            executor.execute(
                    () -> {
                        try {
                            log.info("NameResolver: attempting new listener with known targets size=" + knownTargets.size());
                            if (knownTargets.isEmpty()) {
                                if (pendingRequest != null && (!pendingRequest.isDone()
                                        || pendingRequest.isCompletedExceptionally())) {
                                    pendingRequest.join();
                                }
                                if (knownTargets.isEmpty()) {
                                    log.error("NameResolver: known targets not populated yet!");
                                    throw new RuntimeException("Have not resolved known targets yet");
                                }
                            }
                            List<EquivalentAddressGroup> addresses = knownTargets
                                    .stream()
                                    .map(this::targetStringToSocket)
                                    .map(EquivalentAddressGroup::new)
                                    .toList();

                            listener.onResult(
                                    NameResolver.ResolutionResult
                                            .newBuilder()
                                            .setAddresses(addresses)
                                            .build()
                            );
                            log.info("NameResolver: Successfully updated listener");
                        } catch (Exception e) {
                            log.error("NameResolver: exception when building out for known targets!", e);
                            Status status = Status
                                    .UNAVAILABLE
                                    .withDescription("exception=" + e.getMessage())
                                    .withCause(e);
                            listener.onError(status);
                        }
                    }
            );
        }

        @Override
        public void refresh() {
            shouldClearCacheWhenAvailable = true;
            resolve();
            if (lastKnownListener != null) {
                start(lastKnownListener);
            }
        }

        @Override
        public void shutdown() {
            if (refreshExecutor != null && !refreshExecutor.isShutdown()) {
                refreshExecutor.shutdownNow();
            }
        }

        @Override
        public String getServiceAuthority() {
            return target.getAuthority();
        }

        private void resolve() {
            if (!shouldAttemptResolution()) {
                return;
            }
            log.warn("NameResolver resolve triggered!");
            timeOfLastCache = System.currentTimeMillis();
            shouldClearCacheWhenAvailable = false;
            pendingRequest = httpClient
                    .sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                    .orTimeout(httpTimeout.toMillis(), TimeUnit.MILLISECONDS)
                    .thenAcceptAsync(
                            response -> {
                                try {
                                    Set<String> validTargets = parseResponse.apply(response);
                                    if (!validTargets.isEmpty()) {
                                        knownTargets = validTargets;
                                    }
                                    log.info("NameResolver: resolve successful: " + knownTargets);
                                } catch (Exception e) {
                                    log.error("nameresolver failed to parse known response!", e);
                                    throw new RuntimeException(e);
                                }

                            },
                            executor
                    );
        }

        private InetSocketAddress targetStringToSocket(String target) {
            String[] strs = target.split(":", 2);
            if (strs.length != 2) {
                throw new UnknownFormatConversionException(
                        "Expected target to have a separator of addr:port. Found target=" + target
                );
            }
            String addr = strs[0];
            int port = Integer.parseInt(strs[1]);
            log.info("creating new connection for " + target);
            return new InetSocketAddress(addr, port);
        }

        private boolean shouldAttemptResolution() {
            long timeSinceLastUpdate = System.currentTimeMillis() - timeOfLastCache;

            if (shouldClearCacheWhenAvailable) {
                return true;
            }

            if (knownTargets.isEmpty()) {
                if (timeSinceLastUpdate > (2 * MAX_WAIT_FOR_COMPLETION_TIME.toMillis())) {
                    return true;
                }
            }

            if (timeSinceLastUpdate > refreshTime.toMillis()) {
                return true;
            }

            return false;
        }
    }
}
