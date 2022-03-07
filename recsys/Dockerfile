FROM python:3.10 as builder
WORKDIR /build
ADD requirements.txt .
ADD build_model.py .
RUN --mount=type=cache,target=/root/.cache/pip \
    pip install --cache-dir /root.cache/pip -r requirements.txt
RUN python build_model.py

FROM tensorflow/serving:2.8.0 as release
RUN mkdir -p /models/my-recsys
COPY --from=builder /build/model_dir/* /models/my-recsys/1
ENV MODEL_NAME my-recsys
