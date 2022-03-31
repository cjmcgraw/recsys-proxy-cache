import asyncio
import random

from . import utils
from proto import recsys

# empirical determined from bruteforcing farmfingerprint64 with known
# bucket size. Please use the java code to generate hashes with
# ./gradlew generateCollisionHashes
known_colliding_values = [
    "506cf927006f41a390b37ec8029e5d862e04",
    "173b155ff05a403f97a3b3d9e391e182ae248c6",
    "5c3f59bf3cf54349b7554998581d832659e456",
    "06736214eb2842af801d853c6bbd05a0746",
    "9d4e843379ad4adbb7c828c960c3c3cc0",
    "7c23ed5c306b4e48a9275500324713db50",
    "ffd5f4145f744538af6b61e33934db7b",
    "595644dad92546fba56e12cf9a8cf0b1",
    "a960ea0dec7241a0bc84080a6193593fb87543db",
    "a562ab1b44784b69bdcc40c8dc035819542263",
    "41c73e35c5d349418ae2bbab63db23a3e610a672",
    "fe79c4b49981441ba067d152e453101799",
    "ad2248697b1b46cb83fb68c1a5d567855",
    "126ecc611e8a404ea3aa06d11143b3bf",
    "4f6c3493f82f43a98686586d6bc3becadc82f8c",
    "733047b9d12348e18a8f583e5626acd98389b",
    "6eee155d89114387b412b8ec12bfa89ad4f0c",
    "285b6ba0ad1a4b9296b39721fe0a1970271",
    "705f92c8d5954029af33765a3c88bb01db6b9",
    "0099e65004d544ed8fb2c071b4a05e30e94",
    "aac058b3e3c74061b1e05b854057e14a04",
    "27db26d2a6464f3ca5340e76b98fbcbd639787e3",
    "5dee513cd2a34b419a24dc6fbface2a483d70",
    "2c93eb8e8cb44680a1fc8116c36162184903fa6",
    "a65d5dd141fd4076bb1f7c6d8a8f8b47e3de",
    "62742c0b736d498689ea168a07cebbf4",
    "7a88a8187b124529a568b10a4468d3bec6",
    "3ef419b1921044a79b3d8885dcef43c1de04c7ec0",
    "79677925fa1945c695e2df498bedc0b1fa65e5303",
    "a4733dbc227d45918298c0aa9629e102e1",
    "25cfd4c3153a44098d4e0f6b7339f9e5",
    "34777924cb0841d8b2d55b135d49c834a33d789f",
    "2394cc6469db48b0b1027b0e0362c5efa80c2",
    "9ba56fabd61b47199a7dc05368f37a8d",
    "506dca98641a4e5cb1bb338825961d8677aa",
    "e05a9ae5a3ec413fa3a9879c1c1305ffc52f63",
    "acdd2448075f41b9b2010a57515dc0e3e9843ff4",
    "06346d71a71c4e13b38f592093cddd425",
    "39daa005b2fe445d8270ba2125fca99ce20d3f",
    "754db2320de84018930ba7b704509cc51db2f",
    "023d67ab2d754ea09510b1002ad334cbbb",
    "d10b8ffac35e41679c75baf9bd7481448fa2d5d1c",
    "c19a012943344f719a18f3f9d2cdfad2b2de140df",
    "01b21133696f4a159623d5fdcc4ddb0ca8",
    "bfab58e52f2644d9b3c4f3cb56a6416d",
    "5743a1d2c4254649bf740c0c99ecc1c2ab148",
    "fde14cb7471f40479989892dfaaf06061744ba9",
    "ad458876fbc84f55b1613f11c6379e64c401",
    "f580adb6c22446a29d9b2d815225891b7",
    "0dc9214e126a401facbfdab944913b88adac1b7",
    "6a417a94cc6a47f9990a274053608b6933",
    "bd9e02c1661442f29e032c111c97e1585a26",
    "cf564ba8ce914a66941273a7291056c9a2e8",
    "0113ea3234314c56a34d1659dcbb59b083306961",
    "eae7526b7e064503ab64f00a35faf06d",
    "eebc506661a445d8a902858af81fa6da4",
    "5f0ada73bd23403f918d0f580c30bbbe",
    "64633ea4152c4780aa4ef66fd5412713e232f",
    "79118e6c222648d9bb66b5c96f4fc68500",
    "d49cd80688c64a7d9302d08405bd439e",
    "53452593b4f14c4f8c58918033fe9ef8c2a0",
    "165d230beb8a427d9bc56c5bb8e8622fb42eb",
    "a7c37f4d03e740f592393beb8db0ee8920",
    "33fd22d0666849d7a1f82ae7ba695fa81539b1b",
    "13e3f338f0b64b5ca456c79bd303b084b",
    "9bcf1d038d744d28846f7591522ddf39d",
    "983e3bdeba3a498dadb77394bd8ff674843811b",
    "00928294edfd481f80b38ce9e313b42225bf",
    "b2606f6a6a264c7c96ca82a3f91e85ef",
    "2748710aa795492fab566f370b99f2446c",
    "9ec88d0b149c45b79a179543f931b259ef9817e1",
    "389082ae84ca4691812b8697b21b9037b3adc",
    "0a5f7ae8071a48c4b5bb57ce25f6e2d9150f",
    "8538ae7659444aa4a4cafd2a406fa1153d0541"
]


async def test_key_collision_matches_outputs(predict_stub):
    items = utils.get_random_items(random.randint(1, 250))
    context = utils.get_random_context()

    def send_request(session: str):
        context.fields["session"] = recsys.Values(
            values=[session]
        )
        print(context)
        return predict_stub.get_scores(
            model_name="recsys",
            context=context,
            items=items
        )

    prev = await send_request(known_colliding_values[0])
    for v in known_colliding_values[1:]:
        curr = await send_request(v)
        assert prev.scores == curr.scores, f"""
        Expected known colliding keys to return the same scores.
        
        If this fails chances are high cardinality key algorithm
        has changed for some reason.

        expected result = {prev}
        actual result = {curr}
        """
        prev = curr
