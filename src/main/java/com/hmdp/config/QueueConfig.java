package com.hmdp.config;

import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;

@Configuration
public class QueueConfig {

    // 普通交换机名称
    public static final String COMMON_EXCHANGE = "Common";
    // 死信交换机名称
    public static final String DEAD_DEAD_LETTER_EXCHANGE = "Dead-letter";
    // 普通队列名称
    public static final String QUEUE_C = "CQ";
    // 死信队列名称
    public static final String DEAD_LETTER_QUEUE_D = "DLQ";

    /**
     * 声明普通交换机
     *
     * @return DirectExchange
     */
    @Bean("commonExchange")
    public DirectExchange commonExchange(){
        return new DirectExchange(COMMON_EXCHANGE);
    }

    /**
     * 声明死信交换机
     *
     * @return DirectExchange
     */
    @Bean("deadLetterExchange")
    public DirectExchange deadLetterExchange(){
        return new DirectExchange(DEAD_DEAD_LETTER_EXCHANGE);
    }

    /**
     * 声明普通队列C, 并绑定死信交换机及设置消息TTL
     *
     * 设置说明：
     * 所有 x- 开头的队列 / 交换机参数，都是 RabbitMQ 预定义的扩展参数
     * - x-dead-letter-exchange: 配置消息过期后转发的死信交换机名称
     * - x-dead-letter-routing-key: 配置转发到死信交换机时使用的路由键，此处与死信队列绑定时的路由键一致（"DLQ"）
     * - x-message-ttl: 消息存活时间（此处设置为10000毫秒，即10秒）
     *
     * 当消息在普通队列中变成死信时，RabbitMQ 做的事情只有三步：
     *
     * 1️⃣ 把这条消息 重新投递一次
     * 2️⃣ 投递目标：死信交换机
     * 3️⃣ 投递时 必须带一个 routing key
     * @return Queue
     */
    @Bean("queueC")
    public Queue queueC(){
        HashMap<String, Object> arguments = new HashMap<>();
        // 消息在队列中存活10秒后失效，进入死信队列
        arguments.put("x-message-ttl", 10000);
        // 配置死信交换机
        arguments.put("x-dead-letter-exchange", DEAD_DEAD_LETTER_EXCHANGE);
        // 配置死信路由键，绑定到死信队列时使用
        arguments.put("x-dead-letter-routing-key", "DLQ");

        return QueueBuilder.durable(QUEUE_C)
                .withArguments(arguments)
                .build();
    }

    /**
     * 声明死信队列D
     *
     * @return Queue
     */
    @Bean("deadLetterQueueD")
    public Queue deadLetterQueueD(){
        return QueueBuilder.durable(DEAD_LETTER_QUEUE_D)
                .build();
    }

    /**
     * 普通队列C与普通交换机Common绑定
     *
     * 当消息发送到交换机Common，并使用路由键 "CQ" 时，
     * 消息将被路由到队列CQ。
     *
     * @param queueC 普通队列
     * @param commonExchange 普通交换机
     * @return Binding
     */
    @Bean
    public Binding bindingQueueCToCommonExchange(@Qualifier("queueC") Queue queueC,
                                                 @Qualifier("commonExchange") DirectExchange commonExchange) {
        return BindingBuilder.bind(queueC).to(commonExchange).with("CQ");
    }

    /**
     * 死信队列D与死信交换机Dead-letter绑定
     *
     * 当普通队列CQ中的消息由于TTL过期或其他原因被转为死信后，
     * 消息会转发到死信交换机Dead-letter，并使用路由键 "DLQ"，
     * 从而被路由到死信队列DLQ。
     *
     * @param deadLetterQueueD 死信队列
     * @param deadLetterExchange 死信交换机
     * @return Binding
     */
    @Bean
    public Binding bindingDeadLetterQueueDToDeadLetterExchange(@Qualifier("deadLetterQueueD") Queue deadLetterQueueD,
                                                               @Qualifier("deadLetterExchange") DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueueD).to(deadLetterExchange).with("DLQ");
    }
}


/*
@Configuration 的本质
@Configuration
public class QueueConfig { ... }
这句话对 Spring 来说，等价于：
        “这是一个 Bean 工厂类”
Spring 在启动时会：先扫描这个类,找到所有 @Bean 方法,托管这些方法的返回值
在调用 @Bean 方法时，自动解析方法参数
//@Bean 方法的参数注入规则,@Bean 方法的参数 = Spring 容器负责注入,规则和构造器注入一模一样：,先按类型,冲突了 → 用 @Qualifier,找不到 → 启动失败*/
