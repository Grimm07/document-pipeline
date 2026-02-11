"""Structured JSON logging configuration with correlation ID injection."""

import logging
import sys

from pythonjsonlogger.json import JsonFormatter

from app.correlation import get_correlation_id


class CorrelationIdFilter(logging.Filter):
    """Inject the current correlation ID into every log record."""

    def filter(self, record: logging.LogRecord) -> bool:
        """Add correlationId attribute to the log record."""
        record.correlationId = get_correlation_id()  # type: ignore[attr-defined]
        return True


def configure_logging() -> None:
    """Replace default logging with structured JSON output.

    Adds a correlation ID filter and sets up JSON formatting for all loggers
    including uvicorn's access and error loggers.
    """
    formatter = JsonFormatter(
        fmt="%(asctime)s %(levelname)s %(name)s %(message)s %(correlationId)s",
        rename_fields={"asctime": "timestamp", "levelname": "level", "name": "logger"},
        static_fields={"service": "ml-service"},
    )

    correlation_filter = CorrelationIdFilter()

    # Root logger
    root = logging.getLogger()
    root.handlers.clear()
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(formatter)
    handler.addFilter(correlation_filter)
    root.addHandler(handler)
    root.setLevel(logging.INFO)

    # Reconfigure uvicorn loggers to use same handler
    for logger_name in ("uvicorn", "uvicorn.access", "uvicorn.error"):
        uv_logger = logging.getLogger(logger_name)
        uv_logger.handlers.clear()
        uv_logger.addHandler(handler)
        uv_logger.propagate = False
