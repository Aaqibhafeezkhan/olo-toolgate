# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
FROM python:3.13-slim@sha256:e48f59cdba69a3cbd9ed2cf0f348d90d45a8d350d98134cf1070bc106ee9ed90
WORKDIR /builder
COPY debug/profiled/qlora-build-requirements.txt ./requirements.txt
RUN python -m pip install --no-cache-dir --index-url https://download.pytorch.org/whl/cpu torch==2.15.0+cpu \
    && python -m pip install --no-cache-dir -r requirements.txt \
    && python -m pip check
ARG LLAMA_CPP_REVISION=0a76757469ea94ad5f88d14e18cc7370b9931f99
RUN python -c "import io,tarfile,urllib.request; data=urllib.request.urlopen('https://codeload.github.com/ggml-org/llama.cpp/tar.gz/${LLAMA_CPP_REVISION}').read(); tarfile.open(fileobj=io.BytesIO(data)).extractall('/opt',filter='data')"
ENV OLO_GGUF_CONVERTER=/opt/llama.cpp-${LLAMA_CPP_REVISION}/convert_hf_to_gguf.py
COPY tools/models/prepare_qlora.py ./prepare_qlora.py
ENV HF_HUB_DISABLE_TELEMETRY=1 HF_HUB_DISABLE_PROGRESS_BARS=1 TOKENIZERS_PARALLELISM=false
ENTRYPOINT ["python", "/builder/prepare_qlora.py"]
