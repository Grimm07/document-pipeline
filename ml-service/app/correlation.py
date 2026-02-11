"""Correlation ID propagation via ASGI middleware."""

import logging
import uuid
from contextvars import ContextVar

from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import Response

logger = logging.getLogger(__name__)

correlation_id_var: ContextVar[str] = ContextVar("correlation_id", default="")


def get_correlation_id() -> str:
    """Return the current request's correlation ID (empty string if unset)."""
    return correlation_id_var.get()


class CorrelationIdMiddleware(BaseHTTPMiddleware):
    """Read or generate X-Request-ID and store it in a ContextVar for the request."""

    async def dispatch(self, request: Request, call_next) -> Response:
        """Extract or generate correlation ID, store in ContextVar, add to response."""
        cid = request.headers.get("x-request-id") or str(uuid.uuid4())
        token = correlation_id_var.set(cid)
        try:
            response = await call_next(request)
            response.headers["X-Request-ID"] = cid
            return response
        finally:
            correlation_id_var.reset(token)
