package recsys_proxy_cache.cache;

import com.google.common.collect.Maps;
import com.google.common.hash.Hashing;
import com.google.common.primitives.UnsignedLong;
import com.jsoniter.JsonIterator;
import java.io.FileNotFoundException;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HighCardinalityKeys {
    private static final String resourceFileName = "high-cardinality-context-keys.json";
    private static final Logger log = LoggerFactory.getLogger(HighCardinalityKeys.class);
    private static final Map<String, Hasher> highCardinalityKeyLookup = getHighCardinalityKeyLookup();

    public static boolean isHighCardinality(String key) {
        return highCardinalityKeyLookup.containsKey(key);
    }

    public static UnsignedLong hashHighCardinalityKey(String key, byte[] value) {
        return highCardinalityKeyLookup
                .get(key)
                .applyHash(value);
    }

    private static Map<String, Hasher> getHighCardinalityKeyLookup() {
        log.warn("Starting load of HighCardinalityKey configurations at {}", resourceFileName);
        try {
            var highCardinalityKeyData = Maps.<String, Hasher>newHashMap();
            var resourceStream = Optional.ofNullable(
                    ScoreCache.class
                    .getClassLoader()
                    .getResourceAsStream(resourceFileName)
            );
            if (resourceStream.isEmpty()) {
                throw new FileNotFoundException("Failed to find resource file for high cardinality keys!");
            }
            var highCardinalityConfigsJson = JsonIterator.deserialize(
                    resourceStream
                            .get()
                            .readAllBytes()
            ).asList();

            log.info("found {} high cardinality key configurations", highCardinalityConfigsJson.size());
            for (var configJson : highCardinalityConfigsJson) {
                var config = configJson.asMap();
                var contextKey = config.get("key").as(String.class);

                log.info("processing high cardinality key configuration key={}", contextKey);
                var intBuckets = config.get("buckets").as(Long.class);
                UnsignedLong buckets = null;
                if (intBuckets != null) {
                    buckets = UnsignedLong.fromLongBits(intBuckets);
                }
                var hasher = new Hasher(
                        config.get("hashFunction").as(String.class),
                        buckets
                );

                highCardinalityKeyData.put(contextKey, hasher);
            }


            log.info("finished loading high cardinality key configurations");
            return highCardinalityKeyData;
        } catch (Exception exception) {
            log.error("failed to read high-cardinality-context-keys.json file", exception);
            throw new RuntimeException(exception);
        }
    }

    private static class Hasher {
        private final Function<byte[], UnsignedLong> hashingFunction;
        private final Function<UnsignedLong, UnsignedLong> bucketize;

        Hasher(String hashingFunctionStr, @Nullable UnsignedLong buckets) {
            switch (hashingFunctionStr.toLowerCase()) {
                case "farmfingerprint64":
                    hashingFunction = bytes -> UnsignedLong.fromLongBits(
                            Hashing
                                    .farmHashFingerprint64()
                                    .newHasher(bytes.length)
                                    .putBytes(bytes)
                                    .hash()
                                    .asLong()
                    );
                    break;
                default:
                    log.error("error processing unknown hashFunction={}", hashingFunctionStr);
                    throw new RuntimeException("failed to process high cardinality key hashing data");
            }

            if (buckets != null) {
                bucketize = l -> l.mod(buckets);
            } else {
                bucketize = Function.identity();
            }
        }

        UnsignedLong applyHash(byte[] bytes) {
            return bucketize.apply(hashingFunction.apply(bytes));
        }
    }
}
