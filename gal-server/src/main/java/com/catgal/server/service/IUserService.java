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

    void register(RegisterDTO dto);

    LoginVO login(LoginDTO dto);

    UserHomeVO getUserHomeById(UserHomeQueryDTO dto);

    void updateProfile(Long id, UserUpdateDTO dto);

    Integer getMyPoints();

    String updateAvatar(MultipartFile file);
}
