# 阅读说明
# 创建异步 SQLAlchemy 引擎、连接配置与会话工厂，事务由具体仓储声明。

from sqlalchemy import URL, event
from sqlalchemy.ext.asyncio import AsyncEngine, async_sessionmaker, create_async_engine

from app.config import Settings


def create_database(settings: Settings) -> AsyncEngine:
    if not settings.mysql_host or not settings.mysql_username:
        raise RuntimeError("AI MySQL connection is not configured")
    url = URL.create(
        "mysql+aiomysql",
        username=settings.mysql_username,
        password=settings.mysql_password.get_secret_value() if settings.mysql_password else "",
        host=settings.mysql_host,
        port=settings.mysql_port,
        database=settings.mysql_database,
        query={"charset": "utf8mb4"},
    )
    engine = create_async_engine(
        url,
        pool_pre_ping=True,
        pool_recycle=1800,
        pool_size=5,
        max_overflow=5,
        isolation_level="READ COMMITTED",
        connect_args={"connect_timeout": 5},
        echo=False,
        hide_parameters=True,
    )

    @event.listens_for(engine.sync_engine, "connect")
    def configure_connection(connection, _record):
        cursor = connection.cursor()
        cursor.execute("SET time_zone = '+00:00'")
        cursor.execute("SET SESSION innodb_lock_wait_timeout = 5")
        cursor.close()

    return engine


def session_factory(engine: AsyncEngine):
    return async_sessionmaker(engine, expire_on_commit=False)
