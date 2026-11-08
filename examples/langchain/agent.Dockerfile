# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
FROM python:3.13-slim
WORKDIR /app
COPY examples/langchain/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt
COPY debug/agent-mimic.py /app/agent_mimic.py
COPY examples/langchain/agent.py /app/agent.py
COPY examples/langchain/trigger_agent.py examples/langchain/hotfolder-trigger.profile.json /app/
COPY LICENSE NOTICE.md /usr/share/licenses/olo-toolgate/
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
ENTRYPOINT ["python", "/app/agent.py"]
