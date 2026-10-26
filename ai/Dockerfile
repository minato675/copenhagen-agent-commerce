# Python 3.11.16; pin the multi-platform image digest for repeatable base layers.
FROM docker.io/library/python:3.11.16-slim-bookworm@sha256:528257d48c1da0dcecc2e725d1ae34498d60c965f1241e39cd6a85a8859bdf84 AS base

FROM base AS builder
WORKDIR /build
COPY pyproject.toml README.md ./
COPY src/ ./src/
RUN python -m pip install --no-cache-dir --no-compile --prefix=/install '.[server]'

FROM base AS runtime
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

RUN groupadd --gid 10001 fds \
    && useradd --uid 10001 --gid fds --create-home fds \
    && mkdir -p /app/results \
    && chown fds:fds /app/results

WORKDIR /app
COPY --from=builder /install/ /usr/local/
COPY examples/ ./examples/

USER fds:fds
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health/live', timeout=2).close()"]
CMD ["python", "-m", "uvicorn", "copenhagen_fds.api.app:create_app", "--factory", "--host", "0.0.0.0", "--port", "8000", "--no-access-log", "--no-proxy-headers"]
