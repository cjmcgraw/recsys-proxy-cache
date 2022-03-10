package recsys_proxy_cache;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import recsys_proxy_cache.protos.*;

import java.util.*;

public class TestUtils {
    private static final Random rand = new Random(1L);

    static class TestData {
        public List<Long> items;
        public List<Double> scores;
        public Map<Long, Double> proxyRecords;
        public Map<Long, Double> cacheRecords;
        public ScoreRequest request;
        public ScoreResponse expected;
    }

    static TestData generateRandomTestData(int recordsForProxy, int recordsForCache) {
        var request = TestUtils.getTestScoreRequest(recordsForProxy + recordsForCache);
        var items = request.getItemsList();
        var itemsToScores = Maps.<Long, Double>newHashMapWithExpectedSize(request.getItemsCount());
        for (var item : items) {
            itemsToScores.put(item, rand.nextDouble());
        }

        var scores = Lists.<Double>newArrayListWithExpectedSize(request.getItemsCount());
        for (var item : items) {
            scores.add(itemsToScores.get(item));
        }

        var expected = ScoreResponse
                .newBuilder()
                .addAllScores(scores)
                .build();

        var proxyRecords = Maps.<Long, Double>newHashMapWithExpectedSize(recordsForProxy);
        var cacheRecords = Maps.<Long, Double>newHashMapWithExpectedSize(recordsForCache);
        for (var entry : itemsToScores.entrySet()) {
            if (proxyRecords.size() < recordsForProxy) {
                proxyRecords.put(entry.getKey(), entry.getValue());
            } else if(cacheRecords.size() < recordsForCache) {
                cacheRecords.put(entry.getKey(), entry.getValue());
            }
        }

        var record = new TestData();
        record.items = items;
        record.scores = scores;
        record.proxyRecords = proxyRecords;
        record.cacheRecords = cacheRecords;
        record.request = request;
        record.expected = expected;
        return record;
    }

    static Context getRandomContext() {
        var context = Context.newBuilder();
        for (int i = 0; i < 10; i++) {
            context.putFields(
                    UUID.randomUUID().toString(),
                    getRandomValues(3)
            );
        }

        return context.build();
    }


    static ScoreRequest getTestScoreRequest(int itemsToAdd) {
        return getTestScoreRequest(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                itemsToAdd
        );
    }
    static ScoreRequest getTestScoreRequest(String modelName, String modelVersion, int itemsToAdd) {
        return getTestScoreRequest(modelName, modelVersion, getRandomContext(), itemsToAdd);
    }

    static ScoreRequest getTestScoreRequest(String modelName, String modelVersion, Context context, int itemsToAdd) {
        return ScoreRequest
                .newBuilder()
                .setModelName(modelName)
                .setContext(context)
                .addAllItems(getRandomItems(itemsToAdd))
                .build();
    }

    static Map<Long, Double> getRandomScores(int n) {
        var scores = Maps.<Long, Double>newHashMapWithExpectedSize(n);
        var randomItems = getRandomItems(n);
        for (var item: randomItems) {
           scores.put(item, rand.nextDouble());
        }
        return scores;
    }

    static List<Long> getRandomItems(int n) {
        return rand.longs(n)
                .boxed()
                .toList();
    }

    private static Values getRandomValues(int numberOfRandomFields) {
        var values = Values.newBuilder();
        for (int i = numberOfRandomFields; i > 0; i--) {
            values.addValues(UUID.randomUUID().toString());
        }
        return values.build();
    }

}
