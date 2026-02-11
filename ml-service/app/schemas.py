"""Pydantic request/response schemas for the ML classification and OCR API."""

from pydantic import BaseModel


class ClassifyRequest(BaseModel):
    """Request body for classification endpoints.

    Attributes:
        content: Base64-encoded document bytes.
        mimeType: MIME type of the document (e.g. ``application/pdf``).
    """

    content: str
    mimeType: str


class ClassifyResponse(BaseModel):
    """Response from the ``/classify`` endpoint.

    Attributes:
        classification: Top predicted document label.
        confidence: Confidence score for the top label (0.0 to 1.0).
    """

    classification: str
    confidence: float


class BoundingBox(BaseModel):
    """Axis-aligned bounding box in pixel coordinates.

    Attributes:
        x: Left edge x-coordinate.
        y: Top edge y-coordinate.
        width: Box width in pixels.
        height: Box height in pixels.
    """

    x: float
    y: float
    width: float
    height: float


class TextBlock(BaseModel):
    """A detected text region with its bounding box.

    Attributes:
        text: Recognized text content (empty for detection-only mode).
        bbox: Bounding box of the text region.
        confidence: Optional recognition confidence score.
    """

    text: str
    bbox: BoundingBox
    confidence: float | None = None


class OcrPage(BaseModel):
    """OCR results for a single page.

    Attributes:
        pageIndex: Zero-based page index.
        width: Page image width in pixels.
        height: Page image height in pixels.
        text: Full extracted text for the page.
        blocks: Detected text regions with bounding boxes.
    """

    pageIndex: int
    width: int
    height: int
    text: str
    blocks: list[TextBlock]


class OcrResult(BaseModel):
    """Complete OCR output across all pages.

    Attributes:
        pages: Per-page OCR results.
        fullText: Concatenated text from all pages.
    """

    pages: list[OcrPage]
    fullText: str


class ClassifyWithOcrResponse(BaseModel):
    """Response from the ``/classify-with-ocr`` endpoint.

    Attributes:
        classification: Top predicted document label.
        confidence: Confidence score for the top label (0.0 to 1.0).
        ocr: OCR results with bounding boxes, or None for text documents.
    """

    classification: str
    confidence: float
    ocr: OcrResult | None = None


class HealthResponse(BaseModel):
    """Response from the ``/health`` endpoint.

    Attributes:
        status: Service status (``healthy`` or ``loading``).
        models_loaded: Whether all ML models have finished loading.
        classifier_model: HuggingFace model ID for the classifier.
        ocr_model: HuggingFace model ID for the OCR model.
    """

    status: str
    models_loaded: bool
    classifier_model: str
    ocr_model: str
