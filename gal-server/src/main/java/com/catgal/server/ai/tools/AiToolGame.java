/*
 * 阅读说明
 * 向 AI 工具暴露的游戏资料快照，字段范围用于控制上下文大小。
 */
package com.catgal.server.ai.tools;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Public catalog fields only. IDs are strings to preserve browser integer precision. */
public record AiToolGame(String id, String name, String introduction, LocalDate releaseDate,
                          BigDecimal rating, List<String> tags, List<String> companies) { }
