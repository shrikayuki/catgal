# 阅读说明
# 识别疑似凭据并计算来源或内容摘要，供候选去重和版本核验。

import hashlib
import re
import unicodedata

_CREDENTIAL = re.compile(
    r"sk-[A-Za-z0-9_-]{8,}|eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+"
    r"|(?:api[ _-]?key|access[ _-]?key|secret|password|passwd|token|密钥|密码|令牌)"
    r"\s*(?:[=:：]|是|为|\bis\b)\s*\S{4,}"
    r"|\bBearer\s+[A-Za-z0-9._~-]{12,}|\bAKIA[A-Z0-9]{16}\b"
    r"|(?:redis|mysql|postgres(?:ql)?)://[^\s/:]+:[^\s/@]+@",
    re.IGNORECASE,
)


def contains_credential(content):
    return bool(_CREDENTIAL.search(content))


def source_hash(content):
    return hashlib.sha256(content.encode()).hexdigest()


def content_hash(content, memory_type):
    normalized = unicodedata.normalize("NFKC", " ".join(content.split())).casefold()
    return source_hash(memory_type + "\n" + normalized)
