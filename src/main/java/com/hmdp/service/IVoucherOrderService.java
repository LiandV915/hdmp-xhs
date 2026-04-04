package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    /**
     * 我的订单列表
     * @param current 页码
     * @param status  订单状态筛选（null=全部，1=未支付，2=已支付，3=已核销，4=已取消）
     */
    Result queryMyOrders(Integer current, Integer status);

    /** 订单详情 */
    Result queryOrderById(Long id);

    /** 取消订单（仅限未支付状态） */
    Result cancelOrder(Long id);
}
