# 阅读说明
# AES-GCM 用户模型凭据加解密，密钥环来自外部配置。
# 每次加密生成随机 nonce，AAD 绑定凭据 ID、用户、来源和 revision。

import base64
import os
import re

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from app.config import Settings
from app.persistence.contracts import PersistenceError


class CredentialCipher:
    def __init__(self, settings: Settings):
        self.active_key_id = settings.credential_active_key_id
        self._keys = {}
        for key_id, value in settings.credential_encryption_keys.items():
            try:
                if not re.fullmatch(r"[A-Za-z0-9_-]{1,32}", key_id):
                    raise ValueError
                key = base64.b64decode(value.get_secret_value(), validate=True)
                if len(key) != 32:
                    raise ValueError
                self._keys[key_id] = AESGCM(key)
            except (ValueError, TypeError):
                raise RuntimeError("Invalid credential encryption configuration") from None
        if self.active_key_id not in self._keys:
            raise RuntimeError("Active credential encryption key is missing")

    # 绑定身份与版本的附加认证数据参与校验，复制密文到其他凭据行会导致解密失败。
    def aad(self, row) -> bytes:
        return (
            f"catgal:credential:{row.id}:{row.user_id}:{row.provider_code}:{row.revision}".encode()
        )

    # 随机 nonce 与密钥 ID 随密文保存，界面只需要展示末尾掩码。
    def encrypt(self, row, secret: str):
        row.nonce = os.urandom(12)
        row.encryption_key_id = self.active_key_id
        row.ciphertext = self._keys[self.active_key_id].encrypt(
            row.nonce, secret.encode(), self.aad(row)
        )
        row.masked_suffix = secret[-4:]
        row.status = 1

    # 根据行上的密钥 ID 读取密钥环，认证失败统一转换为凭据不可用错误。
    def decrypt(self, row) -> str:
        try:
            key = self._keys[row.encryption_key_id]
            return key.decrypt(row.nonce, row.ciphertext, self.aad(row)).decode()
        except (KeyError, InvalidTag, ValueError, TypeError, UnicodeError):
            raise PersistenceError("CREDENTIAL_UNAVAILABLE", "模型凭据不可用", 503) from None
