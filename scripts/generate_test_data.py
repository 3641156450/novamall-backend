# -*- coding: utf-8 -*-
"""
测试数据生成脚本。

为什么要造数据？
- 空数据库跑不出任何报表，也无法验证缓存/分页/索引的效果
- 面试演示时有几千条真实感的数据，效果完全不一样
- 压测需要大量数据，否则测出来的是"空表性能"，没有参考价值

用法：
    python scripts/generate_test_data.py --users 200 --orders 5000
    python scripts/generate_test_data.py --clean   # 只清空业务数据
"""
from __future__ import annotations

import argparse
import random
import sys
from datetime import datetime, timedelta

# 商品池（对应 sql/novamall.sql 里的初始商品）
PRODUCTS = [
    (10001, "NovaPhone 15 Pro", 5999.00),
    (10002, "NovaBook Air 13", 4999.00),
    (10003, "NovaBuds 无线耳机", 399.00),
    (10004, "NovaWatch S8", 1299.00),
    (10005, "Nova 空气炸锅", 299.00),
]

STATUS_PAID = 1
STATUS_SHIPPED = 2
STATUS_FINISHED = 3
STATUS_WAIT_PAY = 0
STATUS_CLOSED = 4


def build_insert_sql(user_count: int, order_count: int, days: int) -> list[str]:
    """生成批量 INSERT 语句（不直接执行，方便人工检查后再跑）"""
    sqls: list[str] = []
    now = datetime.now()

    # ---------- 订单 ----------
    values: list[str] = []
    item_values: list[str] = []
    order_id = 100000
    item_id = 200000

    for i in range(order_count):
        order_id += 1
        user_id = random.randint(3, 3 + user_count)  # 1、2 是 admin/test，跳过
        created = now - timedelta(days=random.randint(0, days),
                                  hours=random.randint(0, 23),
                                  minutes=random.randint(0, 59))
        # 80% 已支付，15% 待付款，5% 已关闭 —— 模拟真实分布
        roll = random.random()
        if roll < 0.80:
            status = random.choice([STATUS_PAID, STATUS_SHIPPED, STATUS_FINISHED])
        elif roll < 0.95:
            status = STATUS_WAIT_PAY
        else:
            status = STATUS_CLOSED

        # 一个订单 1~3 个商品
        items = random.sample(PRODUCTS, random.randint(1, min(3, len(PRODUCTS))))
        total = 0.0
        for pid, pname, price in items:
            item_id += 1
            qty = random.randint(1, 3)
            amount = round(price * qty, 2)
            total += amount
            item_values.append(
                f"({item_id},{order_id},{pid},'{pname}','',{price},{qty},{amount})"
            )

        order_no = f"{created.strftime('%Y%m%d%H%M%S')}{user_id % 1000000:06d}{i % 10000:04d}"
        pay_time = "NULL" if status == STATUS_WAIT_PAY else f"'{created.strftime('%Y-%m-%d %H:%M:%S')}'"
        close_time = f"'{created.strftime('%Y-%m-%d %H:%M:%S')}'" if status == STATUS_CLOSED else "NULL"

        values.append(
            f"({order_id},'{order_no}',{user_id},{total},{total},{status},"
            f"{pay_time},{close_time},'{created.strftime('%Y-%m-%d %H:%M:%S')}','{created.strftime('%Y-%m-%d %H:%M:%S')}')"
        )

    # 分批插入，避免单条 SQL 太长（MySQL 有 max_allowed_packet 限制）
    batch = 500
    for start in range(0, len(values), batch):
        chunk = values[start:start + batch]
        sqls.append(
            "INSERT INTO t_order (id, order_no, user_id, total_amount, pay_amount, "
            "status, pay_time, close_time, create_time, update_time) VALUES\n  "
            + ",\n  ".join(chunk) + ";"
        )

    for start in range(0, len(item_values), batch):
        chunk = item_values[start:start + batch]
        sqls.append(
            "INSERT INTO t_order_item (id, order_id, product_id, product_name, "
            "product_image, price, quantity, total_amount) VALUES\n  "
            + ",\n  ".join(chunk) + ";"
        )

    return sqls


def main() -> int:
    parser = argparse.ArgumentParser(description="生成 NovaMall 测试数据")
    parser.add_argument("--users", type=int, default=200, help="生成的用户数")
    parser.add_argument("--orders", type=int, default=3000, help="生成的订单数")
    parser.add_argument("--days", type=int, default=60, help="数据分布在最近 N 天内")
    parser.add_argument("--clean", action="store_true", help="只输出清空语句")
    parser.add_argument("--out", type=str, default="", help="输出到文件，默认打印到控制台")
    args = parser.parse_args()

    if args.clean:
        output = "SET FOREIGN_KEY_CHECKS = 0;\nTRUNCATE TABLE t_order;\nTRUNCATE TABLE t_order_item;\nTRUNCATE TABLE t_local_message;\nSET FOREIGN_KEY_CHECKS = 1;\n"
    else:
        sqls = build_insert_sql(args.users, args.orders, args.days)
        output = "-- 由 scripts/generate_test_data.py 自动生成\n" + "\n\n".join(sqls) + "\n"

    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(output)
        print(f"已写入 {args.out}，共 {len(output)} 字符")
    else:
        print(output)

    return 0


if __name__ == "__main__":
    sys.exit(main())
