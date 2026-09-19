"""
配置管理。

为什么用 pydantic-settings 而不是直接用 os.environ？
1. 有类型校验和默认值，少写一堆 if "XXX" in os.environ
2. 支持 .env 文件，本地开发方便
3. 配置项集中，别人看一眼就知道这个服务依赖什么
"""
from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """服务配置（环境变量前缀 NOVAMALL_）"""

    model_config = SettingsConfigDict(
        env_prefix="NOVAMALL_",
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # 数据库
    mysql_host: str = "127.0.0.1"
    mysql_port: int = 3306
    mysql_user: str = "root"
    mysql_password: str = "root"
    mysql_db: str = "novamall"

    # Redis（热榜缓存）
    redis_host: str = "127.0.0.1"
    redis_port: int = 6379
    redis_db: int = 0

    # 服务
    app_host: str = "0.0.0.0"
    app_port: int = 8100
    debug: bool = True

    # 热榜缓存时间（秒）
    hot_cache_ttl: int = 300

    @property
    def mysql_url(self) -> str:
        return (
            f"mysql+pymysql://{self.mysql_user}:{self.mysql_password}"
            f"@{self.mysql_host}:{self.mysql_port}/{self.mysql_db}"
            f"?charset=utf8mb4"
        )


@lru_cache
def get_settings() -> Settings:
    """缓存单例，避免每次请求都重新解析环境变量"""
    return Settings()
