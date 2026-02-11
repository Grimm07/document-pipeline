from unittest.mock import MagicMock, patch

import pytest

from app.services.pdf_converter import pdf_bytes_to_images


def _make_minimal_pdf(pages: int = 1) -> bytes:
    """Create a minimal valid PDF with the given number of pages using PyMuPDF."""
    import fitz

    doc = fitz.open()
    for _ in range(pages):
        doc.new_page(width=100, height=100)
    pdf_bytes = doc.tobytes()
    doc.close()
    return pdf_bytes


class TestPdfConverter:
    def test_converts_single_page(self):
        pdf = _make_minimal_pdf(1)
        images = pdf_bytes_to_images(pdf)
        assert len(images) == 1
        assert images[0].mode == "RGB"

    def test_converts_multiple_pages(self):
        pdf = _make_minimal_pdf(3)
        images = pdf_bytes_to_images(pdf)
        assert len(images) == 3

    def test_respects_max_pages_limit(self):
        pdf = _make_minimal_pdf(5)
        images = pdf_bytes_to_images(pdf, max_pages=2)
        assert len(images) == 2

    def test_max_pages_larger_than_doc_returns_all(self):
        pdf = _make_minimal_pdf(2)
        images = pdf_bytes_to_images(pdf, max_pages=10)
        assert len(images) == 2

    def test_corrupt_pdf_raises(self):
        with pytest.raises(Exception):
            pdf_bytes_to_images(b"not a pdf")

    def test_image_dimensions_scale_with_dpi(self):
        pdf = _make_minimal_pdf(1)
        images_lo = pdf_bytes_to_images(pdf, dpi=72)
        images_hi = pdf_bytes_to_images(pdf, dpi=200)
        assert images_hi[0].width > images_lo[0].width
