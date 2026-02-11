"""Tests for the mock ML service."""

import base64

import pytest
from fastapi.testclient import TestClient

from main import CANDIDATE_LABELS, app

client = TestClient(app)

# A valid base64 payload for test requests
SAMPLE_CONTENT = base64.b64encode(b"mock document content").decode()


def _classify_request(mime_type: str = "application/pdf") -> dict:
    return {"content": SAMPLE_CONTENT, "mimeType": mime_type}


# ---------------------------------------------------------------------------
# Health endpoint
# ---------------------------------------------------------------------------


def test_health_returns_healthy():
    resp = client.get("/health")
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "healthy"
    assert data["models_loaded"] is True
    assert data["classifier_model"] == "mock-classifier"
    assert data["ocr_model"] == "mock-ocr"


# ---------------------------------------------------------------------------
# POST /classify
# ---------------------------------------------------------------------------


def test_classify_returns_valid_response():
    resp = client.post("/classify", json=_classify_request())
    assert resp.status_code == 200
    data = resp.json()
    assert data["classification"] in CANDIDATE_LABELS
    assert 0.0 <= data["confidence"] <= 1.0


# ---------------------------------------------------------------------------
# POST /classify-with-ocr
# ---------------------------------------------------------------------------


def test_classify_with_ocr_returns_ocr_for_pdf():
    resp = client.post("/classify-with-ocr", json=_classify_request("application/pdf"))
    assert resp.status_code == 200
    data = resp.json()
    assert data["classification"] in CANDIDATE_LABELS
    assert 0.0 <= data["confidence"] <= 1.0
    assert data["ocr"] is not None
    assert "pages" in data["ocr"]
    assert "fullText" in data["ocr"]
    assert len(data["ocr"]["pages"]) >= 1


def test_classify_with_ocr_returns_ocr_for_image():
    resp = client.post("/classify-with-ocr", json=_classify_request("image/png"))
    assert resp.status_code == 200
    data = resp.json()
    assert data["ocr"] is not None
    assert len(data["ocr"]["pages"]) >= 1


def test_classify_with_ocr_returns_null_ocr_for_text():
    resp = client.post("/classify-with-ocr", json=_classify_request("text/plain"))
    assert resp.status_code == 200
    data = resp.json()
    assert data["classification"] in CANDIDATE_LABELS
    assert data["ocr"] is None


# ---------------------------------------------------------------------------
# OCR structure validation
# ---------------------------------------------------------------------------


def test_ocr_page_structure_valid():
    resp = client.post("/classify-with-ocr", json=_classify_request("application/pdf"))
    data = resp.json()
    assert data["ocr"] is not None

    for page in data["ocr"]["pages"]:
        assert isinstance(page["pageIndex"], int)
        assert isinstance(page["width"], int)
        assert isinstance(page["height"], int)
        assert isinstance(page["text"], str)
        assert isinstance(page["blocks"], list)
        assert len(page["blocks"]) >= 1

        for block in page["blocks"]:
            assert isinstance(block["text"], str)
            assert len(block["text"]) > 0
            bbox = block["bbox"]
            assert 0.0 <= bbox["x"] <= 1.0
            assert 0.0 <= bbox["y"] <= 1.0
            assert 0.0 < bbox["width"] <= 1.0
            assert 0.0 < bbox["height"] <= 1.0
            # x + width and y + height should not exceed 1.0
            assert bbox["x"] + bbox["width"] <= 1.0 + 1e-6
            assert bbox["y"] + bbox["height"] <= 1.0 + 1e-6


# ---------------------------------------------------------------------------
# Validation / error handling
# ---------------------------------------------------------------------------


def test_invalid_request_returns_422():
    # Missing required fields
    resp = client.post("/classify", json={})
    assert resp.status_code == 422

    resp = client.post("/classify-with-ocr", json={"content": SAMPLE_CONTENT})
    assert resp.status_code == 422

    resp = client.post("/classify", json={"mimeType": "text/plain"})
    assert resp.status_code == 422


# ---------------------------------------------------------------------------
# Statistical: all classifications come from known labels
# ---------------------------------------------------------------------------


def test_classification_from_candidate_labels():
    """Run 20 calls and verify every classification is from the known set."""
    seen_labels = set()
    for _ in range(20):
        resp = client.post("/classify", json=_classify_request())
        assert resp.status_code == 200
        label = resp.json()["classification"]
        assert label in CANDIDATE_LABELS
        seen_labels.add(label)
    # With 20 calls and 8 labels, we should see at least 2 distinct labels
    assert len(seen_labels) >= 2, f"Only saw {seen_labels} across 20 calls"
