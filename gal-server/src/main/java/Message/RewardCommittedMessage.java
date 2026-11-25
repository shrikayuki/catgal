package Message;

/** 消息只携带定位，余额和归属由消费者回库读取，避免旧消息中的数值覆盖新账本。 */
public record RewardCommittedMessage(int version, Long eventId) { }
