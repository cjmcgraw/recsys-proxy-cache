import asyncio
import random

from . import utils


async def test_gets_expected_number_of_scores(predict_stub):
    items = utils.get_random_items(random.randint(1, 250))
    response = await predict_stub.get_scores(
        model_name="recsys",
        context=utils.get_random_context(),
        items=items
    )
    assert len(items) == len(response.scores), f"""
    Expected the number of items we asked for, and the number
    of scores we retrieved to match and be the exact same length.
    
    They were not. There is probably a problem with how the scores
    are being processed!
    
    expected size = {len(items)}
    actual sixe = {len(response.scores)}
        """


async def test_multiple_calls_are_idempotent(predict_stub):
    context = utils.get_random_context(100)
    items = utils.get_random_items(500)
    predict = lambda: predict_stub.get_scores(
        model_name="recsys",
        context=context,
        items=items,
    )

    prev = await predict()
    for _ in range(25):
        curr = await predict()
        assert prev.scores == curr.scores, f"""
        Expected multiple calls with identical context/items
        to result in identical results.
        
        previous={prev}
        current={curr}
        """
        prev = curr


async def test_different_context_yields_different_scores(predict_stub):
    items = utils.get_random_items(500)

    request1 = await predict_stub.get_scores(
        model_name="recsys",
        context=utils.get_random_context(10),
        items=items,
    )

    request2 = await predict_stub.get_scores(
        model_name="recsys",
        context=utils.get_random_context(100),
        items=items
    )

    assert request1.scores != request2.scores, f"""
    Expected request1 to have different scores than request2
    because the contexts are different
    
    request1={request1}
    request2={request2}
    """


async def test_same_itemids_are_idempontent(predict_stub):
    context = utils.get_random_context(100)
    items = utils.get_random_items(random.randint(100, 250))

    def send_new_request(data):
        return predict_stub.get_scores(
            items=data,
            context=context,
            model_name="recsys",
        )

    samples = [random.sample(items, k=25) for _ in range(100)]
    requests = [send_new_request(s) for s in samples]
    responses = await asyncio.gather(*requests, return_exceptions=False)

    all_scores = dict()
    for sample, response in zip(samples, responses):
        for item, score in zip(sample, response.scores):
            all_scores.setdefault(item, set())
            all_scores[item].add(score)

    for item, scores in all_scores.items():
        assert len(scores) == 1, f"""
        Expected all items to have exactly one score, regardless of how
        many times they were called.
        
        item={item}
        scores={scores}
        """
