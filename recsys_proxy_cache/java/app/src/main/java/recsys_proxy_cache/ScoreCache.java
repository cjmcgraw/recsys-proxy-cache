package recsys_proxy_cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.google.common.collect.Maps;
import com.google.common.hash.Hashing;
import com.google.common.primitives.Bytes;
import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import recsys_proxy_cache.protos.Context;
import recsys_proxy_cache.protos.Item;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.*;

public class ScoreCache {
    private static final Logger log = LoggerFactory.getLogger(ScoreCache.class.getName());
    /*
     * Shared cache by all threads, backed by concurrent hashmap. See the javadocs for more.
     *
     * The maximumSize of the cache is a point of interest for the keys. We want to keep the
     * cache size large enough to catch enough cases of repeat scoring, but small enough to
     * keep memory sufficiently low. The larger the maximumSize the more likely collisions
     * are. So choosing our hash algorithm is critical too.
     *
     * There are a lot of considerations here
     */
    private static final Cache<ByteBuffer, Double> internalCache = Caffeine
            .newBuilder()
            .maximumSize(250_000_000)
            .expireAfterAccess(5, TimeUnit.MINUTES)
            .scheduler(Scheduler.systemScheduler())
            .build();

    /*
     * memory impacted by queue size and threads operatingb
     */
    private static final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(10_000);
    private static final ThreadPoolExecutor insertExecutor = new ThreadPoolExecutor(
            3, 16, 10, TimeUnit.SECONDS, queue, Executors.defaultThreadFactory()
    );


    static void shutdown() {
        log.warn("shutting down cache gracefully");
        queue.clear();
        insertExecutor.shutdown();
    }

    final private byte[] contextBytes;
    private ScoreCache(String modelName, String modelVersion, Context context) {
        contextBytes = Bytes.concat(
                modelName.getBytes(),
                modelVersion.getBytes(),
                context.toByteArray()
        );
    }

    private ByteBuffer getHashKey(Item item) {
        var itemBytes = item.toByteArray();

        /*
         * Using murmur3 because its non-cryptographic and generally
         * fast. We may want to consider another fast hash such as
         * CityHash or FarmHash.
         *
         * I could only find a 128 murmur. really wanted 128 farmhash.
         *
         * It is however important that we keep the maximumSize in
         * mind on the cache as well though. Consider the collision
         * probability chance at 100Million keys
         *
         * Ideally we'd keep the hash size greater than 64 to ensure
         * hash collisions are rare. Ideally we'd have it greater than
         * 160bits to force them to be almost non-existent
         *
         * But we also need to balance this against speed and memory
         * usage in the cache as well.
         */
        var bytes = Hashing
                .murmur3_128()
                .newHasher(contextBytes.length + itemBytes.length)
                .putBytes(contextBytes)
                .putBytes(itemBytes)
                .hash()
                .asBytes();
        return ByteBuffer.wrap(bytes);
    }

    Map<Item, Double> getScores(Collection<Item> items) {
        var itemLookup = Maps.<ByteBuffer, Item>newHashMapWithExpectedSize(items.size());
        for (var item : items) {
            var key = getHashKey(item);
            itemLookup.put(key, item);
        }

        var cachedData = internalCache.getAllPresent(itemLookup.keySet());
        var scoredItems = Maps.<Item, Double>newHashMapWithExpectedSize(cachedData.size());
        for (var entry: cachedData.entrySet()) {
            var item = itemLookup.get(entry.getKey());
            scoredItems.put(item, entry.getValue());
        }

        return scoredItems;
    }

    void setScores(Map<Item, Double> scores) {
        /*
         * Currently, we are using caffeine for the implementation
         * of this shared internalCache. Caffeine internally uses a
         * ConcurrentHashMap to store data. Everything from this point
         * in is just going to be about usage of that ConcurrentHashMap
         *
         * https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/ConcurrentHashMap.html         *
         *
         * All reads should happen without locking utilizing volatile memory,
         * this means that each individual read will get the most "updated"
         * key without amortized locking. But it could be that there are updates
         * waiting on a lock while a read is being performed, therefore it is
         * out of date.
         *
         * Writes are a very different story though. The ConcurrentHashMap will
         * create a number of locks then lock on each bucket during the put
         * operations. This means that during any given "put" operation we
         * may see it block as it requires the lock to operate.
         *
         * The most sensible option here to me is to limit the thread capacity
         * of updates by queueing them and having thread pools manage the updates.
         * With this we will never fully freeze, but instead queue updates that
         * should operate on a thread pool that won't spike heavily.
         *
         * There is a trade-off with this approach though. Queueing the updates
         * means that we will silently miss more in the cache. Cache misses mean
         * more fallthrough to underlying systems increasing their load.
         *
         * The other option here is we return the grpc response in the service
         * and continue processing the insert. This would tie up threads in the service
         * instead. Is it better to silently fall through the cache, or loudly block the
         * service when the cache write is overwhelmed?
         *
         * I am now opting to silently fail. May god have mercy on us.
         */
        try {
            insertExecutor.execute(() -> {
                var scoresToInsert = Maps.<ByteBuffer, Double>newHashMapWithExpectedSize(scores.size());
                for (var entry : scores.entrySet()) {
                    var key = getHashKey(entry.getKey());
                    scoresToInsert.put(key, entry.getValue());
                }
                internalCache.putAll(scoresToInsert);
            });
        } catch (RejectedExecutionException exception) {
            log.error(exception.getMessage());
            log.warn("cache insert has exceeded maximum queue size! Ignoring cache insert/update temporarily");
            // silent failure
        }
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
        private String modelVersion;
        private Context context;

        private Builder() {}

        public Builder withModelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder withModelVersion(String modelVersion) {
            this.modelVersion = modelVersion;
            return this;
        }

        public Builder withContext(Context context) {
            this.context = context;
            return this;
        }

        public ScoreCache build() {
            return new ScoreCache(
                    modelName,
                    modelVersion,
                    context
            );
        }
    }
}
