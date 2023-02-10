package com.hmdp;

import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class HmDianPingApplicationTests {


    @Autowired
    private ShopServiceImpl shopService;

    // 写入逻辑过期时间
    @Test
    void addLogicalExpire() throws InterruptedException {
        shopService.addLogicalExpire(1L, 10L);
    }
}
