FROM ghcr.io/astral-sh/uv:0.13.6 AS uv
FROM python:3.12-slim AS base

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy

WORKDIR /app

COPY --from=uv /uv /uvx /bin/
COPY pyproject.toml uv.lock ./

FROM base AS test
RUN --mount=type=cache,target=/root/.cache/uv uv sync --frozen
COPY app ./app
COPY tests ./tests
RUN uv run --no-sync python -m pytest && uv run --no-sync ruff check app tests

FROM base AS runtime
RUN groupadd --system catgal && useradd --system --gid catgal --home-dir /app catgal
RUN --mount=type=cache,target=/root/.cache/uv uv sync --frozen --no-dev
COPY app ./app
RUN chown -R catgal:catgal /app

USER catgal
EXPOSE 8090

CMD ["uv", "run", "--no-sync", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090"]
