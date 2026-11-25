/*
 * 阅读说明
 * 社区用户的业务服务接口，Controller 和跨业务调用依赖此契约。
 * 继承 IService 获得实体基础操作，带业务含义的方法由同名 ServiceImpl 实现约束。
 */
package com.catgal.server.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.catgal.common.domain.dto.LoginDTO;
import com.catgal.common.domain.dto.RegisterDTO;
import com.catgal.common.domain.query.UserHomeQueryDTO;
import com.catgal.common.domain.dto.UserUpdateDTO;
import com.catgal.common.domain.vo.UserHomeVO;
import com.catgal.server.domain.po.User;
import com.catgal.common.domain.vo.LoginVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 用户表 服务类
 */
public interface IUserService extends IService<User> {

    /** 注册先校验身份字段与重复账号，再编码密码，显式赋予初始普通角色和正常状态。 */
    void register(RegisterDTO dto);

    /** 先校验账号密码，再检查当前封禁状态，成功后签发 JWT 并组装登录响应。 */
    LoginVO login(LoginDTO dto);

    /** 组装用户主页基本信息，并按 tab 委托对应查询策略。 */
    UserHomeVO getUserHomeById(UserHomeQueryDTO dto);

    /** 当前用户身份由认证上下文读取，先验证本人权限再保存允许修改的资料字段。 */
    void updateProfile(Long id, UserUpdateDTO dto);

    /** 读取当前认证用户的积分余额。 */
    Integer getMyPoints();

    /** 提交签到或抽奖后要求读取最新已提交余额。 */
    Integer getMyPoints(boolean fresh);

    /** 上传并绑定当前用户头像，返回应用可读取的业务路径。 */
    String updateAvatar(MultipartFile file);
}
