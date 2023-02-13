package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

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

    @Override

    public Result seckillVoucher(Long voucherId) {
        // 查询优惠券
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        // 判断秒杀是否开始
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始！");
        }
        // 判断秒杀是否结束
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束！");
        }
        // 判断库存是否充足
        if (seckillVoucher.getStock() < 1) {
            return Result.fail("库存不足！");
        }

        //悲观锁
        Long userId = UserHolder.getUser().getId();


        // 创建锁对象
        SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate,"order:"+userId);

        boolean isLock = lock.tryLock(1200);
        log.info(String.valueOf(isLock));
        if (!isLock) {
            return Result.fail("请勿重复下单");
        }

        //获取代理对象（事务）
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }finally {
            lock.unlock();
        }
    }


    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //一人一单，限购
        // 查询订单
        Long userId = UserHolder.getUser().getId();
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            // 判断是否存在
            if (count > 0) {
                //已存在订单
                return Result.fail("限购数量为：1！");
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
                    //乐观锁-->解决库存超卖
                    .setSql("stock=stock-1")  //set stock = stock -1
                    .eq("voucher_id", voucherId).gt("stock", 0)  //where voucher_id = ? and stock > 0
                    .update();
            if (!success) {
                return Result.fail("库存不足！");
            }


            // 创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            long orderId = idWorker.nextId("order");
            voucherOrder.setId(orderId);
            // Long userId = UserHolder.getUser().getId();
            voucherOrder.setUserId(userId);
            voucherOrder.setVoucherId(voucherId);
            voucherOrderService.save(voucherOrder);
            // 返回订单

            return Result.ok(orderId);

    }
}
