package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SHOP_TYPE_KEY;

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
    @Override
    public Result queryTypeList() {
        //从redis中查找商铺类型
        List<String> shopList = stringRedisTemplate.opsForList().range(SHOP_TYPE_KEY, 0, -1);
        //存在，返回
        if (!shopList.isEmpty()) {
            List<ShopType> shop = shopList.stream().map((item) -> {
                return JSONUtil.toBean(item, ShopType.class);
            }).collect(Collectors.toList());
        return Result.ok(shop);
        }

        //不存在，去数据库查找
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        if (shopTypes.isEmpty()) {
            //数据库不存在返回error
            return Result.fail("shopType ERROR");
        }
        //存在，返回
         List<String> resList = shopTypes.stream().map((item) -> {
            return JSONUtil.toJsonStr(item);
        }).collect(Collectors.toList());
        //把结果写入redis
        stringRedisTemplate.opsForList().rightPushAll(SHOP_TYPE_KEY,resList);
        //返回结果
        return Result.ok(shopTypes);
    }
}
