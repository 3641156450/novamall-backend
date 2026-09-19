"""
单元测试：验证纯计算逻辑（不连数据库也能跑）。

为什么重点测这些？
- 预测算法、占比计算这类纯函数，测试成本最低、收益最高
- 涉及数据库的部分用集成测试跑（需要 MySQL），CI 里才做
- 面试时能说"我写了单测"并且真的有测试文件，比口头说强得多

运行：
    pytest app/tests -v
"""
import pytest

from app.services.report import moving_average_forecast


class TestMovingAverageForecast:
    """移动平均预测的边界情况"""

    def test_empty_history_returns_empty(self):
        assert moving_average_forecast([]) == []

    def test_window_larger_than_history_is_clamped(self):
        """历史数据不足窗口大小时，窗口应自动收缩，不能崩溃"""
        result = moving_average_forecast([10.0, 20.0], window=7, horizon=3)
        assert len(result) == 3
        # 窗口收缩为 2：(10+20)/2 = 15
        assert result[0] == 15.0

    def test_constant_series_forecasts_constant(self):
        """恒定序列的预测值应等于原值"""
        result = moving_average_forecast([5.0] * 10, window=5, horizon=4)
        assert all(abs(v - 5.0) < 1e-6 for v in result)

    def test_forecast_length_matches_horizon(self):
        result = moving_average_forecast([1, 2, 3, 4, 5, 6, 7], window=3, horizon=7)
        assert len(result) == 7

    def test_rising_series_forecast_is_between(self):
        """递增序列的短期预测应落在合理区间（不大于最大值太多）"""
        history = [float(x) for x in range(1, 15)]
        result = moving_average_forecast(history, window=7, horizon=1)
        assert result[0] > 0
        assert result[0] <= max(history) + 1

    @pytest.mark.parametrize("horizon", [1, 3, 10, 30])
    def test_various_horizons(self, horizon):
        assert len(moving_average_forecast([1.0] * 20, window=7, horizon=horizon)) == horizon
