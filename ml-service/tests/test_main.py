import base64
from contextlib import asynccontextmanager
from unittest.mock import MagicMock, patch

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient


@asynccontextmanager
async def _noop_lifespan(app: FastAPI):
    """No-op lifespan that skips model loading for tests."""
    yield


@pytest.fixture
def loaded_client():
    """Client with models 'loaded' (mocked pipeline)."""
    import app.main as main_module

    mock_pipeline = MagicMock()
    mock_pipeline.classify_document.return_value = ("invoice", 0.92, {"invoice": 0.92, "contract": 0.05, "report": 0.03})
    mock_pipeline.classify_and_ocr_document.return_value = ("invoice", 0.92, {"invoice": 0.92, "contract": 0.05, "report": 0.03}, None)

    original_pipeline = main_module._pipeline
    original_loaded = main_module._models_loaded
    original_lifespan = main_module.app.router.lifespan_context

    main_module._pipeline = mock_pipeline
    main_module._models_loaded = True
    main_module.app.router.lifespan_context = _noop_lifespan

    with TestClient(main_module.app, raise_server_exceptions=False) as client:
        yield client

    main_module._pipeline = original_pipeline
    main_module._models_loaded = original_loaded
    main_module.app.router.lifespan_context = original_lifespan


@pytest.fixture
def unloaded_client():
    """Client with models not yet loaded."""
    import app.main as main_module

    original_pipeline = main_module._pipeline
    original_loaded = main_module._models_loaded
    original_lifespan = main_module.app.router.lifespan_context

    main_module._pipeline = None
    main_module._models_loaded = False
    main_module.app.router.lifespan_context = _noop_lifespan

    with TestClient(main_module.app, raise_server_exceptions=False) as client:
        yield client

    main_module._pipeline = original_pipeline
    main_module._models_loaded = original_loaded
    main_module.app.router.lifespan_context = original_lifespan


class TestHealthEndpoint:
    def test_health_when_loaded(self, loaded_client):
        response = loaded_client.get("/health")
        assert response.status_code == 200
        data = response.json()
        assert data["status"] == "healthy"
        assert data["models_loaded"] is True

    def test_health_when_loading(self, unloaded_client):
        response = unloaded_client.get("/health")
        assert response.status_code == 200
        data = response.json()
        assert data["status"] == "loading"
        assert data["models_loaded"] is False


class TestClassifyEndpoint:
    def test_classify_returns_correct_shape(self, loaded_client):
        content = base64.b64encode(b"This is a test").decode()
        response = loaded_client.post(
            "/classify",
            json={"content": content, "mimeType": "text/plain"},
        )
        assert response.status_code == 200
        data = response.json()
        assert "classification" in data
        assert "confidence" in data
        assert data["classification"] == "invoice"
        assert data["confidence"] == pytest.approx(0.92)

    def test_classify_returns_503_when_loading(self, unloaded_client):
        content = base64.b64encode(b"test").decode()
        response = unloaded_client.post(
            "/classify",
            json={"content": content, "mimeType": "text/plain"},
        )
        assert response.status_code == 503

    def test_classify_returns_500_on_inference_error(self, loaded_client):
        import app.main as main_module

        main_module._pipeline.classify_document.side_effect = RuntimeError("boom")
        content = base64.b64encode(b"test").decode()
        response = loaded_client.post(
            "/classify",
            json={"content": content, "mimeType": "text/plain"},
        )
        assert response.status_code == 500
        assert "failed" in response.json()["detail"].lower()

    def test_classify_rejects_missing_fields(self, loaded_client):
        response = loaded_client.post("/classify", json={"content": "abc"})
        assert response.status_code == 422

    def test_classify_response_matches_kotlin_contract(self, loaded_client):
        """The Kotlin client expects 'classification', 'confidence', and 'scores' fields."""
        content = base64.b64encode(b"test").decode()
        response = loaded_client.post(
            "/classify",
            json={"content": content, "mimeType": "text/plain"},
        )
        data = response.json()
        assert set(data.keys()) == {"classification", "confidence", "scores"}
        assert isinstance(data["classification"], str)
        assert isinstance(data["confidence"], (int, float))
        assert isinstance(data["scores"], dict)


class TestClassifyWithOcrEndpoint:
    def test_classify_with_ocr_no_ocr_result(self, loaded_client):
        """Text documents return null OCR."""
        content = base64.b64encode(b"test document").decode()
        response = loaded_client.post(
            "/classify-with-ocr",
            json={"content": content, "mimeType": "text/plain"},
        )
        assert response.status_code == 200
        data = response.json()
        assert data["classification"] == "invoice"
        assert data["confidence"] == pytest.approx(0.92)
        assert data["ocr"] is None

    def test_classify_with_ocr_with_ocr_result(self, loaded_client):
        """When pipeline returns OCR data, it's included in response."""
        import app.main as main_module
        from app.schemas import OcrResult, OcrPage

        ocr = OcrResult(
            pages=[OcrPage(pageIndex=0, width=100, height=200, text="page text", blocks=[])],
            fullText="page text",
        )
        main_module._pipeline.classify_and_ocr_document.return_value = (
            "report", 0.88, {"report": 0.88, "invoice": 0.07, "contract": 0.05}, ocr
        )

        content = base64.b64encode(b"pdf content").decode()
        response = loaded_client.post(
            "/classify-with-ocr",
            json={"content": content, "mimeType": "application/pdf"},
        )
        assert response.status_code == 200
        data = response.json()
        assert data["classification"] == "report"
        assert data["ocr"] is not None
        assert len(data["ocr"]["pages"]) == 1
        assert data["ocr"]["pages"][0]["text"] == "page text"
        assert data["ocr"]["fullText"] == "page text"

    def test_classify_with_ocr_returns_503_when_loading(self, unloaded_client):
        content = base64.b64encode(b"test").decode()
        response = unloaded_client.post(
            "/classify-with-ocr",
            json={"content": content, "mimeType": "text/plain"},
        )
        assert response.status_code == 503

    def test_classify_with_ocr_returns_500_on_error(self, loaded_client):
        import app.main as main_module

        main_module._pipeline.classify_and_ocr_document.side_effect = RuntimeError("boom")
        content = base64.b64encode(b"test").decode()
        response = loaded_client.post(
            "/classify-with-ocr",
            json={"content": content, "mimeType": "text/plain"},
        )
        assert response.status_code == 500

    def test_classify_with_ocr_response_shape(self, loaded_client):
        """Response must include classification, confidence, scores, and ocr fields."""
        content = base64.b64encode(b"test").decode()
        response = loaded_client.post(
            "/classify-with-ocr",
            json={"content": content, "mimeType": "text/plain"},
        )
        data = response.json()
        assert set(data.keys()) == {"classification", "confidence", "scores", "ocr"}


def test_metrics_endpoint(loaded_client):
    """GET /metrics returns Prometheus metrics text."""
    response = loaded_client.get("/metrics")
    assert response.status_code == 200
    assert "http_request" in response.text or "HELP" in response.text


def test_correlation_id_echoed(loaded_client):
    """X-Request-ID header is echoed back in response."""
    response = loaded_client.get("/health", headers={"X-Request-ID": "test-corr-123"})
    assert response.status_code == 200
    assert response.headers.get("X-Request-ID") == "test-corr-123"


def test_correlation_id_generated(loaded_client):
    """X-Request-ID header is generated when not provided."""
    response = loaded_client.get("/health")
    assert response.status_code == 200
    assert "X-Request-ID" in response.headers
    assert len(response.headers["X-Request-ID"]) > 0
