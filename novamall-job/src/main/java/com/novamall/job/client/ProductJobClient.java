package com.novamall.job.client;

import com.novamall.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * 调用商品服务的 Feign 接口。
 *
 * <p>任务服务只通过接口调用业务服务，不直接连业务库——
 * 这样业务服务改表结构时，任务服务不用跟着改。
 * 这是微服务"数据库私有（Database per Service）"原则的体现。</p>
 *
 * @author NovaMall
 */
@FeignClient(name = "novamall-product", path = "/api/product")
public interface ProductJobClient {

    @PostMapping("/cache/warmup")
    R<String> warmUp();
}
