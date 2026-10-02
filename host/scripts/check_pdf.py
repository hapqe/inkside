mods = []
for name in ("pypdf", "PyPDF2", "fitz", "pdfminer", "pdfminer.high_level"):
    try:
        __import__(name)
        mods.append(name + "=ok")
    except Exception as e:
        mods.append(name + "=no")
print("\n".join(mods))
