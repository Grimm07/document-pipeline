# Document Pipeline — ML Service

FastAPI service providing document classification (zero-shot NLI) and OCR with bounding box detection. Called by the pipeline worker to classify uploaded documents and extract text.

## Quick Start

```bash
# Run tests (no GPU needed — fully mocked)
pip install -e ".[dev]"
pytest tests/ -v

# Run locally (requires CUDA GPU)
pip install -e .
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000

# Run via Docker (recommended)
docker compose -f ../docker/docker-compose.yml up ml-service
```

First run downloads ~4GB of model weights from HuggingFace Hub. Subsequent starts load from the `ml_models` Docker volume.

## API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/health` | GET | Health check — reports model loading status |
| `/classify` | POST | Classification only (legacy) |
| `/classify-with-ocr` | POST | Classification + OCR text + bounding boxes |

**Request** (both `/classify` and `/classify-with-ocr`):
```json
{"content": "<base64-encoded>", "mimeType": "application/pdf"}
```

**Response** (`/classify-with-ocr`):
```json
{
  "classification": "invoice",
  "confidence": 0.94,
  "ocr": {
    "fullText": "Invoice #1234...",
    "pages": [{
      "pageIndex": 0,
      "width": 1654, "height": 2339,
      "text": "Invoice #1234...",
      "blocks": [{"text": "", "bbox": {"x": 50, "y": 100, "width": 200, "height": 30}, "confidence": null}]
    }]
  }
}
```

## Models

| Model | Purpose | VRAM | Download |
|-------|---------|------|----------|
| [DeBERTa-v3-large NLI](https://huggingface.co/MoritzLaurer/DeBERTa-v3-large-mnli-fever-anli-ling-wanli) | Zero-shot classification | ~870 MB | ~1.5 GB |
| [GOT-OCR2](https://huggingface.co/stepfun-ai/GOT-OCR-2.0-hf) | Text extraction from images/PDFs | ~1.1 GB | ~2.5 GB |
| PaddleOCR (detection) | Bounding box detection | ~50 MB | ~3 MB |

Total GPU VRAM: ~2.5 GB (plus CUDA overhead).

## Project Structure

```
app/
├── main.py             # FastAPI app, lifespan, endpoints
├── pipeline.py         # DocumentPipeline — orchestrates classify + OCR
├── config.py           # Pydantic Settings (ML_* env vars)
├── schemas.py          # Request/response Pydantic models
└── services/
    ├── classifier.py   # DeBERTa zero-shot classification
    ├── ocr.py          # GOT-OCR2 text extraction
    ├── bbox_extractor.py  # PaddleOCR detection-only mode
    └── pdf_converter.py   # PDF → page images via PyMuPDF
tests/
├── conftest.py         # Shared fixtures, GPU detection
├── test_main.py        # Endpoint tests
├── test_pipeline.py    # Pipeline integration tests
├── test_classifier.py  # Classifier unit tests
├── test_ocr.py         # OCR unit tests
├── test_bbox_extractor.py  # BBox extractor tests
├── test_pdf_converter.py   # PDF converter tests
└── test_gpu_integration.py # GPU-only smoke tests
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `ML_DEVICE` | `cuda` | `cuda` or `cpu` |
| `ML_TORCH_DTYPE` | `float16` | `float16` or `float32` (use `float32` for CPU) |
| `ML_CLASSIFIER_MODEL` | `MoritzLaurer/DeBERTa-v3-large-mnli-fever-anli-ling-wanli` | HF model ID |
| `ML_OCR_MODEL` | `stepfun-ai/GOT-OCR-2.0-hf` | HF model ID |
| `ML_CANDIDATE_LABELS` | `invoice,receipt,contract,...` | Comma-separated classification labels |
| `ML_OCR_MAX_PDF_PAGES` | `10` | Max PDF pages to OCR |
| `ML_HF_HOME` | `~/.cache/huggingface` | Model cache directory |

## Testing

```bash
# All tests (mocked, no GPU needed)
pytest tests/ -v

# GPU integration tests (requires CUDA + downloaded models)
pytest -m gpu -v
```

GPU tests are excluded by default (`addopts = "-m 'not gpu'"` in pyproject.toml). They auto-skip if CUDA is unavailable.

## Docker

```bash
# Build image
docker compose -f ../docker/docker-compose.yml build ml-service

# Run with GPU
docker compose -f ../docker/docker-compose.yml up ml-service

# CPU-only (remove GPU reservation from docker-compose.yml first)
ML_DEVICE=cpu ML_TORCH_DTYPE=float32 docker compose -f ../docker/docker-compose.yml up ml-service
```

Base image: `nvidia/cuda:12.6.3-runtime-ubuntu24.04`. Model weights persist in the `ml_models` Docker volume.
