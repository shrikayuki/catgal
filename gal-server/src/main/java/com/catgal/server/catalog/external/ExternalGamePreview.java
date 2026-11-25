/*
 * 阅读说明
 * 外部条目预览快照，管理员可核对后决定关联哪个本地游戏。
 */
package com.catgal.server.catalog.external;

/** External text is untrusted content. Clients must render it as plain text. */
public record ExternalGamePreview(String provider, String externalId, String url, String title,
                                  String originalTitle, String coverUrl, String description, String releaseDate) { }
