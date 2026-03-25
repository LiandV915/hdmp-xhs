package com.hmdp.utils.listener;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.impl.SeckillVoucherServiceImpl;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation
.Resource;

@Component
@Slf4j
public class SeckillVoucherListener {

    @Resource
    private VoucherOrderServiceImpl voucherOrderService;

    @Resource
    private SeckillVoucherServiceImpl seckillVoucherService;
    /**
     * =========================
     * 普通队列消费者（核心）
     * =========================
     *
     * 监听普通队列 CQ
     *
     * 该方法的职责非常单一：
     * 1. 反序列化消息
     * 2. 幂等校验（防止重复下单）
     * 3. 落库（保存订单）
     * 4. 手动 ACK
     *
     * ⚠️ 注意：
     *
     * - 不做复杂业务逻辑
     */
    @Transactional
    @RabbitListener(queues = "CQ")
    public void receiveOrder(Message message, Channel channel) throws Exception {
        // 每一条消息在 RabbitMQ 中都有一个唯一的 deliveryTag
        // ACK / NACK 都是基于它
        long deliveryTag = message.getMessageProperties().getDeliveryTag();

        try {
            // 1. 解析消息
            String msg = new String(message.getBody());
            VoucherOrder order = JSONUtil.toBean(msg, VoucherOrder.class);

            log.info("收到秒杀订单消息，orderId={}", order.getId());

            // 2. 幂等校验（非常重要）
            // RabbitMQ 的消息可能被重复投递
            // 所以必须在数据库层兜底“一人一单”
            QueryWrapper<VoucherOrder> wrapper = new QueryWrapper<>();
            wrapper.eq("user_id", order.getUserId());
            wrapper.eq("voucher_id", order.getVoucherId());
            boolean exists = voucherOrderService.count(wrapper)>0;

            if (exists) {
                log.warn("重复消费消息，userId={}, voucherId={}",
                        order.getUserId(), order.getVoucherId());

                // 已经处理过，直接 ACK，防止消息反复消费
                channel.basicAck(deliveryTag, false);//deliveryTag指定消息编号 // false	只 ACK 这一条（不批量）
                return;
            }

            // 3. 保存订单到数据库
            voucherOrderService.save(order);
            //扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1")
                    .eq("voucher_id", order.getVoucherId())
                    .gt("stock", 0)
                    .update();
            if (!success) {
                log.error("库存不足");
            }
            // 4. 手动 ACK，告诉 MQ：这条消息我处理成功了
            channel.basicAck(deliveryTag, false);
            log.info("订单处理完成并 ACK，orderId={}", order.getId());

        } catch (Exception e) {
            log.error("处理秒杀订单失败，消息将进入死信队列", e);

            /**
             * basicNack 参数说明：
             * 1. deliveryTag：消息标识
             * 2. multiple：是否批量（false 表示只处理当前这一条）
             * 3. requeue：
             *    - true  ：重新放回原队列（⚠️ 秒杀场景不建议）
             *    - false ：进入死信队列（推荐）
             */
            channel.basicNack(deliveryTag, false, false);
        }
    }

    /**
     * =========================
     * 死信队列消费者（兜底）
     * =========================
     *
     * 死信队列的消息说明：
     * - 多次消费失败
     * - 或 TTL 过期
     *
     * ❌ 不建议在这里“再走一遍下单逻辑”
     * ✅ 正确做法：
     *    - 打日志
     *    - 记录失败信息
     *    - 发告警
     */
    @RabbitListener(queues = "DLQ")
    public void receiveDeadLetter(Message message) {

        String msg = new String(message.getBody());
        VoucherOrder order = JSONUtil.toBean(msg, VoucherOrder.class);

        log.error("【死信订单】orderId={}, userId={}, voucherId={}",
                order.getId(), order.getUserId(), order.getVoucherId());

        // 这里你可以：
        // 1. 写入失败订单表
        // 2. 发钉钉 / 邮件告警
        // 3. 人工补偿
        
    }
}
