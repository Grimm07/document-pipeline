import base64
import io
import logging

from PIL import Image

from app.schemas import BoundingBox, OcrPage, OcrResult, TextBlock
from app.services.bbox_extractor import BBoxExtractor
from app.services.classifier import ClassifierService
from app.services.ocr import OCRService
from app.services.pdf_converter import pdf_bytes_to_images, pdf_bytes_to_images_with_dims

logger = logging.getLogger(__name__)


class DocumentPipeline:
    def __init__(
        self,
        classifier: ClassifierService,
        ocr: OCRService,
        candidate_labels: list[str],
        ocr_max_pdf_pages: int,
        bbox_extractor: BBoxExtractor | None = None,
    ) -> None:
        self._classifier = classifier
        self._ocr = ocr
        self._candidate_labels = candidate_labels
        self._ocr_max_pdf_pages = ocr_max_pdf_pages
        self._bbox_extractor = bbox_extractor

    def classify_document(
        self, content_b64: str, mime_type: str
    ) -> tuple[str, float]:
        raw_bytes = base64.b64decode(content_b64)
        text = self._extract_text(raw_bytes, mime_type)

        if not text or not text.strip():
            logger.warning("No text extracted for mime_type=%s", mime_type)
            return ("unknown", 0.0)

        return self._classifier.classify(text, self._candidate_labels)

    def classify_and_ocr_document(
        self, content_b64: str, mime_type: str
    ) -> tuple[str, float, OcrResult | None]:
        """Classify document and return OCR results with bounding boxes.

        For PDFs and images, returns full OCR data including per-page text
        and bounding boxes. For text/* types, returns None for OCR.
        """
        raw_bytes = base64.b64decode(content_b64)
        mime_lower = mime_type.lower()

        if mime_lower == "application/pdf":
            return self._classify_pdf_with_ocr(raw_bytes)
        elif mime_lower.startswith("image/"):
            return self._classify_image_with_ocr(raw_bytes)
        else:
            # Text and other types — no visual OCR needed
            text = self._extract_text(raw_bytes, mime_type)
            if not text or not text.strip():
                return ("unknown", 0.0, None)
            label, score = self._classifier.classify(text, self._candidate_labels)
            return (label, score, None)

    def _classify_pdf_with_ocr(
        self, raw_bytes: bytes
    ) -> tuple[str, float, OcrResult | None]:
        page_data = pdf_bytes_to_images_with_dims(
            raw_bytes, max_pages=self._ocr_max_pdf_pages
        )
        if not page_data:
            return ("unknown", 0.0, None)

        pages: list[OcrPage] = []
        all_texts: list[str] = []

        for i, (img, w, h) in enumerate(page_data):
            page_text = self._ocr.extract_text(img)
            if page_text:
                all_texts.append(page_text)

            blocks = self._detect_blocks(img, page_text)
            pages.append(OcrPage(
                pageIndex=i, width=w, height=h,
                text=page_text, blocks=blocks,
            ))

        full_text = "\n\n".join(all_texts)
        if not full_text.strip():
            return ("unknown", 0.0, OcrResult(pages=pages, fullText=""))

        label, score = self._classifier.classify(full_text, self._candidate_labels)
        return (label, score, OcrResult(pages=pages, fullText=full_text))

    def _classify_image_with_ocr(
        self, raw_bytes: bytes
    ) -> tuple[str, float, OcrResult | None]:
        image = Image.open(io.BytesIO(raw_bytes)).convert("RGB")
        w, h = image.size
        page_text = self._ocr.extract_text(image)

        blocks = self._detect_blocks(image, page_text)
        ocr_result = OcrResult(
            pages=[OcrPage(
                pageIndex=0, width=w, height=h,
                text=page_text, blocks=blocks,
            )],
            fullText=page_text,
        )

        if not page_text.strip():
            return ("unknown", 0.0, ocr_result)

        label, score = self._classifier.classify(page_text, self._candidate_labels)
        return (label, score, ocr_result)

    def _detect_blocks(self, image: Image.Image, page_text: str) -> list[TextBlock]:
        """Run PaddleOCR detection if extractor is available."""
        if self._bbox_extractor is None:
            return []
        try:
            raw_boxes = self._bbox_extractor.detect_boxes(image)
            return [
                TextBlock(
                    text="",  # detection-only, no per-box text
                    bbox=BoundingBox(**box),
                )
                for box in raw_boxes
            ]
        except Exception:
            logger.exception("BBox detection failed, returning empty blocks")
            return []

    def _extract_text(self, raw_bytes: bytes, mime_type: str) -> str:
        mime_lower = mime_type.lower()

        if mime_lower.startswith("text/"):
            return self._decode_text(raw_bytes)

        if mime_lower == "application/pdf":
            return self._extract_from_pdf(raw_bytes)

        if mime_lower.startswith("image/"):
            return self._extract_from_image(raw_bytes)

        # Unknown MIME — attempt text decode as fallback
        logger.info("Unknown mime_type=%s, attempting text decode", mime_type)
        return self._decode_text(raw_bytes)

    def _decode_text(self, raw_bytes: bytes) -> str:
        try:
            return raw_bytes.decode("utf-8")
        except UnicodeDecodeError:
            return raw_bytes.decode("latin-1")

    def _extract_from_pdf(self, raw_bytes: bytes) -> str:
        images = pdf_bytes_to_images(
            raw_bytes, max_pages=self._ocr_max_pdf_pages
        )
        if not images:
            return ""
        return self._ocr.extract_text_from_images(images)

    def _extract_from_image(self, raw_bytes: bytes) -> str:
        image = Image.open(io.BytesIO(raw_bytes)).convert("RGB")
        return self._ocr.extract_text(image)
