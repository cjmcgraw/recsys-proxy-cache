package recsys_proxy_cache;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import recsys_proxy_cache.protos.Item;
import recsys_proxy_cache.protos.RecsysProxyCacheGrpc;
import recsys_proxy_cache.protos.ScoreRequest;
import recsys_proxy_cache.protos.ScoreResponse;

import java.util.Map;
import java.util.Optional;


class GrpcService extends RecsysProxyCacheGrpc.RecsysProxyCacheImplBase {
    private static final Logger log = LoggerFactory.getLogger(GrpcService.class);

    private final ScoreCache.Builder scoreCacheBuilder;
    private final RecsysProxy.Builder recsysProxyBuilder;

    public GrpcService() {
        this(
                ScoreCache.Builder.newBuilder(),
                RecsysProxy.Builder.newBuilder()
        );
    }

    GrpcService(
        ScoreCache.Builder scoreCacheBuilder,
        RecsysProxy.Builder recsysProxyBuilder) {
        this.recsysProxyBuilder = recsysProxyBuilder;
        this.scoreCacheBuilder = scoreCacheBuilder;
    }

    private void getScoresInner(ScoreRequest request, StreamObserver<ScoreResponse> responseObserver) throws StatusException {
        if (request.getItemsCount() <= 0) {
           throw Status
                   .INVALID_ARGUMENT
                   .withDescription("must provide at least 1 item for scoring. Received 0 items")
                   .asException();
        }
        var items = Sets.newHashSet(request.getItemsList());
        var scoreCache = scoreCacheBuilder
                .withModelName(request.getModelName())
                .withModelVersion(request.getModelVersion())
                .withContext(request.getContext())
                .build();

        var recsysProxy = recsysProxyBuilder
                .withModelName(request.getModelName())
                .withModelVersion(request.getModelVersion())
                .build();

        var itemsToScores = scoreCache.getScores(items);
        items.removeAll(itemsToScores.keySet());

        var newScores = Optional.<Map<Item, Double>>empty();
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

    @Override
    public void getScores(ScoreRequest request, StreamObserver<ScoreResponse> responseObserver) {
        try {
            getScoresInner(request, responseObserver);
        } catch (Exception exception) {
            log.error("unexpected exception thrown during getScores method", exception);
            responseObserver.onError(exception);
        }
    }
}