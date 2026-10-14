"""Text layer of a PDF (PyMuPDF). Digital NF-e/NFS-e PDFs carry their text; scanned ones do not."""

from fiscal_worker.domain.extraction import DocumentText, TextLine
from fiscal_worker.extractors.rasterizer import MAX_PAGES, open_pdf

# Below this a "text layer" is noise (a watermark, a scanner stamp) and the page is really an image.
MIN_TEXT_CHARACTERS = 40


def extract_text_layer(content: bytes) -> DocumentText | None:
    """The PDF's own text, or None when it has none worth using and OCR must take over."""
    with open_pdf(content) as document:
        raw_lines = [
            line.strip() for page in list(document)[:MAX_PAGES] for line in page.get_text("text").splitlines()
        ]
    lines = [line for line in raw_lines if line]
    if sum(len(line) for line in lines) < MIN_TEXT_CHARACTERS:
        return None
    return DocumentText(
        engine="pdf_text", lines=tuple(TextLine(text=line, confidence=None) for line in lines)
    )
