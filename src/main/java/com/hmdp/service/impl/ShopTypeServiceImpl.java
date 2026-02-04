package com.hmdp.service.impl;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation
.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 查找店铺种类
     */
    @Override
    public List<ShopType> listByType() {
        List<ShopType> shopTypeList = new ArrayList<>();
        List<String> shopTypeString=new ArrayList<>();
        shopTypeString =stringRedisTemplate.opsForList().range("shoptype",0,-1);
        if(shopTypeString!=null&&shopTypeString.size()>0){//缓存中有
            for(String  shopType:shopTypeString){
                ShopType shopType1=new ShopType();
                shopType1= JSONUtil.toBean(shopType,ShopType.class);//将json转为shoptype
                shopTypeList.add(shopType1);
            }
            return shopTypeList;
        }
        shopTypeList=list();
        for(ShopType shopType:shopTypeList){
            stringRedisTemplate.opsForList().rightPush("shoptype",JSONUtil.toJsonStr(shopType));
        }
        return shopTypeList;
    }
}
