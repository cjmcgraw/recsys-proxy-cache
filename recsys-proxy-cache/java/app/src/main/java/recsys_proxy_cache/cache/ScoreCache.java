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
package recsys_proxy_cache.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.google.common.collect.Maps;
import com.google.common.hash.Hashing;
import com.google.common.primitives.Bytes;
import com.github.benmanes.caffeine.cache.Cache;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
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


    public static void shutdown() {
        log.warn("shutting down cache gracefully");
        queue.clear();
        insertExecutor.shutdown();
    }

    final private byte[] hashedContext;

    private ScoreCache(String modelName, Context context) {
        var nameBytes = modelName.getBytes(StandardCharsets.US_ASCII);
        // context bytes are surprisingly complex to parse, check the function
        var contextBytes = getContextBytes(context);

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
                .newHasher(nameBytes.length + contextBytes.length)
                .putBytes(nameBytes)
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

    public Map<Long, Double> getScores(Collection<Long> items) {
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

    public void setScores(Map<Long, Double> scores) {
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
            log.warn("cache insert has exceeded maximum queue size! Ignoring cache insert/update temporarily");
            log.error("failed to insert set scores into queue. queue probably full", exception);
            // silent failure
        }
    }

    /**
     * Retrieves the bytes from the context, handling all sub-rules for context key/value
     * pairs.
     *
     * This gets a bit complicated because of the existence of HighCardinalityKeys.
     *
     * In short, some key terms in context, like "session" which is a random string
     * generated for each unique visit causes our cache to have a near 100% miss rate.
     * To handle this we will transform the values into something that is lower cardinality.
     *
     * The specifics of which will be pushed out to another class
     *
     * @param context the context to process
     * @return the bytes that represent the context with all processing handled
     */
    private byte[] getContextBytes(Context context) {
        var contextByteStream = new ByteArrayOutputStream(context.getSerializedSize());

        /*
         * since we are going to process the keys, we must sort them initially to ensure
         * that there is consistency between key/value byte pairings
         */
        var fields = context.getFieldsMap();
        var keys = new ArrayList<>(fields.keySet());
        Collections.sort(keys);

        for (var key : keys) {
            var isHighCardinality = HighCardinalityKeys.isHighCardinality(key);
            var keyBytes = key.getBytes(StandardCharsets.US_ASCII);
            contextByteStream.writeBytes(keyBytes);

            // we must sort values, to ensure that the values order is not important
            var values = new ArrayList<>(fields.get(key).getValuesList());
            Collections.sort(values);

            for (var value : values) {
                byte[] valueBytes = value.getBytes(StandardCharsets.US_ASCII);
                if (isHighCardinality) {
                    valueBytes = Ints.toByteArray(
                            HighCardinalityKeys
                                    .hashHighCardinalityKey(key, valueBytes)
                                    .intValue()
                    );

                }
                contextByteStream.writeBytes(valueBytes);
            }
        }

        return contextByteStream.toByteArray();
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
    public static class Builder {
        public static Builder newBuilder() {
            return new Builder();
        }

        private String modelName;
        private Context context;

        private Builder() {}

        public Builder withModelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder withContext(Context context) {
            this.context = context;
            return this;
        }

        public ScoreCache build() {
            return new ScoreCache(
                    modelName,
                    context
            );
        }
    }
}
