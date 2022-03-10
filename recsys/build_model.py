import tensorflow as tf
from typing import Dict
import math

import logging as log
import sys

log.basicConfig(
    level=log.DEBUG,
    stream=sys.stdout,
)

log.info(f"tensorflow version: {tf.__version__}")

if __name__ == '__main__':
    valid_context_keys = [
        "country",
        "language",
        "site",
    ]

    item_id = tf.keras.Input(shape=(None, 1), dtype=tf.int64, name="item_id")
    context_values = [
        tf.keras.Input(shape=(None,), dtype=tf.string, name=k)
        for k in valid_context_keys
    ]

    flattened_context_keys = tf.concat(context_values, axis=0)
    context_lengths = tf.strings.length(flattened_context_keys)
    total_context_characters = tf.cast(tf.reduce_sum(context_lengths), tf.int64)

    reshaped_item_id = tf.reshape(item_id, (-1, 1))


    total_lengths = reshaped_item_id + total_context_characters

    multiplied_ids = math.pi * tf.cast(total_lengths, tf.float64)
    scores = tf.reshape(multiplied_ids, (-1,))

    model = tf.keras.Model(
        inputs={
            **{"item_id": item_id},
            **dict(zip(valid_context_keys, context_values))
        },
        outputs=scores
    )

    input_signature = {
        "item_id": tf.TensorSpec(shape=(None, 1), dtype=tf.int64, name="item_id"),
        **{
            k: tf.TensorSpec(shape=(None,), dtype=tf.string, name=k)
            for k in valid_context_keys
        }
    }

    @tf.function(input_signature=[input_signature])
    def serving_fn(inputs: Dict[str, tf.Tensor]):
        return {"scores": model(inputs)}

    model.save(
        "model_dir",
        signatures={
            tf.saved_model.DEFAULT_SERVING_SIGNATURE_DEF_KEY: serving_fn,
            tf.saved_model.PREDICT_METHOD_NAME: serving_fn,
        }
    )
