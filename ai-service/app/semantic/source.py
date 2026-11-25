# 阅读说明
# 将 Java 导出的游戏字段整理成稳定 embedding 输入和可校验的来源摘要。

import hashlib
import json

from pydantic import BaseModel, ConfigDict, Field

from app.models import to_camel


class PublicGame(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="forbid")
    id: str = Field(pattern=r"^[1-9][0-9]{0,18}$")
    name: str = Field(min_length=1, max_length=200)
    introduction: str | None = Field(default=None, max_length=6000)
    release_date: str | None = None
    tag_ids: list[str] = Field(default_factory=list, max_length=1000)
    tags: list[str] = Field(default_factory=list, max_length=20)
    company_ids: list[str] = Field(default_factory=list, max_length=1000)
    companies: list[str] = Field(default_factory=list, max_length=20)
    languages: list[str] = Field(default_factory=list, max_length=100)
    platforms: list[str] = Field(default_factory=list, max_length=100)
    resource_types: list[str] = Field(default_factory=list, max_length=100)


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def document_source(item):
    game_id, version = int(item["gameId"]), int(item["version"])
    if not 0 < game_id < 2**63 or not 0 < version < 2**63 or type(item["deleted"]) is not bool:
        raise ValueError("Invalid catalog identity")
    base = {"game_id": game_id, "version": version, "deleted": item["deleted"]}
    if item["deleted"]:
        if item.get("game") is not None:
            raise ValueError("Tombstone contains public body")
        return base, None
    game = PublicGame.model_validate(item["game"])
    if int(game.id) != game_id:
        raise ValueError("Catalog game identity mismatch")
    canonical = json.dumps(game.model_dump(by_alias=True), ensure_ascii=False, sort_keys=True)
    prefix = "\n".join(
        (
            "名称 " + game.name,
            "标签 " + "、".join(game.tags)[:400],
            "会社 " + "、".join(game.companies)[:400],
            "发售 " + (game.release_date or ""),
            "语言 " + "、".join(game.languages)[:80],
            "平台 " + "、".join(game.platforms)[:80],
            "资源类型 " + "、".join(game.resource_types)[:80],
        )
    )
    text = (prefix + "\n介绍 " + (game.introduction or ""))[:2000]
    ids = {}
    for name, values in (("tag_ids", game.tag_ids), ("company_ids", game.company_ids)):
        ids[name] = [int(value) for value in values]
        if any(not 0 < value < 2**63 for value in ids[name]):
            raise ValueError("Invalid catalog relation identity")
    base.update(
        {
            "source_text_hash": digest(canonical),
            "input_text_hash": digest(text),
            "languages": game.languages,
            "platforms": game.platforms,
            "resource_types": game.resource_types,
            **ids,
        }
    )
    if game.release_date:
        from datetime import date

        base["release_year"] = date.fromisoformat(game.release_date).year
    return base, text
