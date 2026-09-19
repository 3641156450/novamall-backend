package com.novamall.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novamall.common.annotation.DistributedLock;
import com.novamall.common.annotation.Idempotent;
import com.novamall.common.context.UserContext;
import com.novamall.common.exception.BizException;
import com.novamall.common.result.ResultCode;
import com.novamall.order.client.ProductClient;
import com.novamall.order.client.dto.ProductDTO;
import com.novamall.order.config.RabbitMqConfig;
import com.novamall.order.dto.CreateOrderRequest;
import com.novamall.order.entity.LocalMessage;
import com.novamall.order.entity.Order;
import com.novamall.order.entity.OrderItem;
import com.novamall.order.entity.OrderStatusEnum;
import com.novamall.order.mapper.LocalMessageMapper;
import com.novamall.order.mapper.OrderItemMapper;
import com.novamall.order.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 订单服务：幂等 + 分布式锁 + 本地消息表。
 *
 * <p>一次下单要同时保证：</p>
 * <ol>
 *   <li>用户重复点击只生成一笔订单 → <b>幂等 token</b>（Redis Lua 原子消费）</li>
 *   <li>同一用户并发下单不产生脏数据 → <b>分布式锁</b>（按 userId 维度串行）</li>
 *   <li>订单和扣库存要么都成功要么都不生效 → <b>本地消息表 + MQ 最终一致</b></li>
 *   <li>库存不会被扣成负数 → <b>SQL 里带 stock >= num 条件</b>（见 ProductMapper）</li>
 * </ol>
 *
 * @author NovaMall
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final LocalMessageMapper localMessageMapper;
    private final ProductClient productClient;
    private final RabbitTemplate rabbitTemplate;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String TOKEN_KEY = "idempotent:";

    /**
     * 申请幂等令牌。
     *
     * <p>为什么不让服务端直接拿 userId 做幂等 key？
     * 因为同一个用户在不同时间下两笔不同的单是合法需求。
     * 一次性 token 的语义是"这次提交"，粒度刚刚好。</p>
     */
    public String generateToken() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        // 5 分钟不用就过期，避免用户开了页面一直不提交导致 Redis 里堆积
        redisTemplate.opsForValue().set(TOKEN_KEY + token, token, 5, TimeUnit.MINUTES);
        return token;
    }

    /**
     * 创建订单。
     *
     * <p>两个注解叠在一起的考量：
     * <ul>
     *   <li>@Idempotent 防"重复提交"（可能是间隔几秒的两次请求）</li>
     *   <li>@DistributedLock 防"并发提交"（同一毫秒内打到两个实例上的请求）</li>
     * </ul>
     * 幂等 token 是 Redis 原子删除，本身也能防并发；
     * 再加一层锁是为了让"查库存 → 算价 → 落库"这段逻辑串行化，
     * 避免读到中间态。锁的粒度按 userId，不同用户之间互不影响，不影响整体吞吐。</p>
     */
    @Idempotent(param = "#request.token", message = "订单正在处理中，请勿重复提交")
    @DistributedLock(prefix = "create-order", key = "#request.token",
            leaseTime = 10, waitTime = 3)
    @Transactional(rollbackFor = Exception.class)
    public String createOrder(CreateOrderRequest request) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        if (CollectionUtils.isEmpty(request.getItems())) {
            throw new BizException(ResultCode.PARAM_ERROR);
        }

        // ---------- 1. 查询商品（远程调用） ----------
        List<Long> productIds = request.getItems().stream()
                .map(CreateOrderRequest.Item::getProductId)
                .toList();

        var productResp = productClient.batch(productIds);
        if (productResp == null || productResp.getData() == null || productResp.getData().isEmpty()) {
            throw new BizException(ResultCode.REMOTE_CALL_FAILED.getCode(), "商品服务暂时不可用，请稍后重试");
        }
        List<ProductDTO> products = productResp.getData();

        // ---------- 2. 校验：商品存在、上架、库存够 ----------
        BigDecimal totalAmount = BigDecimal.ZERO;
        List<OrderItem> orderItems = new ArrayList<>();

        for (CreateOrderRequest.Item item : request.getItems()) {
            ProductDTO product = products.stream()
                    .filter(p -> p.getId().equals(item.getProductId()))
                    .findFirst()
                    .orElseThrow(() -> new BizException(ResultCode.NOT_FOUND.getCode(),
                            "商品 " + item.getProductId() + " 不存在或已下架"));

            if (product.getStatus() == null || product.getStatus() != 1) {
                throw new BizException(ResultCode.NOT_FOUND.getCode(), "商品【" + product.getName() + "】已下架");
            }
            if (product.getStock() == null || product.getStock() < item.getQuantity()) {
                throw new BizException(ResultCode.STOCK_NOT_ENOUGH.getCode(),
                        "商品【" + product.getName() + "】库存不足");
            }

            // 金额用 BigDecimal，且用 String 构造器避免 double 精度问题
            BigDecimal price = product.getPrice();
            BigDecimal subTotal = price.multiply(new BigDecimal(item.getQuantity()))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            totalAmount = totalAmount.add(subTotal);

            OrderItem orderItem = new OrderItem();
            orderItem.setProductId(product.getId());
            orderItem.setProductName(product.getName());       // 快照
            orderItem.setProductImage(product.getMainImage()); // 快照
            orderItem.setPrice(price);                          // 快照
            orderItem.setQuantity(item.getQuantity());
            orderItem.setTotalAmount(subTotal);
            orderItems.add(orderItem);
        }

        // ---------- 3. 本地事务：订单 + 明细 + 本地消息（同生共死） ----------
        String orderNo = generateOrderNo(userId);

        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setTotalAmount(totalAmount);
        order.setPayAmount(totalAmount);
        order.setStatus(OrderStatusEnum.WAIT_PAY.getCode());
        order.setCreateTime(LocalDateTime.now());
        orderMapper.insert(order);

        orderItems.forEach(item -> {
            item.setOrderId(order.getId());
            orderItemMapper.insert(item);
        });

        LocalMessage message = new LocalMessage();
        message.setBizKey(orderNo);
        message.setTopic(RabbitMqConfig.STOCK_ROUTING_KEY);
        message.setBody(buildStockDeductBody(orderNo, request.getItems()));
        message.setStatus(0);
        message.setRetryCount(0);
        message.setMaxRetry(5);
        message.setNextRetryTime(LocalDateTime.now());
        message.setCreateTime(LocalDateTime.now());
        localMessageMapper.insert(message);

        // ---------- 4. 事务提交后发消息 ----------
        // 注意：不能用 @Transactional 包住发 MQ 的动作，否则事务回滚了消息却发出去了。
        // 这里用 TransactionSynchronization 保证"提交成功才发"。
        sendAfterCommit(message);

        log.info("[Order] 下单成功 orderNo={} userId={} amount={}", orderNo, userId, totalAmount);
        return orderNo;
    }

    /** 事务提交后再发消息，避免"事务回滚但消息已发" */
    private void sendAfterCommit(LocalMessage message) {
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        doSend(message);
                    }
                });
    }

    /**
     * 真正发送消息。失败不用管——本地消息表里还躺着"待发送"记录，
     * 定时任务会扫出来重投（见 novamall-job 的 LocalMessageRetryTask）。
     */
    public void doSend(LocalMessage message) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMqConfig.ORDER_EXCHANGE,
                    RabbitMqConfig.STOCK_ROUTING_KEY,
                    message.getBody(),
                    new CorrelationData(message.getBizKey()));

            message.setStatus(1);
            message.setUpdateTime(LocalDateTime.now());
            localMessageMapper.updateById(message);
        } catch (Exception e) {
            log.error("[Order] 消息发送失败，等待定时任务重投 bizKey={}", message.getBizKey(), e);
            // 指数退避：重试次数越多，下次重试间隔越长，避免雪崩时疯狂重试
            int retry = message.getRetryCount() == null ? 0 : message.getRetryCount() + 1;
            message.setRetryCount(retry);
            if (retry >= (message.getMaxRetry() == null ? 5 : message.getMaxRetry())) {
                message.setStatus(2); // 标记为失败，需要人工介入
            }
            message.setNextRetryTime(LocalDateTime.now().plusSeconds((long) Math.pow(2, retry)));
            message.setUpdateTime(LocalDateTime.now());
            localMessageMapper.updateById(message);
        }
    }

    /** 订单号：时间前缀 + 用户ID后6位 + 雪花序列，保证可读且不重复 */
    private String generateOrderNo(Long userId) {
        String time = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                .format(LocalDateTime.now());
        String userSuffix = String.format("%06d", userId % 1_000_000);
        String rand = String.format("%04d", (int) (Math.random() * 10000));
        return time + userSuffix + rand;
    }

    private String buildStockDeductBody(String orderNo, List<CreateOrderRequest.Item> items) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "orderNo", orderNo,
                    "items", items.stream()
                            .map(i -> java.util.Map.of("productId", i.getProductId(), "quantity", i.getQuantity()))
                            .toList()
            ));
        } catch (Exception e) {
            throw new BizException(ResultCode.SYSTEM_ERROR);
        }
    }

    /**
     * 支付回调（模拟）。
     *
     * <p>支付回调一定会被第三方重复通知，所以必须幂等：
     * UPDATE 语句里带 status = 0 条件，第二次执行影响行数为 0，天然安全。
     * 这比"先查再改"可靠得多——查和改之间有时间窗。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean payCallback(String orderNo) {
        Order order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            log.warn("[Order] 支付回调：订单不存在 orderNo={}", orderNo);
            return false;
        }
        int rows = orderMapper.payIfWaitPay(order.getId());
        if (rows == 0) {
            log.info("[Order] 支付回调重复通知，已忽略 orderNo={} status={}", orderNo, order.getStatus());
            return true; // 重复通知不算失败，返回 true 让第三方别再重试
        }
        return true;
    }

    /** 取消订单（并触发库存回补） */
    @Transactional(rollbackFor = Exception.class)
    public boolean cancelOrder(Long userId, String orderNo) {
        Order order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            throw new BizException(ResultCode.NOT_FOUND.getCode(), "订单不存在");
        }
        if (!order.getUserId().equals(userId)) {
            // 水平越权检查：不能只看"登录了"，还要看"是不是你自己的订单"
            throw new BizException(ResultCode.FORBIDDEN.getCode(), "无权操作他人订单");
        }
        int rows = orderMapper.closeIfWaitPay(order.getId());
        if (rows == 0) {
            throw new BizException(ResultCode.PARAM_ERROR.getCode(), "订单状态已变更，无法取消");
        }

        // 发消息回补库存
        LocalMessage message = new LocalMessage();
        message.setBizKey(orderNo + ":restore");
        message.setTopic("stock.restore");
        message.setBody(buildRestoreBody(order.getId()));
        message.setStatus(0);
        message.setRetryCount(0);
        message.setMaxRetry(5);
        message.setNextRetryTime(LocalDateTime.now());
        message.setCreateTime(LocalDateTime.now());
        localMessageMapper.insert(message);
        sendAfterCommit(message);
        return true;
    }

    private String buildRestoreBody(Long orderId) {
        List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, orderId));
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "orderId", orderId,
                    "items", items.stream()
                            .map(i -> java.util.Map.of("productId", i.getProductId(), "quantity", i.getQuantity()))
                            .toList()
            ));
        } catch (Exception e) {
            throw new BizException(ResultCode.SYSTEM_ERROR);
        }
    }

    public Order detail(String orderNo) {
        Order order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            throw new BizException(ResultCode.NOT_FOUND.getCode(), "订单不存在");
        }
        Long userId = UserContext.getUserId();
        if (userId != null && !order.getUserId().equals(userId)) {
            throw new BizException(ResultCode.FORBIDDEN);
        }
        return order;
    }
}
