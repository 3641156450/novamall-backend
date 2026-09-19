package com.novamall.order.client;

import com.novamall.common.result.R;
import com.novamall.order.client.dto.ProductDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Feign 降级工厂。
 *
 * <p>为什么用 FallbackFactory 而不是 fallback？
 * fallback 只能返回一个固定结果，拿不到异常信息；
 * FallbackFactory 能拿到 Throwable，可以根据异常类型区分处理：
 * <ul>
 *   <li>超时 → 可能是下游抖动，提示"稍后重试"</li>
 *   <li>404 → 真的没有这个商品，可以明确报错</li>
 *   <li>连接拒绝 → 下游整个挂了，需要告警</li>
 * </ul>
 * 而且能打日志，出问题时有迹可循。</p>
 *
 * @author NovaMall
 */
@Slf4j
@Component
public class ProductClientFallbackFactory implements FallbackFactory<ProductClient> {

    @Override
    public ProductClient create(Throwable cause) {
        return new ProductClient() {
            @Override
            public R<ProductDTO> detail(Long id) {
                log.error("[Feign] 调用商品服务失败 id={}", id, cause);
                // 降级策略：返回 null + 明确的错误码，让上层决定是重试还是提示用户
                return null;
            }

            @Override
            public R<List<ProductDTO>> batch(List<Long> ids) {
                log.error("[Feign] 批量调用商品服务失败 ids={}", ids, cause);
                return null;
            }
        };
    }
}
