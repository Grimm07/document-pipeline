import base64
from unittest.mock import MagicMock, patch

import pytest

from app.pipeline import DocumentPipeline


@pytest.fixture
def pipeline(mock_classifier_spec, mock_ocr_spec):
    return DocumentPipeline(
        classifier=mock_classifier_spec,
        ocr=mock_ocr_spec,
        candidate_labels=["invoice", "contract", "report"],
        ocr_max_pdf_pages=5,
    )


@pytest.fixture
def pipeline_with_bbox(mock_classifier_spec, mock_ocr_spec, mock_bbox_extractor_spec):
    return DocumentPipeline(
        classifier=mock_classifier_spec,
        ocr=mock_ocr_spec,
        candidate_labels=["invoice", "contract", "report"],
        ocr_max_pdf_pages=5,
        bbox_extractor=mock_bbox_extractor_spec,
    )


class TestDocumentPipeline:
    def test_text_mime_skips_ocr(self, pipeline, mock_ocr_spec):
        content = base64.b64encode(b"This is a test document").decode()
        label, score, scores = pipeline.classify_document(content, "text/plain")
        assert label == "invoice"
        assert score == 0.85
        assert "invoice" in scores
        mock_ocr_spec.extract_text.assert_not_called()
        mock_ocr_spec.extract_text_from_images.assert_not_called()

    def test_text_html_skips_ocr(self, pipeline, mock_ocr_spec):
        content = base64.b64encode(b"<html>doc</html>").decode()
        pipeline.classify_document(content, "text/html")
        mock_ocr_spec.extract_text.assert_not_called()

    def test_image_mime_runs_ocr(self, pipeline, mock_ocr_spec):
        from PIL import Image
        import io

        img = Image.new("RGB", (10, 10), color="white")
        buf = io.BytesIO()
        img.save(buf, format="PNG")
        content = base64.b64encode(buf.getvalue()).decode()

        mock_ocr_spec.extract_text.return_value = "OCR text"
        pipeline.classify_document(content, "image/png")
        mock_ocr_spec.extract_text.assert_called_once()

    def test_pdf_mime_converts_and_ocrs(self, pipeline, mock_ocr_spec):
        import fitz

        doc = fitz.open()
        doc.new_page(width=100, height=100)
        pdf_bytes = doc.tobytes()
        doc.close()

        content = base64.b64encode(pdf_bytes).decode()
        mock_ocr_spec.extract_text_from_images.return_value = "PDF text"
        pipeline.classify_document(content, "application/pdf")
        mock_ocr_spec.extract_text_from_images.assert_called_once()

    def test_unknown_mime_falls_back_to_text(self, pipeline, mock_ocr_spec):
        content = base64.b64encode(b"Some content").decode()
        label, _, scores = pipeline.classify_document(content, "application/octet-stream")
        assert label == "invoice"
        assert "invoice" in scores
        mock_ocr_spec.extract_text.assert_not_called()

    def test_empty_ocr_result_returns_unknown(self, pipeline, mock_ocr_spec, mock_classifier_spec):
        from PIL import Image
        import io

        img = Image.new("RGB", (10, 10))
        buf = io.BytesIO()
        img.save(buf, format="PNG")
        content = base64.b64encode(buf.getvalue()).decode()

        mock_ocr_spec.extract_text.return_value = ""
        label, score, scores = pipeline.classify_document(content, "image/png")
        assert label == "unknown"
        assert score == 0.0
        assert scores == {}
        mock_classifier_spec.classify.assert_not_called()

    def test_mime_type_is_case_insensitive(self, pipeline, mock_ocr_spec):
        content = base64.b64encode(b"test").decode()
        pipeline.classify_document(content, "TEXT/PLAIN")
        mock_ocr_spec.extract_text.assert_not_called()


class TestClassifyAndOcrDocument:
    def test_text_mime_returns_none_ocr(self, pipeline_with_bbox):
        content = base64.b64encode(b"Hello world").decode()
        label, score, scores, ocr = pipeline_with_bbox.classify_and_ocr_document(
            content, "text/plain"
        )
        assert label == "invoice"
        assert score == 0.85
        assert "invoice" in scores
        assert ocr is None

    def test_image_returns_ocr_result(
        self, pipeline_with_bbox, mock_ocr_spec, mock_bbox_extractor_spec
    ):
        from PIL import Image
        import io

        img = Image.new("RGB", (200, 150), color="white")
        buf = io.BytesIO()
        img.save(buf, format="PNG")
        content = base64.b64encode(buf.getvalue()).decode()

        mock_ocr_spec.extract_text.return_value = "OCR text from image"
        label, score, scores, ocr = pipeline_with_bbox.classify_and_ocr_document(
            content, "image/png"
        )
        assert label == "invoice"
        assert "invoice" in scores
        assert ocr is not None
        assert len(ocr.pages) == 1
        assert ocr.pages[0].pageIndex == 0
        assert ocr.pages[0].width == 200
        assert ocr.pages[0].height == 150
        assert ocr.pages[0].text == "OCR text from image"
        assert ocr.fullText == "OCR text from image"
        assert len(ocr.pages[0].blocks) == 1
        mock_bbox_extractor_spec.detect_boxes.assert_called_once()

    def test_pdf_returns_multi_page_ocr(
        self, pipeline_with_bbox, mock_ocr_spec, mock_bbox_extractor_spec
    ):
        import fitz

        doc = fitz.open()
        doc.new_page(width=100, height=100)
        doc.new_page(width=200, height=200)
        pdf_bytes = doc.tobytes()
        doc.close()

        mock_ocr_spec.extract_text.side_effect = ["Page 1 text", "Page 2 text"]
        label, score, scores, ocr = pipeline_with_bbox.classify_and_ocr_document(
            pdf_bytes=None,
            content_b64=base64.b64encode(pdf_bytes).decode(),
            mime_type="application/pdf",
        ) if False else pipeline_with_bbox.classify_and_ocr_document(
            base64.b64encode(pdf_bytes).decode(), "application/pdf"
        )
        assert ocr is not None
        assert len(ocr.pages) == 2
        assert ocr.pages[0].text == "Page 1 text"
        assert ocr.pages[1].text == "Page 2 text"
        assert "Page 1 text" in ocr.fullText
        assert "Page 2 text" in ocr.fullText
        assert "invoice" in scores

    def test_image_empty_ocr_returns_unknown(
        self, pipeline_with_bbox, mock_ocr_spec, mock_classifier_spec
    ):
        from PIL import Image
        import io

        img = Image.new("RGB", (10, 10))
        buf = io.BytesIO()
        img.save(buf, format="PNG")
        content = base64.b64encode(buf.getvalue()).decode()

        mock_ocr_spec.extract_text.return_value = ""
        label, score, scores, ocr = pipeline_with_bbox.classify_and_ocr_document(
            content, "image/png"
        )
        assert label == "unknown"
        assert score == 0.0
        assert scores == {}
        assert ocr is not None  # OCR result still returned (empty text, but with structure)
        mock_classifier_spec.classify.assert_not_called()

    def test_no_bbox_extractor_returns_empty_blocks(
        self, pipeline, mock_ocr_spec
    ):
        """When bbox_extractor is None, blocks should be empty lists."""
        from PIL import Image
        import io

        img = Image.new("RGB", (100, 100))
        buf = io.BytesIO()
        img.save(buf, format="PNG")
        content = base64.b64encode(buf.getvalue()).decode()

        mock_ocr_spec.extract_text.return_value = "Some text"
        label, score, scores, ocr = pipeline.classify_and_ocr_document(
            content, "image/png"
        )
        assert ocr is not None
        assert ocr.pages[0].blocks == []
        assert "invoice" in scores
