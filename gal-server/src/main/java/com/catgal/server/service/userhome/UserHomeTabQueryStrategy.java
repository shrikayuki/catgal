package com.catgal.server.service.userhome;

import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.domain.query.UserHomeQueryDTO;
import com.catgal.common.enums.UserHomeTabEnum;

public interface UserHomeTabQueryStrategy {

    UserHomeTabEnum supports();

    PageDTO<?> query(UserHomeQueryDTO query, Long userId, boolean includePrivate);
}
