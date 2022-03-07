package recsys_proxy_cache;

import com.google.common.collect.Maps;
import org.checkerframework.checker.units.qual.A;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import recsys_proxy_cache.protos.Item;
import tensorflow.serving.ModelServiceGrpc;
import tensorflow.serving.PredictionServiceGrpc;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.random.RandomGenerator;

public class RecsysProxy {
    private static final Logger log = LoggerFactory.getLogger(RecsysProxy.class.getName());
    private static final RandomGenerator random = RandomGenerator.getDefault();
    private static final ExecutorService channelThreadPool = Executors.newCachedThreadPool();
    private static final ExecutorService rpcThreadPool = Executors.newCachedThreadPool();
    private static final PredictionServiceGrpc.PredictionServiceFutureStub predictionStub = getPredictionStub();
    private static final ModelServiceGrpc.ModelServiceFutureStub modelStub = getModelStub();

    private static PredictionServiceGrpc.PredictionServiceFutureStub getPredictionStub() {
        return null;
    }

    private static ModelServiceGrpc.ModelServiceFutureStub getModelStub() {
        return null;
    }

    private final String modelName;
    private final String modelVersion;

    private RecsysProxy(String modelName, String modelVersion) {
        this.modelName = modelName;
        this.modelVersion = modelVersion;
    }

    public Map<Item, Double> score(Collection<Item> items) {
        return getRandomScores(items);
    }

    private Map<Item, Double> getRandomScores(Collection<Item> items) {
        var randomScores = Maps.<Item, Double>newHashMapWithExpectedSize(items.size());
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
        public static Builder newBuilder() { return new Builder(); }

        private String modelName;
        private String modelVersion;
        private Builder() {}

        public Builder withModelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder withModelVersion(String modelVersion) {
            this.modelVersion = modelVersion;
            return this;
        }

        public RecsysProxy build() {
            return new RecsysProxy(modelName, modelVersion);
        }

    }
}
