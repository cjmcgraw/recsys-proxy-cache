FROM python:3.10 as builder
WORKDIR /build
ADD requirements.txt .
RUN --mount=type=cache,target=/root/.cache/pip \
    pip install --cache-dir /root/.cache/pip -r requirements.txt
ADD build_model.py .
RUN python build_model.py

FROM tensorflow/serving:2.8.0 as release
RUN mkdir -p /models/recsys
COPY --from=builder /build/model_dir/* /models/recsys/1
ENV MODEL_NAME recsys
