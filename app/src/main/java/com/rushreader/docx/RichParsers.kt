package com.rushreader.docx

import com.rushreader.core.DocType
import java.io.File
import java.io.IOException

fun parseRich(type: DocType, f: File): DocxDocument = when (type) {
    DocType.DOCX -> DocxParser.parse(f)
    DocType.EPUB -> EpubParser.parse(f)
    DocType.RTF -> RtfParser.parse(f)
    DocType.XLSX -> XlsxParser.parse(f)
    DocType.PPTX -> PptxParser.parse(f)
    else -> throw IOException("Định dạng này chưa được hỗ trợ")
}
