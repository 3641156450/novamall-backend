"""
数据库访问层（SQLAlchemy 2.0 写法）。

几个和 Java 那边不一样的地方，面试时可以对比着讲：
1. Java 用连接池（HikariCP）管理连接，Python 这边 SQLAlchemy 的 Engine 自带连接池，
   通过 QueuePool 控制大小，思维是相通的
2. Java 用 MyBatis 写 SQL，Python 用 SQLAlchemy Core / 原生 SQL，
   复杂分析场景一般直接写 SQL 再交给 Pandas 处理
3. 连接用完必须关：这里用 contextmanager + try/finally 保证，
   和 Java 的 try-with-resources 是一个道理
"""
from contextlib import contextmanager
from typing import Iterator

from sqlalchemy import create_engine, text
from sqlalchemy.engine import Engine

from app.config import get_settings


_engine: Engine | None = None


def get_engine() -> Engine:
    """全局单例 Engine（内部自带连接池，不要每次新建）"""
    global _engine
    if _engine is None:
        settings = get_settings()
        _engine = create_engine(
            settings.mysql_url,
            pool_size=10,
            max_overflow=20,
            pool_recycle=3600,  # 防止 MySQL 的 wait_timeout 把连接掐断
            pool_pre_ping=True,  # 每次取连接前先 ping 一下，断了自动重连
            echo=settings.debug,
        )
    return _engine


@contextmanager
def get_connection() -> Iterator:
    """获取连接，用完自动归还连接池"""
    conn = get_engine().connect()
    try:
        yield conn
    finally:
        conn.close()


def query_to_dicts(sql: str, params: dict | None = None) -> list[dict]:
    """执行 SQL 并返回 dict 列表（方便直接喂给 Pandas）"""
    with get_connection() as conn:
        result = conn.execute(text(sql), params or {})
        columns = list(result.keys())
        return [dict(zip(columns, row)) for row in result.fetchall()]
