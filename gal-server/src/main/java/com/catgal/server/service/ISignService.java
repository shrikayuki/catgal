/*
 * 阅读说明
 * 签到的业务服务接口，Controller 和跨业务调用依赖此契约。
 * 接口收敛业务能力，具体持久化、缓存或外部访问由实现类承担。
 */
package com.catgal.server.service;

import com.catgal.common.domain.vo.SignVO;

import java.util.List;

public interface ISignService{

    /** 按运行模式选择持久事务或旧 Bitmap 签到，切换期间的新来源由共享屏障校验。 */
    void signIn();

    /** 返回当月已签到日期和当前连续签到信息。 */
    SignVO getSignRecords();
}
