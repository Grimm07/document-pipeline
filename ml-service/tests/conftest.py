from unittest.mock import MagicMock, create_autospec

import pytest

from app.services.bbox_extractor import BBoxExtractor
from app.services.classifier import ClassifierService
from app.services.ocr import OCRService


# ---------------------------------------------------------------------------
# GPU detection — used by @pytest.mark.gpu tests
#
# CRITICAL: All CUDA/transformers checks run in subprocesses. Importing torch
# in the main process initializes CUDA context, and a subsequent fork() for
# the transformers probe can SIGBUS on WSL2 (CUDA + fork is unsupported).
# ---------------------------------------------------------------------------
def _subprocess_check(code: str, timeout: int = 30) -> int:
    """Run *code* in a child process; return its exit code (or -1 on error)."""
    import subprocess
    import sys

    try:
        result = subprocess.run(
            [sys.executable, "-c", code],
            capture_output=True,
            timeout=timeout,
        )
        return result.returncode
    except (subprocess.TimeoutExpired, OSError):
        return -1


_cuda_rc = _subprocess_check(
    "import torch, sys; sys.exit(0 if torch.cuda.is_available() else 1)"
)
_has_cuda = _cuda_rc == 0

# Only probe transformers if CUDA is present (avoids the cost when irrelevant)
_pipeline_rc = (
    _subprocess_check("from transformers import pipeline", timeout=60)
    if _has_cuda
    else -1
)
_has_pipeline = _pipeline_rc == 0

_skip_gpu_reason = (
    "No CUDA GPU available" if not _has_cuda
    else "transformers pipeline import failed (bus error / missing deps)" if not _has_pipeline
    else ""
)


def pytest_collection_modifyitems(config, items):
    """Auto-skip @pytest.mark.gpu tests when GPU is not available."""
    if _has_cuda and _has_pipeline:
        return
    skip_marker = pytest.mark.skip(reason=_skip_gpu_reason)
    for item in items:
        if "gpu" in item.keywords:
            item.add_marker(skip_marker)


@pytest.fixture
def mock_classifier():
    """ClassifierService with mocked _pipeline internals (for unit testing classify logic)."""
    service = ClassifierService()
    service._pipeline = MagicMock()
    service._pipeline.return_value = {
        "labels": ["invoice", "contract", "report"],
        "scores": [0.85, 0.10, 0.05],
    }
    return service


@pytest.fixture
def mock_ocr():
    """OCRService with mocked _model/_processor internals (for unit testing OCR logic)."""
    service = OCRService()
    service._model = MagicMock()
    service._processor = MagicMock()
    return service


@pytest.fixture
def mock_classifier_spec():
    """Fully mocked ClassifierService (for pipeline integration tests)."""
    mock = create_autospec(ClassifierService, instance=True)
    mock.classify.return_value = ("invoice", 0.85)
    return mock


@pytest.fixture
def mock_ocr_spec():
    """Fully mocked OCRService (for pipeline integration tests)."""
    mock = create_autospec(OCRService, instance=True)
    mock.extract_text.return_value = "OCR extracted text"
    mock.extract_text_from_images.return_value = "OCR extracted text"
    return mock


@pytest.fixture
def mock_bbox_extractor_spec():
    """Fully mocked BBoxExtractor (for pipeline integration tests)."""
    mock = create_autospec(BBoxExtractor, instance=True)
    mock.detect_boxes.return_value = [
        {"x": 10.0, "y": 20.0, "width": 100.0, "height": 30.0},
    ]
    return mock
