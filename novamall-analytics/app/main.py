"""
NovaMall 数据分析服务（FastAPI）。

定位：Java 主链路之外的**旁路分析服务**。
- 输入：MySQL 里的订单/商品数据
- 输出：热榜、日报、趋势预测，通过 HTTP 提供给 Java 服务和大屏使用

为什么单独起一个 Python 服务而不是写成脚本？
- 脚本只能手动跑，服务可以被定时任务 / Java 服务随时调用
- FastAPI 自带 OpenAPI 文档（/docs），前后端联调不用额外写接口文档
- 天然支持异步，IO 密集场景（查库、等 Redis）吞吐很好

启动：
    pip install -r requirements.txt
    uvicorn app.main:app --reload --port 8100
文档：
    http://127.0.0.1:8100/docs
"""
from __future__ import annotations

from datetime import datetime

from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from app.config import get_settings
from app.services import report

settings = get_settings()

app = FastAPI(
    title="NovaMall Analytics",
    description="新星商城数据分析服务：热销榜 / 销售日报 / 趋势预测",
    version="1.0.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


# ------------------------------------------------------------
# 响应模型（Pydantic）
# ------------------------------------------------------------
class ApiResponse(BaseModel):
    """统一响应体，和 Java 侧的 R<T> 保持一致，前端一套逻辑解析"""

    code: int = 200
    message: str = "success"
    data: object | None = None
    timestamp: int = Field(default_factory=lambda: int(datetime.now().timestamp() * 1000))


def ok(data=None) -> ApiResponse:
    return ApiResponse(code=200, message="success", data=data)


# ------------------------------------------------------------
# 接口
# ------------------------------------------------------------
@app.get("/health", summary="健康检查")
def health() -> ApiResponse:
    return ok({"status": "UP", "service": "novamall-analytics"})


@app.get("/api/analytics/hot-products", summary="热销商品榜")
def hot_products(
    days: int = Query(7, ge=1, le=365, description="统计最近 N 天"),
    limit: int = Query(10, ge=1, le=100, description="返回条数"),
) -> ApiResponse:
    try:
        return ok(report.get_hot_products(days=days, limit=limit))
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=500, detail=f"查询失败：{exc}") from exc


@app.get("/api/analytics/daily-sales", summary="每日销售趋势")
def daily_sales(days: int = Query(30, ge=1, le=365)) -> ApiResponse:
    try:
        return ok(report.get_daily_sales(days=days))
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=500, detail=f"查询失败：{exc}") from exc


@app.get("/api/analytics/category-distribution", summary="品类销售占比")
def category_distribution(days: int = Query(30, ge=1, le=365)) -> ApiResponse:
    try:
        return ok(report.get_category_distribution(days=days))
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=500, detail=f"查询失败：{exc}") from exc


@app.get("/api/analytics/forecast", summary="未来 7 天 GMV 预测")
def forecast(days: int = Query(30, ge=7, le=365, description="用最近 N 天历史数据")) -> ApiResponse:
    try:
        return ok(report.forecast_next_week(days=days))
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=500, detail=f"预测失败：{exc}") from exc


@app.get("/api/analytics/dashboard", summary="大屏聚合接口（一次拿全）")
def dashboard(days: int = Query(7, ge=1, le=365)) -> ApiResponse:
    """
    大屏接口把多个报表一次返回。

    为什么要合并？大屏一打开要调 5、6 个接口，
    每个都是一次 HTTP 往返（还有 DNS、TCP、TLS 开销），
    合并成一个能显著降低首屏时间。这是很常见的接口聚合优化手段。
    """
    try:
        return ok(
            {
                "hotProducts": report.get_hot_products(days=days, limit=10),
                "dailySales": report.get_daily_sales(days=max(days, 30)),
                "categoryDistribution": report.get_category_distribution(days=days),
            }
        )
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=500, detail=f"查询失败：{exc}") from exc


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host=settings.app_host, port=settings.app_port)
