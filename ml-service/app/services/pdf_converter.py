"""PDF-to-image conversion utilities using PyMuPDF."""

import logging

import fitz  # PyMuPDF
from PIL import Image

logger = logging.getLogger(__name__)

DEFAULT_DPI = 200


def pdf_bytes_to_images(
    pdf_bytes: bytes,
    max_pages: int = 10,
    dpi: int = DEFAULT_DPI,
) -> list[Image.Image]:
    """Convert PDF pages to PIL images.

    Args:
        pdf_bytes: Raw PDF file bytes.
        max_pages: Maximum number of pages to convert.
        dpi: Rendering resolution in dots per inch.

    Returns:
        List of RGB PIL images, one per page.
    """
    doc = fitz.open(stream=pdf_bytes, filetype="pdf")
    page_count = min(len(doc), max_pages)

    if len(doc) > max_pages:
        logger.warning("PDF has %d pages, limiting to first %d", len(doc), max_pages)

    images = []
    zoom = dpi / 72  # fitz default is 72 DPI
    matrix = fitz.Matrix(zoom, zoom)

    for i in range(page_count):
        page = doc[i]
        pix = page.get_pixmap(matrix=matrix)
        img = Image.frombytes("RGB", (pix.width, pix.height), pix.samples)
        images.append(img)

    doc.close()
    return images


def pdf_bytes_to_images_with_dims(
    pdf_bytes: bytes,
    max_pages: int = 10,
    dpi: int = DEFAULT_DPI,
) -> list[tuple[Image.Image, int, int]]:
    """Convert PDF pages to images with dimension metadata.

    Args:
        pdf_bytes: Raw PDF file bytes.
        max_pages: Maximum number of pages to convert.
        dpi: Rendering resolution in dots per inch.

    Returns:
        List of (image, width, height) tuples for each page.
    """
    doc = fitz.open(stream=pdf_bytes, filetype="pdf")
    page_count = min(len(doc), max_pages)

    if len(doc) > max_pages:
        logger.warning("PDF has %d pages, limiting to first %d", len(doc), max_pages)

    results = []
    zoom = dpi / 72
    matrix = fitz.Matrix(zoom, zoom)

    for i in range(page_count):
        page = doc[i]
        pix = page.get_pixmap(matrix=matrix)
        img = Image.frombytes("RGB", (pix.width, pix.height), pix.samples)
        results.append((img, pix.width, pix.height))

    doc.close()
    return results
