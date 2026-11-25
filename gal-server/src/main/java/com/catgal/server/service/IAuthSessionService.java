package com.catgal.server.service;

import com.catgal.common.context.UserInfo;

/** 网站登录凭据的当前有效性与主动退出。 */
public interface IAuthSessionService {
    UserInfo authenticate(String token);

    void logout(String token);
}
