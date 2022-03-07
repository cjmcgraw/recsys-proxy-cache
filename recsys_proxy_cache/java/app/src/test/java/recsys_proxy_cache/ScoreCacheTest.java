package recsys_proxy_cache;

import com.google.common.collect.Maps;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.*;
import org.mockito.internal.creation.MockSettingsImpl;
import recsys_proxy_cache.protos.*;

import java.util.UUID;

public class ScoreCacheTest {
    @Test
    public void testScoresCacheHandlesKeyAsExpected() throws InterruptedException {
        var context = TestUtils.getRandomContext();
        var scoreCache = ScoreCache.Builder
                .newBuilder()
                .withContext(context)
                .withModelName(UUID.randomUUID().toString())
                .withModelVersion(UUID.randomUUID().toString())
                .build();

        var randomScores = TestUtils.getRandomScores(5);
        var initialGet = scoreCache.getScores(randomScores.keySet());
        Assert.assertEquals(initialGet, Maps.newHashMap());
        scoreCache.setScores(randomScores);
        // sleep for a bit to allow background task to finish
        Thread.sleep(10L);
        var data = scoreCache.getScores(randomScores.keySet());
        Assert.assertEquals(randomScores, data);
    }
}
