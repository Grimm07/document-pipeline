FROM nvidia/cuda:12.6.3-runtime-ubuntu24.04

ENV DEBIAN_FRONTEND=noninteractive
ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        python3 python3-pip python3-venv \
        libgl1 libglib2.0-0 libgomp1 && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

RUN userdel -r ubuntu 2>/dev/null || true && \
    useradd -m -u 1000 mlservice

WORKDIR /app

# Install dependencies first (cached layer)
COPY pyproject.toml .
RUN python3 -m pip install --no-cache-dir --break-system-packages . && \
    python3 -m pip cache purge 2>/dev/null || true

# Copy application code
COPY app/ app/

# Create model cache directory
RUN mkdir -p /models && chown mlservice:mlservice /models

USER mlservice

ENV HF_HOME=/models
EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=10s --start-period=120s --retries=3 \
    CMD python3 -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/health')" || exit 1

CMD ["python3", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
