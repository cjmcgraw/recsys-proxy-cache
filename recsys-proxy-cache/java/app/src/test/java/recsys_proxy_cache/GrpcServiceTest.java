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
import io.grpc.stub.StreamObserver;

import org.junit.Before;
import org.junit.Test;
import org.mockito.*;
import org.mockito.internal.creation.MockSettingsImpl;
import recsys_proxy_cache.cache.ScoreCache;
import recsys_proxy_cache.protos.*;

import java.util.Random;

public class GrpcServiceTest {
    private static final Random rand = new Random(1L);

    private ScoreCache scoreCacheMock;
    private RecsysProxy recsysProxyMock;
    private StreamObserver<ScoreResponse> streamObserverMock;
    private GrpcService systemUnderTest;

    @Before
    public void setupMockedOutGrpcService() throws Exception {
        var cacheBuilderMock = Mockito.mock(
                ScoreCache.Builder.class,
                new MockSettingsImpl<>().defaultAnswer(Mockito.RETURNS_SELF)
        );
        var recsysBuilderMock = Mockito.mock(
                RecsysProxy.Builder.class,
                new MockSettingsImpl<>().defaultAnswer(Mockito.RETURNS_SELF)
        );

        scoreCacheMock = Mockito.mock(ScoreCache.class);
        recsysProxyMock = Mockito.mock(RecsysProxy.class);

        Mockito.when(cacheBuilderMock.build())
                .thenReturn(scoreCacheMock);

        Mockito.when(recsysBuilderMock.build())
                .thenReturn(recsysProxyMock);

        streamObserverMock = Mockito.mock(StreamObserver.class);
        systemUnderTest = new GrpcService(
                () -> cacheBuilderMock,
                () -> recsysBuilderMock
        );
    }

    @Test
    public void TestCacheEmptyAllScoresAreAdded() throws Exception {
        var testData = TestUtils.generateRandomTestData(10, 0);

        Mockito.when(recsysProxyMock.score(Mockito.any()))
                .thenReturn(testData.proxyRecords);
        Mockito.when(scoreCacheMock.getScores(Mockito.any()))
                .thenReturn(testData.cacheRecords);

        systemUnderTest.getScores(testData.request, streamObserverMock);

        Mockito.verify(streamObserverMock).onNext(testData.expected);
        Mockito.verify(streamObserverMock).onCompleted();
        Mockito.verify(scoreCacheMock).setScores(testData.proxyRecords);
    }

    @Test
    public void TestCacheFullRecsysProxyEmpty() throws Exception {
        var testData = TestUtils.generateRandomTestData(0, 10);

        Mockito.when(recsysProxyMock.score(Mockito.any()))
                .thenThrow(new AssertionError("expected RecsysProxy not to be called"));
        Mockito.when(scoreCacheMock.getScores(Mockito.any()))
                .thenReturn(testData.cacheRecords);

        systemUnderTest.getScores(testData.request, streamObserverMock);

        Mockito.verify(streamObserverMock).onNext(testData.expected);
        Mockito.verify(streamObserverMock).onCompleted();
    }

    @Test
    public void TestCacheHalfFullRecsysProxyWithOtherHalf() throws Exception {
        var testData = TestUtils.generateRandomTestData(10, 10);

        Mockito.when(recsysProxyMock.score(Mockito.any()))
                .thenReturn(testData.proxyRecords);
        Mockito.when(scoreCacheMock.getScores(Mockito.any()))
                .thenReturn(testData.cacheRecords);

        systemUnderTest.getScores(testData.request, streamObserverMock);

        Mockito.verify(streamObserverMock).onNext(testData.expected);
        Mockito.verify(streamObserverMock).onCompleted();
        Mockito.verify(scoreCacheMock).setScores(testData.proxyRecords);
    }
    @Test
    public void TestHandlesInvalidArgumentsGracefully() throws Exception {
        var testData = TestUtils.generateRandomTestData(0, 0);

        Mockito.when(recsysProxyMock.score(Mockito.any()))
                .thenThrow(new AssertionError("assert proxy not called"));
        Mockito.when(scoreCacheMock.getScores(Mockito.any()))
                .thenThrow(new AssertionError("assert mock not called"));

        systemUnderTest.getScores(testData.request, streamObserverMock);

        Mockito.verify(streamObserverMock).onError(Mockito.any());
    }

    @Test
    public void TestHandlesUnexpectedProxyException() throws Exception {
        var testData = TestUtils.generateRandomTestData(10, 10);

        Mockito.when(recsysProxyMock.score(Mockito.any()))
                .thenThrow(new RuntimeException("this exception should be handled"));
        Mockito.when(scoreCacheMock.getScores(Mockito.any()))
                .thenReturn(testData.cacheRecords);

        systemUnderTest.getScores(testData.request, streamObserverMock);

        Mockito.verify(streamObserverMock).onError(Mockito.any());
    }

    @Test
    public void TestHandlesUnexpectedCacheException() throws Exception {
        var testData = TestUtils.generateRandomTestData(10, 10);

        Mockito.when(recsysProxyMock.score(Mockito.any()))
                .thenReturn(testData.proxyRecords);
        Mockito.when(scoreCacheMock.getScores(Mockito.any()))
                .thenThrow(new RuntimeException("this exception should be handled"));

        systemUnderTest.getScores(testData.request, streamObserverMock);

        Mockito.verify(streamObserverMock).onError(Mockito.any());
    }

    @Test
    public void testUnexpectedEmptyResponseFromProxyYieldsException() throws Exception {
        var testData = TestUtils.generateRandomTestData(10, 10);

        Mockito.when(recsysProxyMock.score(Mockito.any()))
                .thenReturn(Maps.newHashMap());
        Mockito.when(scoreCacheMock.getScores(Mockito.any()))
                .thenReturn(testData.cacheRecords);

        systemUnderTest.getScores(testData.request, streamObserverMock);

        // what do we want this to do when we get there?
        throw new AssertionError("determine what to do in error case");
    }
}
