# novamall-analytics · Python 数据分析服务

## 是什么

Java 主链路之外的**旁路分析服务**：读 MySQL 的订单数据，
用 Pandas 做聚合，通过 HTTP 提供给 Java 服务和大屏。

```
MySQL (t_order / t_order_item / t_product)
        │ SQLAlchemy
        ▼
   FastAPI :8100  ──► Pandas 聚合
        │ JSON
        ▼
   Java 服务 / 前端大屏
```

## 快速开始

```bash
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8100
```

打开 http://127.0.0.1:8100/docs 看自动生成的接口文档。

## 接口

| 接口 | 说明 |
|---|---|
| `GET /api/analytics/hot-products?days=7&limit=10` | 热销商品榜 |
| `GET /api/analytics/daily-sales?days=30` | 每日销售趋势（含 7 日移动平均、环比） |
| `GET /api/analytics/category-distribution?days=30` | 品类销售占比 |
| `GET /api/analytics/forecast?days=30` | 未来 7 天 GMV 预测 |
| `GET /api/analytics/dashboard?days=7` | 大屏聚合接口（一次拿全） |
| `GET /health` | 健康检查 |

环境变量（`NOVAMALL_` 前缀，或写在 `.env` 里）：

```
NOVAMALL_MYSQL_HOST=127.0.0.1
NOVAMALL_MYSQL_PORT=3306
NOVAMALL_MYSQL_USER=root
NOVAMALL_MYSQL_PASSWORD=root
NOVAMALL_MYSQL_DB=novamall
```

## 跑测试

```bash
python -m pytest app/tests -v
```

## 为什么要单独起一个 Python 服务

| 维度 | 说明 |
|---|---|
| 生态 | 数据分析（Pandas / NumPy）Python 是碾压级优势，同样逻辑 Java 要写几十行 Stream |
| 性能 | 这类任务是离线/准实时的，用不上 Java 的低延迟优势 |
| 边界 | **Python 服务不能放在核心交易链路上**（下单、支付），那些必须 Java |
| 原则 | 用合适的工具做合适的事，不是所有东西都要塞进 Java |

## 代码要点

- **聚合下推到数据库**：不在 Python 里拉全表再 groupby。
  数据库有索引和优化器，快几个数量级，而且只返回聚合后的几十行。
- **`pool_pre_ping=True`**：MySQL 的 `wait_timeout` 会掐断空闲连接，
  取连接前先 ping 一下能自动重连。
- **`Decimal` 转 `float`**：MySQL 的 DECIMAL 在 Python 里是 Decimal 类型，
  不能直接 JSON 序列化，要先转。
- **移动平均预测**：可解释性最强的预测方法，面试时能说清原理。
  局限是无法捕捉趋势和季节性（比如双 11），要主动说出来。
