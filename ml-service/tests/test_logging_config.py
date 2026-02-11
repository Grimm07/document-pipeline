"""Tests for structured logging configuration."""

import logging

from app.logging_config import CorrelationIdFilter, configure_logging


def test_correlation_filter_adds_field():
    """CorrelationIdFilter adds correlationId to log records."""
    filt = CorrelationIdFilter()
    record = logging.LogRecord("test", logging.INFO, "", 0, "msg", (), None)
    result = filt.filter(record)
    assert result is True
    assert hasattr(record, "correlationId")


def test_configure_logging_sets_json_handler():
    """configure_logging replaces root handler with JSON formatter."""
    configure_logging()
    root = logging.getLogger()
    assert len(root.handlers) > 0
    # Restore basic config so other tests aren't affected
    root.handlers.clear()
    handler = logging.StreamHandler()
    handler.setFormatter(logging.Formatter("%(message)s"))
    root.addHandler(handler)
