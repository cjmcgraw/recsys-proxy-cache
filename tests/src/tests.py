from uuid import uuid4 as uuid
import asyncio
import random
import os

from proto.recsys import RecsysProxyCacheStub, Context, Item, Field, ScoreResponse
from grpclib.client import Channel

RECSYS_PROXY_HOST = os.environ.get("RECSYS_PROXY_HOST", "recsys_proxy_cache")


async def main():
    channel = Channel(host=RECSYS_PROXY_HOST, port=50051)
    service = RecsysProxyCacheStub(channel)

    context = Context()
    items = [
        Item(fields=[Field(name="first", value=uuid().hex)])
        for _ in range(random.randint(1, 10))
    ]

    response: ScoreResponse = await service.get_scores(
        context=context,
        items=items
    )
    print(response)
    channel.close()

if __name__ == '__main__':
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    loop.run_until_complete(main())
