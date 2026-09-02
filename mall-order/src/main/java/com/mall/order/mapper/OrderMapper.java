package com.mall.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.order.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {
    String EXCLUDE_CANCELLED = "status <> 'CANCELLED'";

    @Select("SELECT COUNT(*) AS cnt, IFNULL(SUM(total_amount), 0) AS gmv FROM `order` "
            + "WHERE " + EXCLUDE_CANCELLED + " AND created_at >= #{since}")
    Map<String, Object> sumSince(@Param("since") LocalDateTime since);

    @Select("SELECT COUNT(*) AS cnt, IFNULL(SUM(total_amount), 0) AS gmv FROM `order` "
            + "WHERE " + EXCLUDE_CANCELLED)
    Map<String, Object> sumAll();

    @Select("SELECT COUNT(*) FROM `order` WHERE status = 'PAID'")
    long countPendingShip();

    @Select("SELECT DATE(created_at) AS date, COUNT(*) AS count, IFNULL(SUM(total_amount), 0) AS gmv "
            + "FROM `order` WHERE " + EXCLUDE_CANCELLED + " AND created_at >= #{since} "
            + "GROUP BY DATE(created_at)")
    List<Map<String, Object>> trendSince(@Param("since") LocalDateTime since);
}
