package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private RedisIdWorker idWorker;
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private IVoucherOrderService voucherOrderService;


    private SimpleRedisLock simpleRedisLock;

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Resource
    private RedisIdWorker redisIdWorker;
    // 提前加载脚本


    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();


    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    VoucherOrder voucherOrder = orderTasks.take();

                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        // 创建锁对象
        SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);

        boolean isLock = lock.tryLock(1200);
        log.info(String.valueOf(isLock));
        if (!isLock) {
            log.error("请勿重复下单");
            return;
        }

        // 获取代理对象（事务）
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    private IVoucherOrderService proxy;

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );
        // 2. 判断结果是为0
        int r = result.intValue();
        if (r != 0) {
            // 2.1.不为0，代表没有购买资格
            return Result.fail(result == 1 ? "库存不足" : "不能重复下单");
        }


        // 2.2.为0，有购买资格，把下单信息保存到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        // 订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // userid
        voucherOrder.setUserId(userId);
        // 优惠券id
        voucherOrder.setVoucherId(voucherId);
        // 放入阻塞队列
        orderTasks.add(voucherOrder);

        proxy = (IVoucherOrderService) AopContext.currentProxy();

        //  3. 返回订单id
        return Result.ok(orderId);

    }


    // @Override
    // public Result seckillVoucher(Long voucherId) {
    //     // 查询优惠券
    //     SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
    //     // 判断秒杀是否开始
    //     if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
    //         return Result.fail("秒杀尚未开始！");
    //     }
    //     // 判断秒杀是否结束
    //     if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
    //         return Result.fail("秒杀已经结束！");
    //     }
    //     // 判断库存是否充足
    //     if (seckillVoucher.getStock() < 1) {
    //         return Result.fail("库存不足！");
    //     }
    //
    //     //悲观锁
    //     Long userId = UserHolder.getUser().getId();
    //
    //
    //     // 创建锁对象
    //     SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate,"order:"+userId);
    //
    //     boolean isLock = lock.tryLock(1200);
    //     log.info(String.valueOf(isLock));
    //     if (!isLock) {
    //         return Result.fail("请勿重复下单");
    //     }
    //
    //     //获取代理对象（事务）
    //     try {
    //         IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
    //         return proxy.createVoucherOrder(voucherId);
    //     }finally {
    //         lock.unlock();
    //     }
    // }


    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 一人一单，限购
        // 查询订单
        // Long userId = UserHolder.getUser().getId();
        Long userId = voucherOrder.getUserId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 判断是否存在
        if (count > 0) {
            // 已存在订单
            log.error("限购数量为：1！");
            return;
        }

        // 不存在，扣减库存
        // try {
        //     seckillVoucher.setStock(seckillVoucher.getStock() - 1);
        //
        // } catch (Exception e) {
        //     return Result.fail("库存不足！");
        // }

        // 不存在，扣减库存
        boolean success = seckillVoucherService.update()
                // 乐观锁-->解决库存超卖
                .setSql("stock=stock-1")  // set stock = stock -1
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)  // where voucher_id = ? and stock > 0
                .update();
        if (!success) {
            log.error("库存不足！");
            return;
        }


        // 创建订单
        save(voucherOrder);


    }
}
