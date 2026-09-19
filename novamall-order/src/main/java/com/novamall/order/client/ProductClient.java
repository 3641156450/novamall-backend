package com.novamall.order.client;

import com.novamall.common.result.R;
import com.novamall.order.client.dto.ProductDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 调用商品服务的 Feign 客户端。
 *
 * <h3>为什么用 Feign 而不是 RestTemplate？</h3>
 * <ul>
 *   <li>声明式：写个接口加注解就行，不用拼 URL、不用手动做序列化/反序列化</li>
 *   <li>内置 Spring Cloud LoadBalancer 做负载均衡，写服务名即可</li>
 *   <li>可以统一配置超时、重试、熔断降级</li>
 * </ul>
 *
 * <h3>三个必须注意的坑</h3>
 * <ol>
 *   <li><b>超时必须配</b>：默认连接超时 10s、读超时 60s，下游一卡就能拖垮整条链路。
 *       本项目在配置文件里改成了 2s / 3s。</li>
 *   <li><b>必须做降级</b>：下游挂了要有兜底。直接返回 null 会一路传导成 NPE，
 *       本项目配了 fallbackFactory，能拿到异常原因再决定怎么兜底。</li>
 *   <li><b>请求头不会自动透传</b>：Feign 发起的是一个全新请求，
 *       原请求里的 Authorization / X-User-Id 都不会带过去，
 *       要透传必须实现 RequestInterceptor（见 FeignRequestInterceptor）。</li>
 * </ol>
 *
 * @author NovaMall
 */
@FeignClient(name = "novamall-product",
        path = "/api/product",
        fallbackFactory = ProductClientFallbackFactory.class)
public interface ProductClient {

    @GetMapping("/{id}")
    R<ProductDTO> detail(@PathVariable("id") Long id);

    @GetMapping("/batch")
    R<List<ProductDTO>> batch(@RequestParam("ids") List<Long> ids);
}
