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

import com.google.common.collect.Maps;
import org.junit.Assert;
import org.junit.Test;

import java.util.UUID;
import recsys_proxy_cache.cache.ScoreCache;

public class ScoreCacheTest {
    @Test
    public void testScoresCacheHandlesKeyAsExpected() throws InterruptedException {
        var context = TestUtils.getRandomContext();
        var scoreCache = ScoreCache.Builder
                .newBuilder()
                .withContext(context)
                .withModelName(UUID.randomUUID().toString())
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
