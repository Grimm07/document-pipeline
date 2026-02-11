"""
GPU integration tests — verify real model loading and inference on CUDA hardware.

These tests are EXCLUDED from default pytest runs (see pyproject.toml addopts).
Run explicitly with:
    pytest -m gpu -v                    # all GPU tests
    pytest -m gpu -k classifier -v      # classifier only
    pytest -m gpu -k ocr -v             # OCR only
    pytest -m gpu -k pipeline -v        # full pipeline only

Prerequisites:
    - NVIDIA GPU with >= 4GB VRAM
    - CUDA drivers installed
    - Model weights downloaded (first run takes several minutes)

The conftest.py auto-skips these when no CUDA GPU is detected.
"""

import base64
import io
import os

import pytest
from PIL import Image, ImageDraw

from app.config import Settings

# Use env vars if set, otherwise fall back to project defaults
_settings = Settings()

# ── Fixtures ──────────────────────────────────────────────────────────────────


@pytest.fixture(scope="module")
def gpu_device():
    """Resolve the GPU device string. Prefers env ML_DEVICE, defaults to cuda."""
    return _settings.device if _settings.device != "cpu" else "cuda"


@pytest.fixture(scope="module")
def torch_dtype():
    return _settings.torch_dtype


@pytest.fixture(scope="module")
def loaded_classifier(gpu_device, torch_dtype):
    """Load the real DeBERTa classifier onto GPU. Cached for the module."""
    from app.services.classifier import ClassifierService

    service = ClassifierService()
    service.load(_settings.classifier_model, gpu_device, torch_dtype)
    return service


@pytest.fixture(scope="module")
def loaded_ocr(gpu_device, torch_dtype):
    """Load the real GOT-OCR2 model onto GPU. Cached for the module."""
    from app.services.ocr import OCRService

    service = OCRService()
    service.load(_settings.ocr_model, gpu_device, torch_dtype)
    return service


@pytest.fixture(scope="module")
def sample_text_image():
    """Create a simple image with text-like content for OCR testing."""
    img = Image.new("RGB", (400, 100), color="white")
    draw = ImageDraw.Draw(img)
    draw.text((10, 30), "INVOICE 12345", fill="black")
    return img


# ── CUDA sanity ───────────────────────────────────────────────────────────────


@pytest.mark.gpu
class TestCudaSanity:
    """Basic checks that the CUDA stack is functional before loading models."""

    def test_torch_sees_cuda(self):
        import torch

        assert torch.cuda.is_available(), "CUDA should be available"
        assert torch.cuda.device_count() >= 1

    def test_cuda_tensor_operations(self):
        import torch

        t = torch.tensor([1.0, 2.0, 3.0], device="cuda")
        result = t.sum().item()
        assert result == pytest.approx(6.0)

    def test_cuda_memory_allocatable(self):
        import torch

        # Allocate and free a small tensor to verify memory management works
        t = torch.zeros(1024, 1024, device="cuda", dtype=torch.float16)
        assert t.device.type == "cuda"
        del t
        torch.cuda.empty_cache()


# ── Classifier integration ────────────────────────────────────────────────────


@pytest.mark.gpu
class TestClassifierGPU:
    """Test real DeBERTa model loading and inference on GPU."""

    def test_classifier_loads_successfully(self, loaded_classifier):
        assert loaded_classifier._pipeline is not None

    def test_classifier_pipeline_on_gpu(self, loaded_classifier):
        model = loaded_classifier._pipeline.model
        device = str(next(model.parameters()).device)
        assert "cuda" in device

    def test_classify_returns_label_and_score(self, loaded_classifier):
        labels = _settings.get_candidate_labels()
        label, score = loaded_classifier.classify("This is an invoice for $500", labels)
        assert isinstance(label, str)
        assert label in labels
        assert isinstance(score, float)
        assert 0.0 <= score <= 1.0

    def test_classify_invoice_text(self, loaded_classifier):
        labels = _settings.get_candidate_labels()
        label, score = loaded_classifier.classify(
            "Invoice #12345. Payment due: $1,500.00. Bill to: Acme Corp.", labels
        )
        assert label == "invoice"
        assert score > 0.5

    def test_classify_contract_text(self, loaded_classifier):
        labels = _settings.get_candidate_labels()
        label, score = loaded_classifier.classify(
            "This agreement is entered into by and between Party A and Party B. "
            "The parties hereby agree to the following terms and conditions.",
            labels,
        )
        assert label == "contract"
        assert score > 0.3

    def test_classify_empty_returns_unknown(self, loaded_classifier):
        labels = _settings.get_candidate_labels()
        label, score = loaded_classifier.classify("", labels)
        assert label == "unknown"
        assert score == 0.0

    def test_classify_scores_sum_roughly_to_one(self, loaded_classifier):
        """Verify multi_label=False produces a proper softmax distribution."""
        labels = ["invoice", "contract", "report"]
        result = loaded_classifier._pipeline(
            "Some generic document text",
            candidate_labels=labels,
            multi_label=False,
        )
        total = sum(result["scores"])
        assert total == pytest.approx(1.0, abs=0.01)


# ── OCR integration ──────────────────────────────────────────────────────────


@pytest.mark.gpu
class TestOCRGPU:
    """Test real GOT-OCR2 model loading and inference on GPU."""

    def test_ocr_loads_successfully(self, loaded_ocr):
        assert loaded_ocr._model is not None
        assert loaded_ocr._processor is not None

    def test_ocr_model_on_gpu(self, loaded_ocr):
        device = str(loaded_ocr._model.device)
        assert "cuda" in device

    def test_extract_text_returns_string(self, loaded_ocr, sample_text_image):
        result = loaded_ocr.extract_text(sample_text_image)
        assert isinstance(result, str)

    def test_extract_text_from_images_concatenates(self, loaded_ocr, sample_text_image):
        result = loaded_ocr.extract_text_from_images([sample_text_image, sample_text_image])
        assert isinstance(result, str)
        # Two pages should produce more text than one (or at least not be empty)
        single = loaded_ocr.extract_text(sample_text_image)
        if single:
            assert len(result) >= len(single)

    def test_blank_image_returns_minimal_text(self, loaded_ocr):
        blank = Image.new("RGB", (200, 200), color="white")
        result = loaded_ocr.extract_text(blank)
        # Blank image might return empty string or whitespace
        assert isinstance(result, str)


# ── Full pipeline integration ────────────────────────────────────────────────


@pytest.mark.gpu
class TestPipelineGPU:
    """End-to-end test: base64 input → MIME routing → model inference → result."""

    def test_text_document_classification(self, loaded_classifier, loaded_ocr):
        from app.pipeline import DocumentPipeline

        pipeline = DocumentPipeline(
            classifier=loaded_classifier,
            ocr=loaded_ocr,
            candidate_labels=_settings.get_candidate_labels(),
            ocr_max_pdf_pages=2,
        )

        text = "Invoice #99. Total amount due: $2,340.00. Net 30 terms."
        content = base64.b64encode(text.encode()).decode()
        label, score = pipeline.classify_document(content, "text/plain")

        assert label == "invoice"
        assert score > 0.5

    def test_image_document_classification(
        self, loaded_classifier, loaded_ocr, sample_text_image
    ):
        from app.pipeline import DocumentPipeline

        pipeline = DocumentPipeline(
            classifier=loaded_classifier,
            ocr=loaded_ocr,
            candidate_labels=_settings.get_candidate_labels(),
            ocr_max_pdf_pages=2,
        )

        buf = io.BytesIO()
        sample_text_image.save(buf, format="PNG")
        content = base64.b64encode(buf.getvalue()).decode()
        label, score = pipeline.classify_document(content, "image/png")

        assert isinstance(label, str)
        assert 0.0 <= score <= 1.0

    def test_pdf_document_classification(self, loaded_classifier, loaded_ocr):
        import fitz
        from app.pipeline import DocumentPipeline

        pipeline = DocumentPipeline(
            classifier=loaded_classifier,
            ocr=loaded_ocr,
            candidate_labels=_settings.get_candidate_labels(),
            ocr_max_pdf_pages=2,
        )

        # Create a 1-page PDF with text rendered as an image
        doc = fitz.open()
        page = doc.new_page(width=400, height=100)
        page.insert_text((10, 50), "Receipt for purchase #8812", fontsize=14)
        pdf_bytes = doc.tobytes()
        doc.close()

        content = base64.b64encode(pdf_bytes).decode()
        label, score = pipeline.classify_document(content, "application/pdf")

        assert isinstance(label, str)
        assert 0.0 <= score <= 1.0


# ── GPU memory management ────────────────────────────────────────────────────


@pytest.mark.gpu
class TestGPUMemory:
    """Verify models don't leak VRAM across load/inference cycles."""

    def test_vram_stable_after_inference(self, loaded_classifier):
        import torch

        labels = _settings.get_candidate_labels()

        # Warm up
        loaded_classifier.classify("warmup text", labels)
        torch.cuda.synchronize()
        baseline = torch.cuda.memory_allocated()

        # Run several inferences
        for i in range(5):
            loaded_classifier.classify(f"Document number {i} about invoices", labels)

        torch.cuda.synchronize()
        after = torch.cuda.memory_allocated()

        # Allow some jitter, but no major leak (< 10MB growth)
        growth = after - baseline
        assert growth < 10 * 1024 * 1024, f"VRAM grew by {growth / 1024 / 1024:.1f} MB after 5 inferences"
