# 阅读说明
# 模型、上下文与并发控制使用的异常类型，调用方据类型映射稳定错误码。

class ConversationBusyError(Exception):
    """Another request owns this conversation's generation lease."""


class ConversationLeaseLostError(Exception):
    """The generation lease expired before memory could be committed."""


class ModelCircuitOpenError(Exception):
    """The model provider is cooling down after consecutive failures."""
