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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static org.apache.commons.lang3.StringUtils.defaultString;

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
     * 存数据(knowledgeText 是“让模型理解这是什么”，metadata 是“让系统管理这条知识”。)
     * @param shop
     */
    @Override
    @Transactional
    public void saveShopWithVector(Shop shop) {
        // 1. 保存到 MySQL
        this.save(shop);
        // 2. 构造知识库文本
        String content = buildShopKnowledgeText(shop);

        // 3. 构造知识库 Document
        Document document = new Document(
                content,
                buildShopMetadata(shop)
        );

        // 4. 写入 Redis 向量知识库
        vectorStore.add(List.of(document));

        // 5. 写入缓存
        cacheClient.setWithExpire(
                RedisConstants.CACHE_SHOP_KEY + shop.getId(),
                shop,
                RedisConstants.CACHE_SHOP_TTL,
                TimeUnit.MINUTES
        );
    }


    /**
     * 像“数据库字段 / 倒排索引字段”，解决:条件过滤 精确定位 维护更新 文档分类
     * @param shop
     * @return
     */
    private Map<String, Object> buildShopMetadata(Shop shop) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", "kb_shop"); // 关键：知识库类型
        metadata.put("shopId", String.valueOf(shop.getId()));
        metadata.put("typeId", String.valueOf(shop.getTypeId()));
        metadata.put("name", defaultString(shop.getName()));
        metadata.put("area", defaultString(shop.getArea()));
        metadata.put("address", defaultString(shop.getAddress()));
        metadata.put("avgPrice", shop.getAvgPrice() == null ? "" : String.valueOf(shop.getAvgPrice()));
        metadata.put("score", shop.getScore() == null ? "" : String.valueOf(shop.getScore()));
        metadata.put("openHours", defaultString(shop.getOpenHours()));
        metadata.put("timestamp", String.valueOf(System.currentTimeMillis()));
        return metadata;
    }


    /**
     * 构建店铺知识文本
     * 解决：向量化 语义匹配 给模型看的上下文
     * @param shop
     * @return
     */
    private String buildShopKnowledgeText(Shop shop) {
        StringBuilder sb = new StringBuilder();
        sb.append("这是一个本地生活平台中的店铺信息。");
        sb.append("店铺名称是").append(defaultString(shop.getName())).append("。");

        if (notBlank(shop.getArea())) {
            sb.append("该店铺位于").append(shop.getArea()).append("商圈。");
        }

        if (notBlank(shop.getAddress())) {
            sb.append("具体地址是").append(shop.getAddress()).append("。");
        }

        if (shop.getAvgPrice() != null) {
            sb.append("人均消费大约").append(shop.getAvgPrice()).append("元。");
        }

        if (shop.getScore() != null) {
            sb.append("用户评分大约").append(shop.getScore() / 10.0).append("分。");
        }

        if (notBlank(shop.getOpenHours())) {
            sb.append("营业时间为").append(shop.getOpenHours()).append("。");
        }
        sb.append("该文档可用于回答用户关于店铺位置、价格、评分、营业时间等问题。");

        return sb.toString();
    }


    private String defaultString(String str) {
        return str == null ? "" : str.trim();
    }

    private boolean notBlank(String str) {
        return str != null && !str.trim().isEmpty();
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
