import os
from uuid import uuid4

import pytest

from app.config import Settings
from app.semantic.worker import worker_lock

pytestmark = pytest.mark.skipif(
    os.getenv("CATGAL_AI_SEMANTIC_TEST") != "1", reason="Explicit live semantic MySQL mutex opt-in"
)


@pytest.mark.asyncio
async def test_real_mysql_worker_mutex_uses_connection_ownership_and_releases():
    settings = Settings(_env_file=None).model_copy(
        update={"semantic_index_name": "catgal_vector_mutex_qa_" + uuid4().hex}
    )
    async with worker_lock(settings) as first:
        assert first is not None
        await first()
        async with worker_lock(settings) as second:
            assert second is None
        await first()
    async with worker_lock(settings) as after_release:
        assert after_release is not None
        await after_release()
