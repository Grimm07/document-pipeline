"""
Mock ML Service — lightweight FastAPI stand-in for the real GPU-based ML service.

Returns randomized but schema-valid classification and OCR results so the full
Document Pipeline can run end-to-end without an NVIDIA GPU.
"""

import random

from fastapi import FastAPI
from fastapi.responses import PlainTextResponse
from pydantic import BaseModel

# ---------------------------------------------------------------------------
# Pydantic models (mirrors ml-service/app/schemas.py exactly)
# ---------------------------------------------------------------------------


class ClassifyRequest(BaseModel):
    content: str  # Base64-encoded bytes
    mimeType: str


class ClassifyResponse(BaseModel):
    classification: str
    confidence: float
    scores: dict[str, float] | None = None


class BoundingBox(BaseModel):
    x: float
    y: float
    width: float
    height: float


class TextBlock(BaseModel):
    text: str
    bbox: BoundingBox
    confidence: float | None = None


class OcrPage(BaseModel):
    pageIndex: int
    width: int
    height: int
    text: str
    blocks: list[TextBlock]


class OcrResult(BaseModel):
    pages: list[OcrPage]
    fullText: str


class ClassifyWithOcrResponse(BaseModel):
    classification: str
    confidence: float
    scores: dict[str, float] | None = None
    ocr: OcrResult | None = None


class HealthResponse(BaseModel):
    status: str
    models_loaded: bool
    classifier_model: str
    ocr_model: str


# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

CANDIDATE_LABELS = [
    "invoice",
    "receipt",
    "contract",
    "letter",
    "report",
    "memo",
    "form",
    "other",
]

SAMPLE_WORDS = [
    "Lorem",
    "ipsum",
    "dolor",
    "sit",
    "amet",
    "consectetur",
    "adipiscing",
    "elit",
    "sed",
    "do",
    "eiusmod",
    "tempor",
    "incididunt",
    "ut",
    "labore",
    "et",
    "dolore",
    "magna",
    "aliqua",
]

# MIME types that should receive OCR results
OCR_MIME_TYPES = {
    "application/pdf",
    "image/png",
    "image/jpeg",
    "image/gif",
    "image/tiff",
    "image/bmp",
    "image/webp",
}

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _random_classification() -> tuple[str, float, dict[str, float]]:
    """Return a random label, confidence score, and full score distribution."""
    label = random.choice(CANDIDATE_LABELS)
    confidence = round(random.uniform(0.60, 0.99), 2)

    # Build a realistic score distribution: winner gets the highest score,
    # remaining probability is distributed across the other labels.
    remaining = 1.0 - confidence
    others = [lbl for lbl in CANDIDATE_LABELS if lbl != label]
    random.shuffle(others)

    # Generate random weights, then normalize to fill the remaining probability
    weights = [random.random() for _ in others]
    weight_sum = sum(weights)
    scores: dict[str, float] = {label: confidence}
    for i, other_label in enumerate(others):
        if i == len(others) - 1:
            # Last label gets exact remainder so scores sum to exactly 1.0
            scores[other_label] = round(1.0 - sum(scores.values()), 4)
        else:
            scores[other_label] = round((weights[i] / weight_sum) * remaining, 4)

    return label, confidence, scores


def _random_text(min_words: int = 3, max_words: int = 12) -> str:
    """Generate a random sentence from sample words."""
    count = random.randint(min_words, max_words)
    return " ".join(random.choices(SAMPLE_WORDS, k=count))


def _random_bbox() -> BoundingBox:
    """Generate a random bounding box with normalized coordinates (0.0-1.0)."""
    x = round(random.uniform(0.0, 0.7), 4)
    y = round(random.uniform(0.0, 0.7), 4)
    width = round(random.uniform(0.05, min(0.3, 1.0 - x)), 4)
    height = round(random.uniform(0.02, min(0.15, 1.0 - y)), 4)
    return BoundingBox(x=x, y=y, width=width, height=height)


def _random_text_block() -> TextBlock:
    """Generate a random text block with bounding box."""
    return TextBlock(
        text=_random_text(),
        bbox=_random_bbox(),
        confidence=round(random.uniform(0.70, 0.99), 2),
    )


def _random_ocr_page(page_index: int) -> OcrPage:
    """Generate a random OCR page with 1-5 text blocks."""
    num_blocks = random.randint(1, 5)
    blocks = [_random_text_block() for _ in range(num_blocks)]
    page_text = "\n".join(block.text for block in blocks)
    return OcrPage(
        pageIndex=page_index,
        width=random.choice([612, 800, 1024, 1920]),
        height=random.choice([792, 1200, 1400, 1080]),
        text=page_text,
        blocks=blocks,
    )


def _random_ocr_result() -> OcrResult:
    """Generate a random OCR result with 1-3 pages."""
    num_pages = random.randint(1, 3)
    pages = [_random_ocr_page(i) for i in range(num_pages)]
    full_text = "\n\n".join(page.text for page in pages)
    return OcrResult(pages=pages, fullText=full_text)


# ---------------------------------------------------------------------------
# FastAPI application
# ---------------------------------------------------------------------------

app = FastAPI(title="Mock ML Service", version="0.1.0")


@app.get("/health", response_model=HealthResponse)
async def health():
    return HealthResponse(
        status="healthy",
        models_loaded=True,
        classifier_model="mock-classifier",
        ocr_model="mock-ocr",
    )


@app.get("/metrics")
async def metrics():
    """Minimal Prometheus-compatible metrics endpoint (prevents 404 scrape errors)."""
    return PlainTextResponse(
        "# HELP mock_ml_service_up Mock ML service is running.\n"
        "# TYPE mock_ml_service_up gauge\n"
        "mock_ml_service_up 1\n",
        media_type="text/plain; version=0.0.4; charset=utf-8",
    )


@app.post("/classify", response_model=ClassifyResponse)
async def classify(request: ClassifyRequest):
    label, confidence, scores = _random_classification()
    return ClassifyResponse(classification=label, confidence=confidence, scores=scores)


@app.post("/classify-with-ocr", response_model=ClassifyWithOcrResponse)
async def classify_with_ocr(request: ClassifyRequest):
    label, confidence, scores = _random_classification()

    # Only generate OCR for image/PDF types
    ocr = None
    if request.mimeType in OCR_MIME_TYPES:
        ocr = _random_ocr_result()

    return ClassifyWithOcrResponse(
        classification=label,
        confidence=confidence,
        scores=scores,
        ocr=ocr,
    )
