from uuid import uuid4 as uuid
import random
import grpc

from .proto import recsys
from . import utils


def test_gets_expected_number_of_scores():
    stub = utils.get_stub()

    items = utils.get_random_items(random.randint(1, 250))
    response = stub.get_scores(
        model_name="recsys",
        context=utils.get_random_context(),
        items=utils.get_random_items()
    )
    stub.channel.close()
