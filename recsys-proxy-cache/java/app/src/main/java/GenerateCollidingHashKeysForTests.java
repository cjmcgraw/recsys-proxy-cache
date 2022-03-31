import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import recsys_proxy_cache.cache.HighCardinalityKeys;
import java.util.Random;

public class GenerateCollidingHashKeysForTests {
    public static void main(String... args) {
        var rand = new Random();

        Function<Integer, byte[]> generateRandomBytes = x -> {
            String uuid1 = UUID.randomUUID().toString().toLowerCase().replace("-", "");
            String uuid2 = UUID.randomUUID().toString().toLowerCase().replace("-", "").substring(0, rand.nextInt(0, 10));
            return (uuid1 + uuid2).getBytes(StandardCharsets.US_ASCII);
        };

        var lookup = Maps.<Integer, List<String>>newHashMapWithExpectedSize(50_000);
        for (int i = 0; i < 50_000; i++) {
            var bytes = generateRandomBytes.apply(i);
            var index = HighCardinalityKeys.hashHighCardinalityKey("session", bytes).intValue();
            if (!lookup.containsKey(index)) {
                lookup.put(index, Lists.newArrayListWithExpectedSize(2));
            }
            lookup.get(index).add(new String(bytes, StandardCharsets.US_ASCII));
        }

        var mostCollisions = -1;
        var indexWithMostCollisions = -1;
        for (var entry : lookup.entrySet()) {
            if (entry.getValue().size() > mostCollisions) {
                indexWithMostCollisions = entry.getKey();
                mostCollisions = entry.getValue().size();
            }
        }

        System.out.println("Index with most collisions: " + indexWithMostCollisions);
        System.out.println("Total nubmer of collisions: " + mostCollisions);
        System.out.println("Strings with collisions:");
        for (var s : lookup.get(indexWithMostCollisions)) {
            System.out.println(s);
        }
    }
}
