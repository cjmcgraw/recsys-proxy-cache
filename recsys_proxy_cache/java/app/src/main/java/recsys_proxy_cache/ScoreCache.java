package recsys_proxy_cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.google.common.collect.Maps;
import com.google.common.hash.Hashing;
import com.google.common.primitives.Bytes;
import com.github.benmanes.caffeine.cache.Cache;
import com.google.common.primitives.Longs;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import recsys_proxy_cache.protos.Context;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.*;

public class ScoreCache {
    private static final Logger log = LoggerFactory.getLogger(ScoreCache.class.getName());
    /*
     * Shared cache by all threads, backed by concurrent hashmap. See the javadocs for more.
     *
     * The maximumSize of the cache is a point of interest in the keys. We want to keep the
     * cache size large enough to catch enough cases of repeat scoring, but small enough to
     * keep memory sufficiently low. The larger the maximumSize the more likely collisions
     * are. So choosing our hash algorithm is critical too.
     *
     * There are a lot of considerations here
     */
    private static final Cache<ByteBuffer, Double> internalCache = Caffeine
            .newBuilder()
            .maximumSize(200_000_000)
            .expireAfterAccess(5, TimeUnit.MINUTES)
            .scheduler(Scheduler.systemScheduler())
            .build();

    /*
     * memory impacted by queue size and threads operating
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

    final private byte[] hashedContext;
    private ScoreCache(String modelName, String modelVersion, Context context) {
        var nameBytes = modelName.getBytes(StandardCharsets.US_ASCII);
        var versionBytes = modelVersion.getBytes(StandardCharsets.US_ASCII);
        var contextBytes = context.toByteArray();

        /*
         * Using farmfingerprint64 because its non-cryptographic and generally
         * fast. Preference to usage over murmur3 simply because of known speed.
         *
         * We will be appending the item id bytes to the farmfingerprint64 to
         * reduce has collisions further. Thus producing a 128-bit unique key
         *
         * It is however important that we keep the maximumSize in
         * mind on the cache as well though. Consider the collision
         * probability chance at 100Million keys
         *
         * But we also need to balance this against speed and memory
         * usage in the cache as well.
         */
        hashedContext = Hashing
                .farmHashFingerprint64()
                .newHasher(nameBytes.length + versionBytes.length + contextBytes.length)
                .putBytes(nameBytes)
                .putBytes(versionBytes)
                .putBytes(contextBytes)
                .hash()
                .asBytes();
    }

    private ByteBuffer getHashKey(long item) {
        return ByteBuffer.wrap(
                Bytes.concat(
                        hashedContext,
                        Longs.toByteArray(item)
                )
        );
    }

    Map<Long, Double> getScores(Collection<Long> items) {
        var itemLookup = Maps.<ByteBuffer, Long>newHashMapWithExpectedSize(items.size());
        for (var item : items) {
            var key = getHashKey(item);
            itemLookup.put(key, item);
        }

        var cachedData = internalCache.getAllPresent(itemLookup.keySet());
        var scoredItems = Maps.<Long, Double>newHashMapWithExpectedSize(cachedData.size());
        for (var entry: cachedData.entrySet()) {
            var item = itemLookup.get(entry.getKey());
            scoredItems.put(item, entry.getValue());
        }

        return scoredItems;
    }

    void setScores(Map<Long, Double> scores) {
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
         * There is a trade-off with this approach though. Queueing the update's
          means that we will silently miss more in the cache. Cache misses mean
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
     * This allows the main classes' constructor to be private, and forces
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
