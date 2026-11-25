# 阅读说明
# 游戏 ES 向量索引结构、模型兼容性、文档版本和近邻检索。

import httpx

from app.semantic.client import request_json, vector


class VectorIndex:
    identity_field = "game_id"

    def __init__(self, settings, transport=None, *, index_name=None):
        self.settings = settings
        self.name = index_name or settings.semantic_index_name
        password = settings.semantic_es_password
        auth = (
            (settings.semantic_es_username, password.get_secret_value() if password else "")
            if settings.semantic_es_username
            else None
        )
        self.client = httpx.AsyncClient(
            base_url=settings.semantic_es_url.rstrip("/") + "/",
            auth=auth,
            timeout=5,
            trust_env=False,
            follow_redirects=False,
            transport=transport,
        )

    def metadata(self):
        return {
            "corpus": "public_games",
            "schema_version": 1,
            "pipeline": "catalog-compact-v1",
            "model": self.settings.embedding_model,
            "dimensions": self.settings.embedding_dimensions,
        }

    async def compatible(self):
        data = await request_json(self.client, "GET", self.name + "/_mapping")
        mappings = data.get(self.name, {}).get("mappings", {})
        field = mappings.get("properties", {}).get("vector", {})
        if (
            mappings.get("_meta") != self.metadata()
            or field.get("type") != "dense_vector"
            or field.get("dims") != self.settings.embedding_dimensions
            or field.get("index") is not True
            or field.get("similarity") != "cosine"
        ):
            raise ValueError("Vector index metadata mismatch")

    def mapping_properties(self):
        properties = {
            name: {"type": "keyword"}
            for name in (
                "source_text_hash",
                "input_text_hash",
                "model",
                "languages",
                "platforms",
                "resource_types",
            )
        }
        properties.update(
            {name: {"type": "long"} for name in ("game_id", "version", "tag_ids", "company_ids")}
        )
        return properties

    async def initialize(self):
        try:
            await self.compatible()
            return {"created": False}
        except httpx.HTTPStatusError as exc:
            if exc.response.status_code != 404:
                raise
        properties = self.mapping_properties()
        properties.update(
            {
                "deleted": {"type": "boolean"},
                "release_year": {"type": "integer"},
                "vector": {
                    "type": "dense_vector",
                    "dims": self.settings.embedding_dimensions,
                    "index": True,
                    "similarity": "cosine",
                },
            }
        )
        try:
            await request_json(
                self.client,
                "PUT",
                self.name,
                json={
                    "settings": {"number_of_shards": 1, "number_of_replicas": 0},
                    "mappings": {
                        "dynamic": "strict",
                        "_meta": self.metadata(),
                        "properties": properties,
                    },
                },
            )
        except httpx.HTTPStatusError as exc:
            if exc.response.status_code != 400:
                raise
            await self.compatible()
            return {"created": False}
        return {"created": True}

    async def document(self, game_id):
        try:
            data = await request_json(self.client, "GET", f"{self.name}/_doc/{game_id}")
            return data.get("_source")
        except httpx.HTTPStatusError as exc:
            if exc.response.status_code == 404:
                return None
            raise

    async def write(self, document):
        try:
            await request_json(
                self.client,
                "PUT",
                f"{self.name}/_doc/{document[self.identity_field]}",
                params={"version": document["version"], "version_type": "external"},
                json=document,
            )
        except httpx.HTTPStatusError as exc:
            if exc.response.status_code != 409:
                raise
            if (
                exc.response.json().get("error", {}).get("type")
                != "version_conflict_engine_exception"
            ):
                raise
            current = await self.document(document[self.identity_field])
            self.validate_existing(document, current)

    def validate_existing(self, document, current):
        if current is None or int(current.get("version", 0)) != document["version"]:
            raise ValueError("Vector version recovery mismatch")
        if any(
            current.get(key) != document.get(key)
            for key in ("source_text_hash", "input_text_hash", "deleted", "model")
        ):
            raise ValueError("Equal vector version has different content")
        if not document["deleted"]:
            vector(current.get("vector"), self.settings.embedding_dimensions)

    async def search(self, query_vector, arguments):
        filters = [{"term": {"deleted": False}}, {"term": {"model": self.settings.embedding_model}}]
        for incoming, stored in (
            ("tagId", "tag_ids"),
            ("companyId", "company_ids"),
            ("releaseYear", "release_year"),
            ("language", "languages"),
            ("platform", "platforms"),
            ("resourceType", "resource_types"),
        ):
            if arguments.get(incoming) is not None:
                filters.append({"term": {stored: arguments[incoming]}})
        data = await request_json(
            self.client,
            "POST",
            self.name + "/_search",
            json={
                "size": 50,
                "_source": ["game_id", "version"],
                "knn": {
                    "field": "vector",
                    "query_vector": vector(query_vector, self.settings.embedding_dimensions),
                    "k": 50,
                    "num_candidates": 100,
                    "filter": {"bool": {"filter": filters}},
                },
            },
        )
        if data.get("timed_out") or data.get("_shards", {}).get("failed", 0):
            raise ValueError("Incomplete vector search")
        hits = []
        for hit in data["hits"]["hits"]:
            source = hit["_source"]
            game_id, version = int(source["game_id"]), int(source["version"])
            if not 0 < game_id < 2**63 or not 0 < version < 2**63:
                raise ValueError("Invalid vector identity")
            hits.append({"gameId": game_id, "version": version})
        return hits

    async def aclose(self):
        await self.client.aclose()
