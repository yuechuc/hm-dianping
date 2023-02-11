package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
class HmDianPingApplicationTests {


    @Autowired
    private ShopServiceImpl shopService;

    @Resource
    private CacheClient cacheClient;

    // 写入逻辑过期时间
    @Test
    void addLogicalExpire() throws InterruptedException {
        // shopService.addLogicalExpire(1L, 10L);
        Shop shop = shopService.getById(1L);
        cacheClient.setLogicalExpire(CACHE_SHOP_KEY + 1L,shop,10L, TimeUnit.SECONDS);
    }
}
