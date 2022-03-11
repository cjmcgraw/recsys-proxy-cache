import pytest
import grpclib.client
import os

from .proto import recsys

TARGET = os.environ['RECSYS_PROXY_CACHE_TARGET']
HOST = TARGET.split(':')[0]
PORT = int(TARGET.split(':')[1])


@pytest.fixture
async def predict_stub():
    async with grpclib.client.Channel(HOST, PORT) as channel:
        yield recsys.RecsysProxyCacheStub(channel)
