# 阅读说明
# 智能体可调用的固定游戏工具集合，Java 负责权威业务查询与公开性验证。
# 模型给出的调用参数仍按工具协议和预算校验，推荐结果经过服务器复核。

import json
import logging
import time
from dataclasses import dataclass
from uuid import uuid4

import anyio
import httpx
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from app.models import to_camel
from app.persistence.contracts import PersistenceError
from app.security import compute_signature

logger = logging.getLogger(__name__)


class ToolArguments(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="forbid")


class SearchGames(ToolArguments):
    keyword: str | None = Field(default=None, max_length=200)
    tag_id: int | None = Field(default=None, gt=0, le=2147483647)
    company_id: int | None = Field(default=None, gt=0, lt=2**63)
    release_year: int | None = Field(default=None, ge=1, le=9998)
    language: str | None = Field(default=None, max_length=32)
    platform: str | None = Field(default=None, max_length=32)
    resource_type: str | None = Field(default=None, max_length=32)
    limit: int = Field(default=5, ge=1, le=10)


class GameDetail(ToolArguments):
    game_id: int = Field(gt=0, lt=2**63)


class MyInterests(ToolArguments):
    limit: int = Field(default=5, ge=1, le=10)


class GameData(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True, alias_generator=to_camel)
    id: str = Field(pattern=r"^[1-9][0-9]{0,18}$")
    name: str = Field(min_length=1, max_length=200)
    introduction: str | None = Field(default=None, max_length=4000)
    tags: list[str] = Field(default_factory=list, max_length=50)
    companies: list[str] = Field(default_factory=list, max_length=30)
    release_date: str | None = None
    rating: float | None = None


@dataclass
class RecommendationBatch:
    items: list[dict]


DEFINITIONS = {
    "search_games": (SearchGames, "search-games", "查询本站已发布游戏，使用用户明确的作品条件。"),
    "get_game_detail": (GameDetail, "game-detail", "按真实游戏ID读取本站已发布作品资料。"),
    "get_my_interests": (MyInterests, "my-interests", "读取当前已授权用户的收藏与评价偏好摘要。"),
}


class GameTools:
    def __init__(
        self,
        settings,
        user_id,
        use_interests=False,
        transport=None,
        semantic=None,
        use_semantic_search=False,
    ):
        self.settings = settings
        self.user_id = user_id
        self.allowed = {"search_games", "get_game_detail"}
        if use_interests:
            self.allowed.add("get_my_interests")
        self.calls = 0
        self.candidates = {}
        self.semantic_candidates = {}
        self.semantic = semantic
        self.use_semantic_search = use_semantic_search
        self.client = httpx.AsyncClient(
            base_url=settings.java_tools_base_url,
            timeout=settings.tool_timeout_seconds,
            follow_redirects=False,
            trust_env=False,
            transport=transport,
        )

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        with anyio.CancelScope(shield=True):
            with anyio.move_on_after(2):
                await self.client.aclose()

    def schemas(self):
        return [
            {
                "type": "function",
                "function": {
                    "name": name,
                    "description": DEFINITIONS[name][2],
                    "parameters": DEFINITIONS[name][0].model_json_schema(by_alias=True),
                },
            }
            for name in sorted(self.allowed)
        ]

    async def request(self, operation, arguments):
        path = "/internal/ai/tools/" + operation
        body = json.dumps(
            {**arguments, "userId": self.user_id}, ensure_ascii=False, separators=(",", ":")
        ).encode()
        timestamp, nonce = str(int(time.time())), uuid4().hex
        secret = self.settings.hmac_secret_value
        if not secret:
            raise PersistenceError("GAME_TOOLS_UNAVAILABLE", "游戏查询服务未配置", 503)
        signature = compute_signature(secret, timestamp, nonce, "POST", path, body)
        async with self.client.stream(
            "POST",
            path,
            content=body,
            headers={
                "Content-Type": "application/json",
                "X-Catgal-Timestamp": timestamp,
                "X-Catgal-Nonce": nonce,
                "X-Catgal-Signature": signature,
            },
        ) as response:
            response.raise_for_status()
            parts, size = [], 0
            async for chunk in response.aiter_bytes():
                size += len(chunk)
                if size > 131072:
                    raise ValueError("Tool response exceeds limit")
                parts.append(chunk)
        result = json.loads(b"".join(parts))
        if result.get("code") != 200 or not isinstance(result.get("data"), dict):
            raise ValueError("Invalid tool response")
        return result["data"]

    def game(self, value):
        game = GameData.model_validate(value)
        if int(game.id) >= 2**63:
            raise ValueError("Invalid game ID")
        return game.model_dump(by_alias=True)

    async def execute(self, name, arguments):
        if self.calls >= self.settings.max_tool_calls:
            raise PersistenceError("TOOL_BUDGET_EXCEEDED", "本次查询次数已达上限")
        self.calls += 1
        if name not in self.allowed:
            return {"error": "TOOL_NOT_ALLOWED"}
        schema, operation, _description = DEFINITIONS[name]
        try:
            args = schema.model_validate(arguments).model_dump(by_alias=True, exclude_none=True)
            data = None
            if name == "search_games" and self.use_semantic_search and self.semantic is not None:
                data = await self.semantic.search(args, self.request)
            if data is None:
                data = await self.request(operation, args)
            if name == "search_games":
                games = [self.game(game) for game in data["items"][: args["limit"]]]
                self.candidates = {game["id"]: game for game in games}
                versions = data.get("semanticVersions", {})
                filters = data.get("semanticFilters", {})
                self.semantic_candidates = {
                    game["id"]: (versions[game["id"]], filters)
                    for game in games
                    if game["id"] in versions
                }
                return {"items": games, "hasMore": bool(data.get("hasMore"))}
            if name == "get_game_detail":
                game = self.game(data)
                if game["id"] != str(args["gameId"]):
                    raise ValueError("Mismatched tool identity")
                self.candidates[game["id"]] = game
                return game
            favorites = [self.game(game) for game in data.get("favorites", [])[: args["limit"]]]
            reviews = [
                {
                    "game": self.game(row["game"]),
                    "score": row.get("score"),
                    "recommendLevel": row.get("recommendLevel"),
                    "playStatus": row.get("playStatus"),
                }
                for row in data.get("reviews", [])[: args["limit"]]
            ]
            return {
                "favorites": favorites,
                "reviews": reviews,
                "sampled": bool(data.get("sampled")),
                "sourceStatus": data.get("sourceStatus", "database_fallback"),
            }
        except (httpx.HTTPError, ValidationError, ValueError, KeyError, TypeError) as exc:
            logger.warning("Game tool unavailable: tool=%s type=%s", name, type(exc).__name__)
            return {"error": "GAME_TOOL_UNAVAILABLE", "hint": "请说明查询暂不可用或调整条件。"}

    async def recommendations(self):
        result = []
        for game_id in list(self.candidates)[:5]:
            try:
                # Revalidate publication state after the model has finished answering.
                if game_id in self.semantic_candidates:
                    version, filters = self.semantic_candidates[game_id]
                    data = await self.request(
                        "validate-games",
                        {
                            "candidates": [{"gameId": int(game_id), "version": version}],
                            "limit": 1,
                            **filters,
                        },
                    )
                    if not data.get("items"):
                        continue
                    game = self.game(data["items"][0])
                else:
                    game = self.game(await self.request("game-detail", {"gameId": int(game_id)}))
                if game["id"] != game_id:
                    continue
                result.append(
                    {
                        "id": game_id,
                        "name": game["name"],
                        "reason": "本站检索结果，可查看作品详情核对。",
                    }
                )
            except (httpx.HTTPError, ValueError, KeyError, TypeError):
                continue
        return result
