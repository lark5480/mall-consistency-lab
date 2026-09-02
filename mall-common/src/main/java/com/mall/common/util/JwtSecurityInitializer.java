package com.mall.common.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 启动即校验 JWT 密钥：各服务（含网关）组件扫描 com.mall 时创建本 Bean，
 * 密钥缺失或强度不足会在应用启动阶段直接失败，而不是等首个请求打过来才暴露。
 */
@Component
public class JwtSecurityInitializer {

    public JwtSecurityInitializer(@Value("${mall.jwt.secret:}") String secret,
                                  @Value("${mall.jwt.expire-millis:" + JwtUtil.DEFAULT_EXPIRE_MILLIS + "}") long expireMillis) {
        JwtUtil.configure(secret, expireMillis);
    }
}
