package com.catgal.server.service;

import com.catgal.common.domain.vo.SignVO;

import java.util.List;

public interface ISignService{

    void signIn();

    SignVO getSignRecords();
}
