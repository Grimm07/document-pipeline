from pydantic import BaseModel


class ClassifyRequest(BaseModel):
    content: str  # Base64-encoded bytes
    mimeType: str


class ClassifyResponse(BaseModel):
    classification: str
    confidence: float


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
    ocr: OcrResult | None = None


class HealthResponse(BaseModel):
    status: str
    models_loaded: bool
    classifier_model: str
    ocr_model: str
