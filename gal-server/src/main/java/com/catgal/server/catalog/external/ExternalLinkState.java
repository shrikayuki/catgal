/*
 * 阅读说明
 * 某游戏当前外部关联和修订号，绑定或解绑时回传 revision。
 */
package com.catgal.server.catalog.external;

import java.util.List;

public record ExternalLinkState(long revision, List<ExternalLinkVO> links) { }
