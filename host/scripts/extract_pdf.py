#!/usr/bin/env python3
"""Extract plain text from a PDF for Inkside chat prompts."""
import sys

def main():
    if len(sys.argv) < 2:
        print("usage: extract_pdf.py <path>", file=sys.stderr)
        sys.exit(2)
    path = sys.argv[1]
    try:
        from pypdf import PdfReader
    except Exception as e:
        print(f"pypdf unavailable: {e}", file=sys.stderr)
        sys.exit(1)
    reader = PdfReader(path)
    parts = []
    for page in reader.pages:
        try:
            parts.append(page.extract_text() or "")
        except Exception:
            parts.append("")
    text = "\n".join(parts).strip()
    # Cap stdout size for the bridge
    sys.stdout.write(text[:200_000])

if __name__ == "__main__":
    main()
