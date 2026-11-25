# 阅读说明
# 私有记忆 ES 向量索引，近邻查询同时限制用户、机器人及授权版本。

from app.semantic.client import request_json, vector
from app.semantic.index import VectorIndex


class PrivateMemoryIndex(VectorIndex):
    identity_field = "memory_id"

    def __init__(self, settings, transport=None):
        super().__init__(settings, transport, index_name=settings.memory_vector_index_name)

    def metadata(self):
        return {
            **super().metadata(),
            "corpus": "private_confirmed_memories",
            "pipeline": "memory-private-v1",
        }

    def mapping_properties(self):
        properties = {
            name: {"type": "long"}
            for name in (
                "memory_id",
                "user_id",
                "bot_id",
                "version",
                "source_revision",
                "consent_revision",
            )
        }
        properties.update(
            {name: {"type": "keyword"} for name in ("source_text_hash", "input_text_hash", "model")}
        )
        properties["deleted"] = {"type": "boolean"}
        properties["vector"] = {
            "type": "dense_vector",
            "dims": self.settings.embedding_dimensions,
            "index": True,
            "similarity": "cosine",
        }
        return properties

    def validate_existing(self, document, current):
        super().validate_existing(document, current)
        if any(
            current.get(key) != document.get(key)
            for key in ("memory_id", "user_id", "bot_id", "source_revision", "consent_revision")
        ):
            raise ValueError("Private vector source mismatch")

    async def search(self, values, user_id, bot_id, consent_revision):
        filters = [
            {"term": {key: value}}
            for key, value in (
                ("deleted", False),
                ("user_id", user_id),
                ("bot_id", bot_id),
                ("consent_revision", consent_revision),
                ("model", self.settings.embedding_model),
            )
        ]
        response = await request_json(
            self.client,
            "POST",
            self.name + "/_search",
            json={
                "size": 50,
                "_source": [
                    "memory_id",
                    "user_id",
                    "bot_id",
                    "version",
                    "source_revision",
                    "consent_revision",
                    "source_text_hash",
                ],
                "knn": {
                    "field": "vector",
                    "query_vector": vector(values, self.settings.embedding_dimensions),
                    "k": 50,
                    "num_candidates": 100,
                    "filter": {"bool": {"filter": filters}},
                },
            },
        )
        if response.get("timed_out") or response.get("_shards", {}).get("failed", 0):
            raise ValueError("Incomplete private vector search")
        return [hit["_source"] for hit in response["hits"]["hits"]]
