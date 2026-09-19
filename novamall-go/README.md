# novamall-go · Go 模块

这个目录是项目里的 **Go 点缀**，两个独立的 module，互不依赖。

| 目录 | 作用 | 外部依赖 | 是否需要装 Go |
|---|---|---|---|
| `bench/` | HTTP 压测工具（goroutine + channel + WaitGroup） | **零依赖** | 是，但 `go run .` 即可 |
| `shortlink/` | 短链接服务（Gin + Redis） | gin、go-redis | 是 |

> 如果你机器上没装 Go（1.22+），这两个目录可以直接跳过，不影响 Java 主体运行。
> 简历上写"了解 Go，能写并发压测工具"即可，**不要**写"精通 Go"。

---

## 1. bench — 零依赖压测工具

```bash
cd novamall-go/bench

# 商品详情接口：200 并发，共 10000 个请求
go run . -url http://127.0.0.1:8080/api/product/10001 -c 200 -n 10000

# 秒杀接口：500 并发，持续压 30 秒
go run . -url http://127.0.0.1:8084/api/seckill/1/10001 -c 500 -d 30s -X POST

# 带 JWT 的下单接口
go run . -url http://127.0.0.1:8080/api/order/create \
         -X POST -H "Authorization: Bearer eyJhbGci..." -c 50 -n 500
```

输出示例：

```
========== 压测报告 ==========
目标地址   : http://127.0.0.1:8080/api/product/10001
并发数     : 200
总耗时     : 8.412s
总请求数   : 10000
成功/失败  : 10000 / 0
QPS        : 1188.73
平均耗时   : 12.4ms
P50        : 8.1ms
P95        : 31.7ms
P99        : 74.2ms
状态码分布 :
   200 : 10000
==============================
```

**面试怎么讲**：
1. 用 goroutine 池 + channel 做生产者-消费者模型，天然限流
2. 必须 `io.Copy(io.Discard, resp.Body)` 读掉响应体，否则连接不复用，跑一会儿就 `too many open files`
3. 调优了 `MaxIdleConnsPerHost`，否则测的是"建 TCP 连接"的性能而不是接口性能
4. 关注 P99 而不是平均值——平均值会被大量快请求拉低，掩盖长尾

---

## 2. shortlink — 短链服务

```bash
cd novamall-go/shortlink
go mod tidy
go run . -addr :8200 -redis 127.0.0.1:6379 -base http://s.novamall.com
```

```bash
# 生成短链
curl -X POST http://127.0.0.1:8200/api/shorten \
     -H "Content-Type: application/json" \
     -d '{"url":"https://www.novamall.com/product/10001","ttlHours":720}'

# 访问（302 跳转）
curl -i http://127.0.0.1:8200/aB3dEf

# 查看访问量
curl http://127.0.0.1:8200/api/stats/aB3dEf
```

**设计点**：
- 62 进制字符集，6 位短码 ≈ 568 亿组合
- 用随机码而非自增 ID：自增可枚举，别人能遍历你的短链
- `SetNX` 而非 `Set`：短码撞了就重试，绝不覆盖别人的数据
- 302 而非 301：301 浏览器会永久缓存，之后统计不到访问量
- 访问计数异步 goroutine 累加，不阻塞跳转
- 优雅停机：收到 SIGTERM 后等 5 秒让在途请求处理完（K8s 滚动发布必需）

---

## 为什么 Go 适合这类场景

| 维度 | Java (Spring Boot) | Go |
|---|---|---|
| 启动时间 | 几秒 ~ 几十秒 | 几十毫秒 |
| 常驻内存 | 200MB~1GB（JVM） | 10~50MB |
| 并发模型 | 线程（1 线程 ≈ 1MB 栈） | 协程（1 goroutine ≈ 2KB 栈，动态增长） |
| 部署产物 | jar + JVM | 单个二进制，镜像可 < 20MB |
| 适合场景 | 复杂业务、生态成熟、团队协作 | 中间件、网关、CLI 工具、高并发 IO |

**选型结论**：业务主链路用 Java（生态、可维护性、招人容易），
基础设施类（网关、Agent、压测工具）用 Go。**不要为了用 Go 而用 Go。**
