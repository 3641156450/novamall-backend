"""
报表计算：销量分析、热榜、销售趋势。

为什么这部分用 Python 而不是 Java？
- 数据分析生态（Pandas / NumPy / Matplotlib）Python 是碾压级优势，
  同样的聚合逻辑 Java 要写几十行 Stream，Pandas 一行 groupby 就完事
- 这类任务是离线/准实时的，对延迟不敏感，用不上 Java 的性能优势
- 选型原则：**用合适的工具做合适的事**，不是所有东西都要塞进 Java

但要注意：Python 服务不能放在核心交易链路上（下单、支付），
那些场景必须 Java 的低延迟 + 强类型保障。本项目里 Python 只做分析，
Java 通过 HTTP 拉取结果，两边解耦。
"""
from __future__ import annotations

import pandas as pd

from app.database import query_to_dicts

# ------------------------------------------------------------
# SQL：把聚合尽量下推到数据库做
# ------------------------------------------------------------
# 为什么不在 Python 里拉全表再 groupby？
# 数据库有索引、有优化器，聚合速度快几个数量级，
# 而且只返回聚合后的几十行，网络传输量小。
# 这是"计算向数据移动"而不是"数据向计算移动"的原则。
SQL_HOT_PRODUCTS = """
    SELECT
        oi.product_id                     AS product_id,
        oi.product_name                   AS product_name,
        SUM(oi.quantity)                  AS total_quantity,
        SUM(oi.total_amount)              AS total_amount,
        COUNT(DISTINCT o.id)              AS order_count
    FROM t_order_item oi
    INNER JOIN t_order o ON o.id = oi.order_id
    WHERE o.status IN (1, 2, 3)
      AND o.create_time >= DATE_SUB(NOW(), INTERVAL :days DAY)
    GROUP BY oi.product_id, oi.product_name
    ORDER BY total_quantity DESC
    LIMIT :limit
"""

SQL_DAILY_SALES = """
    SELECT
        DATE(o.create_time)   AS stat_date,
        COUNT(DISTINCT o.id)  AS order_count,
        SUM(o.pay_amount)     AS gmv,
        SUM(oi.quantity)      AS item_count
    FROM t_order o
    LEFT JOIN t_order_item oi ON oi.order_id = o.id
    WHERE o.status IN (1, 2, 3)
      AND o.create_time >= DATE_SUB(NOW(), INTERVAL :days DAY)
    GROUP BY DATE(o.create_time)
    ORDER BY stat_date
"""

SQL_CATEGORY_DISTRIBUTION = """
    SELECT
        p.category_id            AS category_id,
        SUM(oi.total_amount)     AS total_amount,
        SUM(oi.quantity)         AS total_quantity
    FROM t_order_item oi
    INNER JOIN t_order o ON o.id = oi.order_id
    INNER JOIN t_product p ON p.id = oi.product_id
    WHERE o.status IN (1, 2, 3)
      AND o.create_time >= DATE_SUB(NOW(), INTERVAL :days DAY)
    GROUP BY p.category_id
    ORDER BY total_amount DESC
"""


def get_hot_products(days: int = 7, limit: int = 10) -> list[dict]:
    """热销商品 TopN"""
    rows = query_to_dicts(
        SQL_HOT_PRODUCTS,
        {"days": days, "limit": limit},
    )
    if not rows:
        return []

    df = pd.DataFrame(rows)
    # 数据库返回的是 Decimal，转成 float 才能做后续计算 / JSON 序列化
    df["total_amount"] = df["total_amount"].astype(float)
    df["total_quantity"] = df["total_quantity"].astype(int)

    # 计算占比，前端画饼图要用
    total = df["total_amount"].sum()
    df["amount_ratio"] = (df["total_amount"] / total).round(4) if total else 0.0

    return df.to_dict(orient="records")


def get_daily_sales(days: int = 30) -> list[dict]:
    """每日销售趋势"""
    rows = query_to_dicts(SQL_DAILY_SALES, {"days": days})
    if not rows:
        return []

    df = pd.DataFrame(rows)
    df["gmv"] = df["gmv"].fillna(0).astype(float)
    df["order_count"] = df["order_count"].astype(int)
    df["stat_date"] = df["stat_date"].astype(str)

    # 7 日移动平均：平滑掉周末/促销带来的波动，看清真实趋势
    df["gmv_ma7"] = df["gmv"].rolling(window=7, min_periods=1).mean().round(2)

    # 环比：今天比昨天涨了多少
    df["gmv_dod"] = df["gmv"].pct_change().fillna(0).round(4)

    return df.to_dict(orient="records")


def get_category_distribution(days: int = 30) -> list[dict]:
    """品类销售占比"""
    rows = query_to_dicts(SQL_CATEGORY_DISTRIBUTION, {"days": days})
    if not rows:
        return []
    df = pd.DataFrame(rows)
    df["total_amount"] = df["total_amount"].astype(float)
    df["total_quantity"] = df["total_quantity"].astype(int)
    return df.to_dict(orient="records")


def moving_average_forecast(history: list[float], window: int = 7, horizon: int = 7) -> list[float]:
    """
    移动平均法做销量预测（最简单的时间序列预测）。

    为什么要写这个？
    - 展示"除了 CRUD 还能做点数据活儿"
    - 移动平均是可解释性最强的预测方法，面试时能说清楚原理
    - 真实场景会用 Prophet / ARIMA / LSTM，但那些是算法工程师的活儿，
      后端工程师的价值在于能把模型结果**工程化落地**（定时跑、存结果、提供接口）

    局限性要主动说：
    - 无法捕捉趋势和季节性（比如"每年双11暴涨"）
    - 对突发的促销/热点反应迟钝
    """
    if not history:
        return []
    if len(history) < window:
        window = len(history)

    result: list[float] = []
    series = list(history)
    for _ in range(horizon):
        next_value = sum(series[-window:]) / window
        result.append(round(next_value, 2))
        series.append(next_value)
    return result


def forecast_next_week(days: int = 30) -> dict:
    """基于历史 GMV 预测未来 7 天"""
    history_rows = get_daily_sales(days)
    if not history_rows:
        return {"history": [], "forecast": [], "window": 7}

    gmv_series = [float(r["gmv"]) for r in history_rows]
    forecast = moving_average_forecast(gmv_series, window=7, horizon=7)
    return {
        "history": gmv_series,
        "forecast": forecast,
        "window": 7,
        "note": "移动平均预测，未考虑促销与季节性因素，仅供参考",
    }
