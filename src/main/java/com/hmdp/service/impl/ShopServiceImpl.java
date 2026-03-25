package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.TreeCodec;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {



    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private VectorStore vectorStore;

    @Resource
    private CacheClient cacheClient;


    @Override
    public Result queryById(Long id){
        Shop shop=cacheClient.queryWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY+id,id,Shop.class,this::getById
                ,RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES);
        if(shop==null){
            return Result.fail("查不到商铺");
        }
        return Result.ok(shop);
    }

    /**
     * 查缓存，通过互斥锁解决缓存击穿，基于缓存空对象防止缓存穿透
     * @param id
     * @return
     */
    public Result queryById2(Long id) {

        String key = RedisConstants.CACHE_SHOP_KEY + id;
        //先争取从缓存查出结果
        Shop shop = getFromShopCache(key);
        if (shop != null) {
            return Result.ok(shop);
        }
        //缓存为空,对象也是不存在
        String json = stringRedisTemplate.opsForValue().get(key);
        if ("".equals(json)) {
            return Result.fail("查不到店铺");
        }

        String lockkey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock=TryLock(lockkey);
        if (!isLock) {
            // 拿不到锁短暂休息后重试（不会递归）
            try {
                Thread.sleep(50);
            } catch (Exception ignored){}
            return queryById(id);
        }
        try {
            // 双检，可能缓存已经被别的线程写好
            shop = getFromShopCache(key);
            if (shop != null) {
                return Result.ok(shop);
            }
            // 查询数据库
            shop = getById(id);
            if (shop == null) {//仍然为空，则缓存空对象，防止缓存穿透
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return Result.fail("查不到店铺");
            }
            int random = ThreadLocalRandom.current().nextInt(10);

            stringRedisTemplate.opsForValue().set(
                    key,
                    JSONUtil.toJsonStr(shop),
                    RedisConstants.CACHE_SHOP_TTL + random,
                    TimeUnit.MINUTES
            );
/*            每个 key 过期时间都不同
✔ 不会同一时间集体失效
✔ 这才叫防缓存雪崩*/
            return Result.ok(shop);

        } finally {
            unlock(lockkey);
        }
    }

    /**
     * 根据商铺号从缓存查商铺(通过缓存空对象避免缓存穿透）
     * @param key
     * @return
     */
    public Shop getFromShopCache(String key) {
        String json = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(json)){//缓存命中，直接返回数据
            Shop shop= JSONUtil.toBean(json,Shop.class);
            return shop;
        }
        //缓存啥也没有或缓存空对象
        return null;
    }




    /**
     * 更新商店数据
     * @param shop
     * @return
     */
    @Transactional
    @Override
    public Result update(Shop shop) {
        Long id=shop.getId();
        //先删除数据库
        updateById(shop);
        //后操作缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY+id);
        return Result.ok();
    }

    @Override
    public void loadShopToCache() {
        List<Shop> shoplist=list();
        Map<Long,List<Shop>> map=shoplist.stream().collect(Collectors.groupingBy(Shop::getTypeId));

    }


    /**
     * 存数据
     * @param shop
     */
    @Override
    @Transactional
    public void saveShopWithVector(Shop shop) {
        // 1. 保存到 MySQL
        this.save(shop);

        // 2. 构造向量文本
        String content = buildShopVectorText(shop);

        // 3. 构造 Document
        Document document = new Document(
                content,
                Map.of(
                        "shopId", shop.getId(),
                        "typeId", shop.getTypeId(),
                        "name", shop.getName(),
                        "area", shop.getArea()
                )
        );

        // 4. 写入 Redis 向量库
        vectorStore.add(List.of(document));
        // 5. 写入缓存（逻辑过期策略）
        // 使用你的 CacheClient 工具类
        cacheClient.setWithExpire(
                RedisConstants.CACHE_SHOP_KEY + shop.getId(), // key
                shop,                                        // value
                RedisConstants.CACHE_SHOP_TTL,              // TTL
                TimeUnit.MINUTES
        );
    }

    /**
     * 店铺信息 → 向量文本
     */
    private String buildShopVectorText(Shop shop) {
        StringBuilder sb = new StringBuilder();
        sb.append("店铺名称：").append(shop.getName()).append("。");
        sb.append("商圈：").append(shop.getArea()).append("。");
        sb.append("地址：").append(shop.getAddress()).append("。");

        if (shop.getAvgPrice() != null) {
            sb.append("人均消费：").append(shop.getAvgPrice()).append("元。");
        }
        if (shop.getScore() != null) {
            sb.append("评分：").append(shop.getScore() / 10.0).append("分。");
        }
        if (shop.getOpenHours() != null) {
            sb.append("营业时间：").append(shop.getOpenHours()).append("。");
        }

        return sb.toString();
    }
    /**
     * 尝试获取锁
     * @param key
     * @return
     */
    private boolean TryLock(String key){
        Boolean flag=stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 解锁
     * @param key
     */
    private void unlock(String key){
        stringRedisTemplate.delete(key);

    }


}
