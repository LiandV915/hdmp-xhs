package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.VoucherOrderVO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation
.PostConstruct;
import jakarta.annotation
.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    String queueName = "stream.orders";

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    private IVoucherService voucherService;

    @Autowired
    private IShopService shopService;

    /**
     * 存储订单的阻塞队列
     */
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    /**
     * 线程池
     */
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    /**
     * 代理对象
     */
    private IVoucherOrderService proxy;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    /**
     * 创建秒杀订单（基于RabbitMQ改进消息队列）
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");//生成全局唯一订单号
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId));//执行脚本，判断有无购买资格
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不许多买");
        }
        //生成订单对象
        //获取代理对象，为了消费线程调用 createVoucherOrder 时事务生效
        VoucherOrder order = new VoucherOrder();
        order.setId(orderId);
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        // 你可以用 JSON，也可以用序列化
        // 增加消息发送的异常处理
        //放入mq
        String jsonStr = JSONUtil.toJsonStr(order);
        try {
            rabbitTemplate.convertAndSend("Common", "CQ", jsonStr);//RabbitMQ 会把消息投递给 绑定了 "CQ" 的队列
        } catch (Exception e) {
            log.error("发送 RabbitMQ 消息失败");
            throw new RuntimeException("发送消息失败");
        }
        // 3. 返回订单号给前端（实际下单异步处理）
        return Result.ok(orderId);
    }

    // ================================================================
    // 我的订单列表
    // ================================================================
    @Override
    public Result queryMyOrders(Integer current, Integer status) {
        Long userId = UserHolder.getUser().getId();
        Page<VoucherOrder> page = query()
                .eq("user_id", userId)
                .eq(status != null, "status", status)
                .orderByDesc("create_time")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));

        List<VoucherOrder> records = page.getRecords();
        if (records.isEmpty()) {
            return Result.ok(Collections.emptyList(), 0L);
        }

        // 批量查询券信息
        List<Long> voucherIds = records.stream()
                .map(VoucherOrder::getVoucherId).distinct().collect(Collectors.toList());
        Map<Long, Voucher> voucherMap = voucherService.listByIds(voucherIds).stream()
                .collect(Collectors.toMap(Voucher::getId, v -> v));

        // 批量查询商铺信息
        List<Long> shopIds = voucherMap.values().stream()
                .map(Voucher::getShopId).distinct().collect(Collectors.toList());
        Map<Long, Shop> shopMap = shopService.listByIds(shopIds).stream()
                .collect(Collectors.toMap(Shop::getId, s -> s));

        List<VoucherOrderVO> voList = records.stream().map(order -> {
            VoucherOrderVO vo = new VoucherOrderVO();
            vo.setId(order.getId());
            vo.setVoucherId(order.getVoucherId());
            vo.setPayType(order.getPayType());
            vo.setStatus(order.getStatus());
            vo.setCreateTime(order.getCreateTime());
            vo.setPayTime(order.getPayTime());
            vo.setUseTime(order.getUseTime());
            vo.setRefundTime(order.getRefundTime());
            Voucher voucher = voucherMap.get(order.getVoucherId());
            if (voucher != null) {
                vo.setVoucherTitle(voucher.getTitle());
                vo.setVoucherSubTitle(voucher.getSubTitle());
                vo.setPayValue(voucher.getPayValue());
                vo.setShopId(voucher.getShopId());
                Shop shop = shopMap.get(voucher.getShopId());
                if (shop != null) {
                    vo.setShopName(shop.getName());
                }
            }
            return vo;
        }).collect(Collectors.toList());

        return Result.ok(voList, page.getTotal());
    }

    // ================================================================
    // 订单详情
    // ================================================================
    @Override
    public Result queryOrderById(Long id) {
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = query().eq("id", id).eq("user_id", userId).one();
        if (order == null) {
            return Result.fail("订单不存在");
        }
        Voucher voucher = voucherService.getById(order.getVoucherId());
        VoucherOrderVO vo = new VoucherOrderVO();
        vo.setId(order.getId());
        vo.setVoucherId(order.getVoucherId());
        vo.setPayType(order.getPayType());
        vo.setStatus(order.getStatus());
        vo.setCreateTime(order.getCreateTime());
        vo.setPayTime(order.getPayTime());
        vo.setUseTime(order.getUseTime());
        vo.setRefundTime(order.getRefundTime());
        if (voucher != null) {
            vo.setVoucherTitle(voucher.getTitle());
            vo.setVoucherSubTitle(voucher.getSubTitle());
            vo.setPayValue(voucher.getPayValue());
            vo.setShopId(voucher.getShopId());
            Shop shop = shopService.getById(voucher.getShopId());
            if (shop != null) {
                vo.setShopName(shop.getName());
            }
        }
        return Result.ok(vo);
    }

    // ================================================================
    // 取消订单（仅限未支付状态）
    // ================================================================
    @Override
    public Result cancelOrder(Long id) {
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = query().eq("id", id).eq("user_id", userId).one();
        if (order == null) {
            return Result.fail("订单不存在");
        }
        if (order.getStatus() != 1) {
            return Result.fail("当前订单状态不可取消，仅未支付订单可取消");
        }
        boolean ok = update().set("status", 4).eq("id", id).update();
        return ok ? Result.ok() : Result.fail("取消失败，请重试");
    }
}






    /*    *//**以下部分为通过阻塞队列实现异步下单
     * 当前类初始化完毕就立马执行该方法
     * Spring 在 Bean 创建完成、依赖注入完成后 会自动调用带有 @PostConstruct 注解的方法
     *//*
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(() -> {
            while (true) {
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    proxy.createVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        });
    }



    *//**
     * 创建秒杀订单
     *//*
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString());//执行脚本，利用redis判断用户能否购买（库存，一人一单）
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不许多买");
        }
        //生成订单对象
        long orderId=redisIdWorker.nextId("order");//生成全局唯一订单号
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        // 将订单保存到阻塞队列中
        orderTasks.add(voucherOrder);
        //获取代理对象，为了消费线程调用 createVoucherOrder 时事务生效
        IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
        this.proxy = proxy;
        return Result.ok(orderId);
    }

    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long voucherId=voucherOrder.getVoucherId();
        Long userId = voucherOrder.getUserId();//消费线程是 异步线程池线程，和 HTTP 请求线程不是同一个线程，此时不再是一个线程
        // ThreadLocal 是线程隔离的 → 异步线程中无法拿到 UserHolder 的值
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return ;
        }
        //抢购成功,乐观锁，判断库存是否大于零
        boolean success = seckillVoucherService.update().
                setSql("stock=stock-1").
                eq("voucher_id", voucherId).
                gt("stock", 0)
                .update();
        if (!success) {
           return ;

        }
        save(voucherOrder);//创建订单
    }*/





/*    *
     * 当前类初始化完毕就立马执行该方法
     * Spring 在 Bean 创建完成、依赖注入完成后 会自动调用带有 @PostConstruct 注解的方法*/
    //通过redis的stream作为消息队列实现下单


/*    //新建线程池，递交任务，用于异步下单
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(() -> {
            while (true) {
                try {
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"), //指定一个消费者，消费者组的名称是g1，消费者的自身标识为c1
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)), //创建一个空的读取选项对象，一次读一条，未读到则阻塞等待2s
                            StreamOffset.create(queueName, ReadOffset.lastConsumed()));//指定读取信息的起始位置，从上次消费的位置继续读取
                    if (list == null || list.isEmpty()) {
                        continue; //未读到消息
                    }
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    //将从消息队列中得到的信息转为VoucherOrder对象
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //处理订单
                    createVoucherOrder(voucherOrder);
                    //确认消息被消费
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("订单处理异常", e);
                    //因异常未被消费的信息会进入pending-list中等待处理
                    handlePendingList();
                }
            }
        });
    }

    //处理等待队列
    private void handlePendingList () {
            while (true) {
                try {
                    //1.获取pendingList中的订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1), //因为是异常信息，所以无需阻塞等待
                            StreamOffset.create(queueName, ReadOffset.from("0")) //保证确保所有未处理的信息,所以从0处开始读取
                    );
                    //2.判断消息是否获取成功
                    if (list == null || list.isEmpty()) {
                        //获取消息失败
                        break;
                    }
                    //3.获取成功，可以下单
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    createVoucherOrder(voucherOrder);
                    //4.ack确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理pending-list异常", e);
                }

            }
        }

    @Override
    public Result createVoucherOrder (Long voucherId){
        return null;
    }

    @Override
    public void createVoucherOrder (VoucherOrder voucherOrder){
        Long voucherId = voucherOrder.getVoucherId();
        Long userId = voucherOrder.getUserId();//消费线程是 异步线程池线程，和 HTTP 请求线程不是同一个线程，此时不再是一个线程
        // ThreadLocal 是线程隔离的 → 异步线程中无法拿到 UserHolder 的值
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return;
        }
        //抢购成功,乐观锁，判断库存是否大于零
        boolean success = seckillVoucherService.update().
                setSql("stock=stock-1").
                eq("voucher_id", voucherId).
                gt("stock", 0)
                .update();
        if (!success) {
            return;

        }
        save(voucherOrder);//创建订单
    }*/

/*    *//**
 * 创建秒杀订单（Reddison实现）
 * 核心思想
 * 同一个 userId，在同一时刻只能有一个线程进入下单逻辑
 * 也就是：
 * 同一个用户并发请求 → 串行执行 防止「两个线程同时查数据库，都发现没下过单」
 * 真实很少用
 * @param voucherId
 * @return
 *//*
    @Override
    public Result seckillVoucher(Long voucherId) {
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);

        if (seckillVoucher == null) {
            return Result.fail("没有这优惠券");
        }
        if (LocalDateTime.now().isAfter(seckillVoucher.getEndTime())) {
            return Result.fail("抢购已结束");
        }
        if (LocalDateTime.now().isBefore(seckillVoucher.getBeginTime())) {
            return Result.fail("抢购未开始");

        }
        if (seckillVoucher.getStock() < 1) {
            return Result.fail("库存不足");
        }
        Long userId = UserHolder.getUser().getId();
        RLock lock=redissonClient.getLock("lock:order"+userId);//通过bean对象获取锁
        boolean isLock = lock.tryLock();
        if (!isLock) {
            // 索取锁失败，重试或者直接抛异常（这个业务是一人一单，所以直接返回失败信息）
            return Result.fail("一人只能下一单");
        }
        try {
            // 索取锁成功，创建代理对象，使用代理对象调用第三方事务方法， 防止事务失效
            //Spring 的 @Transactional 是通过 AOP（代理模式） 实现的。当你在一个 Spring Bean 上加了 @Transactional 时，Spring 会生成一个 代理对象：
            //若直接执行方法，this 指向的是当前实例对象（原始 Bean），不是 Spring 的代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            lock.unlock();
        }
    }*/

/*
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //查询一下用户之前是否下过单（一人一单判断）
        Long userId = UserHolder.getUser().getId();
            Long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            if (count > 0) {
                return Result.fail("用户已下过单");
            }
            //抢购成功,乐观锁，判断库存是否大于零
            boolean success = seckillVoucherService.update().
                    setSql("stock=stock-1").
                    eq("voucher_id", voucherId).
                    gt("stock", 0)
                    .update();
            if (!success) {
                return Result.fail("库存不足");

            }

            //创建新订单
            VoucherOrder voucherOrder = new VoucherOrder();
            long orderId = redisIdWorker.nextId("order");//通过redis生成全局唯一id
            voucherOrder.setId(orderId);

            voucherOrder.setUserId(userId);
            voucherOrder.setCreateTime(LocalDateTime.now());
            voucherOrder.setVoucherId(voucherId);

            save(voucherOrder);
            return Result.ok(voucherOrder.getId());

    }*/

