"""FastAPI application for document classification and OCR.

Provides REST endpoints for classifying documents and extracting text
via zero-shot classification (DeBERTa) and OCR (GOT-OCR2 + PaddleOCR).
Models are loaded during application lifespan startup.
"""

import logging
import os
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException

from app.config import settings
from app.pipeline import DocumentPipeline
from app.schemas import (
    ClassifyRequest,
    ClassifyResponse,
    ClassifyWithOcrResponse,
    HealthResponse,
)
from app.services.bbox_extractor import BBoxExtractor
from app.services.classifier import ClassifierService
from app.services.ocr import OCRService

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s — %(message)s",
)
logger = logging.getLogger(__name__)

# Global refs set during lifespan
_pipeline: DocumentPipeline | None = None
_models_loaded = False


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Manage application lifecycle: load ML models on startup, clean up on shutdown."""
    global _pipeline, _models_loaded

    os.environ.setdefault("HF_HOME", settings.hf_home)

    classifier = ClassifierService()
    ocr = OCRService()
    bbox_extractor = BBoxExtractor()

    logger.info("Loading models...")
    classifier.load(settings.classifier_model, settings.device, settings.torch_dtype)
    ocr.load(settings.ocr_model, settings.device, settings.torch_dtype)
    bbox_extractor.load()
    logger.info("All models loaded.")

    _pipeline = DocumentPipeline(
        classifier=classifier,
        ocr=ocr,
        candidate_labels=settings.get_candidate_labels(),
        ocr_max_pdf_pages=settings.ocr_max_pdf_pages,
        bbox_extractor=bbox_extractor,
    )
    _models_loaded = True

    yield

    logger.info("Shutting down — clearing GPU cache.")
    _models_loaded = False
    _pipeline = None
    try:
        import torch

        if torch.cuda.is_available():
            torch.cuda.empty_cache()
    except ImportError:
        pass


app = FastAPI(title="ML Classification Service", lifespan=lifespan)


@app.get("/health", response_model=HealthResponse)
def health():
    """Return service health status and model loading state."""
    return HealthResponse(
        status="healthy" if _models_loaded else "loading",
        models_loaded=_models_loaded,
        classifier_model=settings.classifier_model,
        ocr_model=settings.ocr_model,
    )


@app.post("/classify", response_model=ClassifyResponse)
def classify(request: ClassifyRequest):
    """Classify a document and return the top label with confidence score.

    Args:
        request: Document content (base64-encoded) and MIME type.

    Raises:
        HTTPException: 503 if models are still loading, 500 on inference failure.
    """
    if not _models_loaded or _pipeline is None:
        raise HTTPException(status_code=503, detail="Models are still loading")

    try:
        classification, confidence = _pipeline.classify_document(request.content, request.mimeType)
        return ClassifyResponse(classification=classification, confidence=confidence)
    except Exception as exc:
        logger.exception("Classification failed")
        raise HTTPException(status_code=500, detail="Classification inference failed") from exc


@app.post("/classify-with-ocr", response_model=ClassifyWithOcrResponse)
def classify_with_ocr(request: ClassifyRequest):
    """Classify a document and return OCR results with bounding boxes.

    For PDFs and images, extracts text via OCR and detects text region
    bounding boxes. For text types, OCR result is None.

    Args:
        request: Document content (base64-encoded) and MIME type.

    Raises:
        HTTPException: 503 if models are still loading, 500 on inference failure.
    """
    if not _models_loaded or _pipeline is None:
        raise HTTPException(status_code=503, detail="Models are still loading")

    try:
        classification, confidence, ocr_result = _pipeline.classify_and_ocr_document(
            request.content, request.mimeType
        )
        return ClassifyWithOcrResponse(
            classification=classification,
            confidence=confidence,
            ocr=ocr_result,
        )
    except Exception as exc:
        logger.exception("Classification with OCR failed")
        raise HTTPException(status_code=500, detail="Classification inference failed") from exc
