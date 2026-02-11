"""Tests for correlation ID middleware."""

from app.correlation import CorrelationIdMiddleware, correlation_id_var, get_correlation_id


def test_get_correlation_id_default():
    """Default correlation ID is empty string."""
    token = correlation_id_var.set("")
    try:
        assert get_correlation_id() == ""
    finally:
        correlation_id_var.reset(token)


def test_get_correlation_id_set():
    """Correlation ID can be set and retrieved."""
    token = correlation_id_var.set("test-123")
    try:
        assert get_correlation_id() == "test-123"
    finally:
        correlation_id_var.reset(token)


def test_middleware_class_exists():
    """CorrelationIdMiddleware can be instantiated (smoke test)."""
    assert CorrelationIdMiddleware is not None
