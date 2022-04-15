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

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import recsys_proxy_cache.cache.ScoreCache;
import recsys_proxy_cache.protos.RecsysProxyCacheGrpc;
import recsys_proxy_cache.protos.ScoreRequest;
import recsys_proxy_cache.protos.ScoreResponse;

import java.util.Map;
import java.util.Optional;


class GrpcService extends RecsysProxyCacheGrpc.RecsysProxyCacheImplBase {
    private static final Logger log = LoggerFactory.getLogger(GrpcService.class);

    private final Supplier<ScoreCache.Builder> scoreCacheBuilder;
    private final Supplier<RecsysProxy.Builder> recsysProxyBuilder;

    public GrpcService() {
        this(
                ScoreCache.Builder::newBuilder,
                RecsysProxy.Builder::newBuilder
        );
    }

    GrpcService(
        Supplier<ScoreCache.Builder> scoreCacheBuilder,
        Supplier<RecsysProxy.Builder> recsysProxyBuilder) {
        this.recsysProxyBuilder = recsysProxyBuilder;
        this.scoreCacheBuilder = scoreCacheBuilder;
    }

    @Override
    public void getScores(ScoreRequest request, StreamObserver<ScoreResponse> responseObserver) {
        try {
            getScoresInner(request, responseObserver);
        } catch (StatusException exception) {
            log.error("grpc status exception occurred", exception);
            responseObserver.onError(exception);
        } catch (Exception exception) {
            log.error("unexpected exception thrown during getScores method", exception);
            responseObserver.onError(Status
                    .INTERNAL
                    .withCause(exception)
                    .withDescription("unknown exception occurred!")
                    .asException()
            );
        }
    }
    private void getScoresInner(ScoreRequest request, StreamObserver<ScoreResponse> responseObserver) throws StatusException, ExecutionException, InterruptedException, TimeoutException {
        if (request.getItemsCount() <= 0) {
           throw Status
                   .INVALID_ARGUMENT
                   .withDescription("must provide at least 1 item for scoring. Received 0 items")
                   .asException();
        }
        var items = Sets.newHashSet(request.getItemsList());
        var scoreCache = scoreCacheBuilder.get()
                .withModelName(request.getModelName())
                .withContext(request.getContext())
                .build();

        var recsysProxy = recsysProxyBuilder.get()
                .withModelName(request.getModelName())
                .withContext(request.getContext())
                .build();

        var itemsToScores = scoreCache.getScores(items);
        items.removeAll(itemsToScores.keySet());

        var newScores = Optional.<Map<Long, Double>>empty();
        if (items.size() > 0) {
            newScores = Optional.of(recsysProxy.score(items));
            itemsToScores.putAll(newScores.get());
        }

        var missingItems = 0;
        var scoresList = Lists.<Double>newArrayListWithExpectedSize(items.size());
        for (var item : request.getItemsList()) {
            if (!itemsToScores.containsKey(item)) {
                log.warn("unexpected missing score for item=%s".formatted(item));
                missingItems += 1;
            } else {
                var score = itemsToScores.get(item);
                scoresList.add(score);
            }
        }

        if (missingItems > 0) {
            var msg = """
            Unexpected issue.
            
            Cache and proxy together combined less scores then requested. This means generally
            that the proxy cache returned less scores then expected for some unknown reason. Please
            investigate integrity of the proxy and the cache.
            
            items: %s
            scores: %s
            missing: %s
            
            """.formatted(request.getItemsList(), scoresList, missingItems).stripIndent();
            log.error(msg);
            throw Status.INTERNAL.withDescription(msg).asException();
        }

        var scoreResponse = ScoreResponse
                .newBuilder()
                .addAllScores(scoresList)
                .build();
        responseObserver.onNext(scoreResponse);
        responseObserver.onCompleted();

        newScores.ifPresent(scoreCache::setScores);
    }
}
