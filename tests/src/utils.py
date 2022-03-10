from uuid import uuid4 as uuid
import random
import os

import grpc
from .proto import recsys


TARGET = os.environ['RECSYS_PROXY_CACHE_TARGET']


def get_random_items(n: int = 10) -> list[int]:
    return [
        random.randint(1, int(1e10))
        for _ in range(n)
    ]


def get_random_context(fields: int = 10) -> recsys.Context:
    random_values = lambda: recsys.Values([uuid().hex for _ in range(fields)])
    return recsys.Context(
        fields={
            "country": random_values(),
            "site": random_values(),
            "language": random_values()
        }
    )


def get_stub() -> recsys.RecsysProxyCacheStub:
    channel = grpc.insecure_channel(TARGET)
    return recsys.RecsysProxyCacheStub(channel)
