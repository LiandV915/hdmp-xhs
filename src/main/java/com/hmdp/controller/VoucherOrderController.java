package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    IVoucherOrderService voucherOrderService;

    /** 秒杀抢购 */
    @PostMapping("/seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }

    /** 我的订单列表（status 可选，不传则返回全部） */
    @GetMapping("/my")
    public Result queryMyOrders(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "status", required = false) Integer status) {
        return voucherOrderService.queryMyOrders(current, status);
    }

    /** 订单详情 */
    @GetMapping("/{id}")
    public Result queryOrderById(@PathVariable Long id) {
        return voucherOrderService.queryOrderById(id);
    }

    /** 取消订单（仅未支付状态可取消） */
    @PutMapping("/cancel/{id}")
    public Result cancelOrder(@PathVariable Long id) {
        return voucherOrderService.cancelOrder(id);
    }
}
