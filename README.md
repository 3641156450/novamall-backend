# NovaMall 新星商城 · 分布式电商后端

> 一个**能跑起来、能讲清楚**的后端练手项目。
> Java（Spring Boot 3 + Spring Cloud Alibaba）为主，Python（FastAPI）做数据分析，Go 写压测工具与短链服务。

---

## 一、先说清楚：这个项目是什么，不是什么

**它是什么**：一个人从零设计并实现的完整分布式电商后端，覆盖注册登录、商品、下单、秒杀、定时任务、数据分析。
每个技术点都有**可运行的代码**和**写在注释里的原理说明**，能经得起面试官追问。

**它不是什么**：不是生产级系统。没有真实的支付、物流、风控；
没有做服务的多副本部署和压测到几万 QPS；部分实现（如布隆过滤器、分布式锁）是**为了讲清原理手写**的，
生产环境应该直接用成熟组件（Redisson、Sentinel、XXL-Job）。这些取舍我都写在代码注释和
[技术亮点与踩坑](docs/02-技术亮点与踩坑.md) 里了。

> **给应届生的建议**：面试时**主动说出项目的边界和局限**，比硬吹"支持百万并发"更让面试官放心。
> 一来吹牛一问就露馅，二来能看出你有判断力。文档 [03-面试问答话术](docs/03-面试问答话术.md) 里有现成的话术。

---

## 二、技术栈一览

### 后端主栈（Java）

| 分类 | 技术 | 项目中用在哪 |
|---|---|---|
| 基础框架 | Spring Boot 3.2.5 / JDK 17 | 全部服务 |
| 微服务 | Spring Cloud 2023 + Spring Cloud Alibaba 2023 | Nacos 注册中心、OpenFeign 远程调用 |
| 网关 | Spring Cloud Gateway | 统一入口、JWT 鉴权、路由、跨域 |
| ORM | MyBatis-Plus 3.5 | 单表 CRUD、分页插件、乐观锁 |
| 数据库 | MySQL 8.0 | 主从索引设计、逻辑删除 |
| 缓存 | Redis 7 + Caffeine | 多级缓存、分布式锁、限流、布隆过滤器 |
| 消息队列 | RabbitMQ 3.13 | 异步下单、削峰、死信队列 |
| 认证 | JWT (jjwt 0.12) + BCrypt | 无状态登录、退出黑名单 |
| 文档 | SpringDoc OpenAPI 3 | 自动生成接口文档 |
| 监控 | Spring Boot Actuator | 健康检查、指标 |

### 辅助栈

| 技术 | 用途 |
|---|---|
| Python 3.11 + FastAPI + Pandas | 数据分析服务：热销榜、销售日报、GMV 预测 |
| Go 1.22 | 零依赖 HTTP 压测工具 + Gin 短链服务 |
| Docker Compose | 一键拉起 MySQL / Redis / RabbitMQ / Nacos |
| GitHub Actions | push 自动编译 + 跑单测 |
| JUnit 5 + Mockito | 单元测试 |

---

## 三、系统架构

```
                          ┌─────────────┐
                          │   客户端     │
                          └──────┬──────┘
                                 │ HTTP
                          ┌──────▼──────────────────────────┐
                          │  novamall-gateway :8080          │
                          │  · JWT 校验 / 黑名单             │
                          │  · 路由转发 / 跨域 / 限流         │
                          └──┬────┬────┬────┬────────────────┘
                             │    │    │    │
        ┌────────────────────┘    │    │    └──────────────────┐
        │           ┌─────────────┘    └─────────┐             │
   ┌────▼─────┐ ┌───▼──────┐ ┌──────▼─────┐ ┌────▼─────┐ ┌─────▼────┐
   │  auth    │ │ product  │ │   order    │ │ seckill  │ │   job    │
   │  :8081   │ │  :8082   │ │   :8083    │ │  :8084   │ │  :8085   │
   │ 注册登录 │ │ 商品缓存 │ │ 下单/事务  │ │ 秒杀     │ │ 定时任务 │
   └────┬─────┘ └───┬──────┘ └──────┬─────┘ └────┬─────┘ └─────┬────┘
        │           │               │            │             │
        └───────────┴───────┬───────┴────────────┴─────────────┘
                            │
        ┌───────────────────┼───────────────────┬─────────────────┐
   ┌────▼────┐      ┌───────▼───────┐   ┌───────▼──────┐   ┌──────▼─────┐
   │ MySQL 8 │      │   Redis 7     │   │ RabbitMQ 3   │   │Nacos 2.3   │
   │ :3306   │      │   :6379       │   │ :5672/:15672 │   │  :8848     │
   └─────────┘      └───────────────┘   └──────────────┘   └────────────┘
                                                    ▲
                                                    │ HTTP 拉取报表
                                          ┌─────────┴──────────┐
                                          │ novamall-analytics │
                                          │ FastAPI :8100      │
                                          └────────────────────┘
```

详细设计见 [01-架构设计.md](docs/01-架构设计.md)。

---

## 四、模块说明

| 模块 | 端口 | 核心看点 |
|---|---|---|
| `novamall-common` | - | 统一响应/异常、雪花算法、**注解式分布式锁**、**注解式幂等**、**注解式限流（令牌桶）**、RBAC 注解、链路 traceId |
| `novamall-gateway` | 8080 | Gateway 全局过滤器、JWT 校验、token 黑名单、跨域 |
| `novamall-auth` | 8081 | BCrypt 密码、JWT 签发、登录失败锁定、RBAC |
| `novamall-product` | 8082 | **三级缓存**、**缓存穿透/击穿/雪崩**全套防护、**布隆过滤器**、乐观锁扣库存、MQ 消费端幂等 |
| `novamall-order` | 8083 | **幂等 token**、**本地消息表 + MQ 最终一致**、Feign 调用与降级、超时关单、消息重投 |
| `novamall-seckill` | 8084 | **Redis Lua 原子扣减**、**MQ 削峰**、用户维度限流、库存预热、一人一单 |
| `novamall-job` | 8085 | 分布式调度锁、缓存预热（通过 Feign 调用业务服务） |
| `novamall-analytics` | 8100 | FastAPI + Pandas：热销榜、销售趋势、移动平均预测 |
| `novamall-go` | 8200 | 零依赖压测工具 + Gin 短链服务 |

---

## 五、快速开始

### 5.1 环境准备

| 软件 | 版本要求 | 说明 |
|---|---|---|
| JDK | **17**（必须） | 项目用了 Spring Boot 3，不支持 JDK 8。本机是 JDK 8 的话需要装一个 17 |
| Maven | 3.8+ | 构建工具 |
| Docker Desktop | 任意新版 | 拉起 MySQL / Redis / RabbitMQ / Nacos |
| Python | 3.10+ | 可选，跑数据分析服务 |
| Go | 1.22+ | 可选，跑压测工具和短链 |

> **JDK 17 安装（Windows）**：推荐用 [ scoop ](https://scoop.sh/) 或直接下载
> [Temurin JDK 17](https://adoptium.net/temurin/releases/?version=17) 的 msi 安装包，装完在命令行确认 `java -version`。
> 如果机器上有多个 JDK，用 `JAVA_HOME` 环境变量指向 17 的安装目录。

### 5.2 拉起基础设施

```bash
cd novamall-backend/docker
docker compose up -d

# 确认都起来了
docker compose ps
```

服务地址：

| 服务 | 地址 | 账号 |
|---|---|---|
| MySQL | `127.0.0.1:3306` | root / root |
| Redis | `127.0.0.1:6379` | 无密码 |
| RabbitMQ 管理台 | http://localhost:15672 | guest / guest |
| Nacos 控制台 | http://localhost:8848/nacos | nacos / nacos |

数据库表会在 MySQL 首次启动时自动创建（`sql/novamall.sql` 挂载到了 init 目录）。
如果没自动执行，手动跑一次：

```bash
mysql -uroot -proot < sql/novamall.sql
```

### 5.3 编译 & 启动

```bash
cd novamall-backend
mvn clean install -DskipTests
```

然后按顺序启动（每个开一个终端）：

```bash
# 1. 网关
mvn -pl novamall-gateway spring-boot:run
# 2. 认证
mvn -pl novamall-auth spring-boot:run
# 3. 商品
mvn -pl novamall-product spring-boot:run
# 4. 订单
mvn -pl novamall-order spring-boot:run
# 5. 秒杀（可选）
mvn -pl novamall-seckill spring-boot:run
# 6. 任务（可选）
mvn -pl novamall-job spring-boot:run
```

也可以直接用 IDE 打开各个 `XxxApplication` 类点运行。

### 5.4 造点数据

```bash
cd novamall-analytics  # 只用它的脚本目录，依赖 pip 安装见下
python ../scripts/generate_test_data.py --orders 3000 --out data.sql
mysql -uroot -proot novamall < data.sql
```

### 5.5 验证一下

```bash
# 1. 注册
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"zhangsan","password":"12345678","phone":"13800138000"}'

# 2. 登录，拿到 token
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"zhangsan","password":"12345678"}'

# 3. 查商品（不用登录）
curl http://localhost:8080/api/product/10001

# 4. 拿幂等 token（需要登录）
curl http://localhost:8080/api/order/token -H "Authorization: Bearer <token>"

# 5. 下单
curl -X POST http://localhost:8080/api/order/create \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"token":"<幂等token>","addressId":1,"items":[{"productId":10001,"quantity":1}]}'

# 6. 秒杀（先预热再开活动）
curl -X POST "http://localhost:8080/api/seckill/admin/warmup?activityId=1&productId=10001&stock=100"
curl -X POST "http://localhost:8080/api/seckill/admin/start?activityId=1&seconds=600"
curl -X POST http://localhost:8080/api/seckill/1/10001 -H "Authorization: Bearer <token>"
```

接口文档：http://localhost:8082/swagger-ui.html （商品服务其他服务把端口换一下即可）

### 5.6 启动 Python 分析服务（可选）

```bash
cd novamall-analytics
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8100
# 打开 http://127.0.0.1:8100/docs 看接口文档
```

### 5.7 跑压测（可选，需要 Go）

```bash
cd novamall-go/bench
go run . -url http://127.0.0.1:8080/api/product/10001 -c 200 -n 10000
```

---

## 六、这个项目里最值得讲的 8 个点

按"面试命中率"排序，每个都有对应代码：

1. **缓存三兄弟（穿透 / 击穿 / 雪崩）** — 不是背概念，是三种问题分别用布隆过滤器、互斥锁、随机 TTL 解决
   → `ProductService`、`BloomFilterService`
2. **秒杀不超卖** — Redis Lua 把"判断库存 + 判断重复购买 + 扣减"合成一个原子操作
   → `SeckillLuaScripts`
3. **分布式事务** — 本地消息表 + MQ，讲得清为什么不用 Seata
   → `LocalMessage`、`OrderService`
4. **幂等设计** — token + Redis Lua 原子消费 + 数据库唯一索引兜底
   → `Idempotent`、`IdempotentAspect`
5. **分布式锁** — 手写 Lua 实现，能说出"为什么解锁要判断 value""主从切换会丢锁"
   → `RedisLockHelper`
6. **限流** — Redis 令牌桶，能对比固定窗口 / 滑动窗口 / 漏桶
   → `RateLimitAspect`
7. **MQ 可靠性** — 生产者确认 + 手动 ACK + 死信队列 + 消费端幂等
   → `RabbitMqConfig`、`StockDeductConsumer`
8. **多级缓存** — Caffeine + Redis，说得出本地缓存的代价（短暂不一致）

完整清单和"为什么这么做"见 [02-技术亮点与踩坑.md](docs/02-技术亮点与踩坑.md)。

---

## 七、常见问题

**Q：我机器内存只有 8G，跑不动 Nacos 怎么办？**
A：在 `docker/docker-compose.yml` 里把 `nacos` 那段注释掉，
然后给每个服务加参数 `-Dspring.cloud.nacos.discovery.enabled=false`，
Feign 调用会退化成直连（需要在 `@FeignClient` 上写死 `url`）。
更简单的方式：只启动 gateway + auth + product 三个服务，先把缓存那套跑通演示。

**Q：为什么不用 Spring Security？**
A：认证在网关统一做了，服务内用 `@RequireRole` 注解做授权。
再叠一层 Security 过滤器链会重复且难排查。README 的架构文档里写了
"如果要换成 Spring Security 方案"的要点，面试时可以说得出为什么这么选。

**Q：代码能直接编译通过吗？**
A：能。本项目已于 2026-09-19 在 JDK 17 + Maven 3.9.9 环境下完成验证：
`mvn clean compile` 全部 8 个模块 **BUILD SUCCESS**，`mvn test` 的 9 个单元测试全部通过
（雪花 ID 并发唯一性 5 个、秒杀 Lua 脚本 4 个）。Go 部分通过 `go build` + `go vet`，Python 部分通过 `py_compile`。

---

## 八、目录结构

```
novamall-backend/
├── README.md                   ← 你正在看
├── pom.xml                     ← 父 POM，统一版本
├── docs/
│   ├── 01-架构设计.md
│   ├── 02-技术亮点与踩坑.md
│   ├── 03-面试问答话术.md       ← 求职必看
│   ├── 04-简历模板.md           ← 直接抄
│   └── 05-压测报告.md
├── sql/novamall.sql            建表 + 初始数据
├── docker/docker-compose.yml   一键拉起依赖
├── scripts/                    造数脚本
├── .github/workflows/ci.yml    CI
├── novamall-common/            公共模块（重点看）
├── novamall-gateway/
├── novamall-auth/
├── novamall-product/           缓存重点
├── novamall-order/             分布式事务重点
├── novamall-seckill/           高并发重点
├── novamall-job/
├── novamall-analytics/         Python
└── novamall-go/                Go
```

---

## 九、接下来可以怎么加（写在简历里更值钱）

- [ ] 接入 **Sentinel** 做熔断降级，对比自研注解限流
- [ ] 用 **Canal + MQ** 监听 binlog 自动失效缓存，替代延迟双删
- [ ] 接入 **XXL-Job** 替换自研调度锁，做分片广播
- [ ] 用 **RocketMQ 延迟消息** 替换定时扫表关单
- [ ] 加 **Prometheus + Grafana** 监控大盘
- [ ] 加 **SkyWalking** 做分布式链路追踪
- [ ] Elasticsearch 做商品搜索
- [ ] 写一个 K8s Deployment + Helm Chart

把这些做成"TODO 清单"放在 README 里，本身就是加分项——
说明你知道下一步该往哪走。

---

## License

MIT
