package recsys_proxy_cache;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFutureTask;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.*;
import org.mockito.internal.creation.MockSettingsImpl;
import org.tensorflow.framework.TensorProto;
import tensorflow.serving.Predict.PredictResponse;
import tensorflow.serving.PredictionServiceGrpc.PredictionServiceFutureStub;

public class RecsysProxyTest {
    private PredictionServiceFutureStub mockStub;
    private RecsysProxy systemUnderTest;

    @Before
    public void setupMockedOutStub() {
        mockStub = Mockito.mock(
                PredictionServiceFutureStub.class,
                new MockSettingsImpl<>().defaultAnswer(Mockito.RETURNS_SELF)
        );
        systemUnderTest = RecsysProxy.Builder
                .newBuilder()
                .withModelName(UUID.randomUUID().toString())
                .withContext(TestUtils.getRandomContext())
                .withStub(mockStub)
                .build();
    }

    @Test
    public void testPredictReturnsSuccessfully() throws Exception {
        var expected = TestUtils.getRandomScores(25);
        var response = PredictResponse.newBuilder()
                .putOutputs(
                        "scores",
                        TensorProto
                                .newBuilder()
                                .addAllDoubleVal(expected.values())
                                .build()
                )
                .build();

        Mockito.when(mockStub.predict(Mockito.any()))
                .thenReturn(Futures.immediateFuture(response));

        var actual = systemUnderTest.score(expected.keySet());
        Assert.assertEquals(expected, actual);
    }

    @Test
    public void testPredictThrowsExceptionOnTimeout() throws Exception {
        var expected = TestUtils.getRandomScores(10);

        var response = PredictResponse.newBuilder()
                .putOutputs(
                        "scores",
                        TensorProto
                                .newBuilder()
                                .addAllDoubleVal(expected.values())
                                .build()
                )
                .build();

        Mockito.when(mockStub.predict(Mockito.any())).thenReturn(
                ListenableFutureTask.create(() -> {
                    Thread.sleep(300);
                    return response;
                })
        );
        Assert.assertThrows(
                TimeoutException.class,
                () -> systemUnderTest.score(expected.keySet())
        );
    }
}
