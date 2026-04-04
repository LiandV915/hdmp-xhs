package com.hmdp.dto;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 我的订单列表展示 VO
 * 冗余券标题、商铺名，避免前端多次请求
 */
@Data
public class VoucherOrderVO {

    /** 订单id */
    private Long id;

    /** 券id */
    private Long voucherId;

    /** 券标题 */
    private String voucherTitle;

    /** 券副标题 */
    private String voucherSubTitle;

    /** 支付金额（分） */
    private Long payValue;

    /** 所属商铺id */
    private Long shopId;

    /** 商铺名称 */
    private String shopName;

    /**
     * 支付方式
     * 1:余额支付 2:支付宝 3:微信
     */
    private Integer payType;

    /**
     * 订单状态
     * 1:未支付 2:已支付 3:已核销 4:已取消 5:退款中 6:已退款
     */
    private Integer status;

    private LocalDateTime createTime;
    private LocalDateTime payTime;
    private LocalDateTime useTime;
    private LocalDateTime refundTime;
}
