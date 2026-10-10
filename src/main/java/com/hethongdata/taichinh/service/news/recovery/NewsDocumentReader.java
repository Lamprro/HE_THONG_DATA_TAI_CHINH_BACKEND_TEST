package com.hethongdata.taichinh.service.news.recovery;

import com.hethongdata.taichinh.integration.cloudinary.SafeDocumentDownloader.Download;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

/** No office process, external entity resolution, macros or executable document code. */
public final class NewsDocumentReader {
  public record Document(String extension, String mime, String text, boolean image) {}

  private NewsDocumentReader() {}

  public static Document inspect(Download file) throws Exception {
    byte[] bytes = file.bytes();
    if (bytes.length < 4) throw new IllegalArgumentException("DOCUMENT_EMPTY_OR_INVALID");
    if (bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F') {
      String raw = new String(bytes, StandardCharsets.ISO_8859_1);
      if (!raw.contains("%%EOF")
          || raw.contains("/Encrypt")
          || raw.contains("/JavaScript")
          || raw.contains("/Launch")
          || raw.contains("/EmbeddedFile"))
        throw new IllegalArgumentException("PDF_ENCRYPTED_ACTIVE_OR_INCOMPLETE");
      return new Document("pdf", "application/pdf", null, false);
    }
    if ((bytes[0] & 255) == 137 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G')
      return new Document("png", "image/png", null, true);
    if ((bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255)
      return new Document("jpg", "image/jpeg", null, true);
    if (bytes[0] == 'P' && bytes[1] == 'K') return office(bytes);
    if (Set.of("text/plain", "text/csv").contains(file.mime())) {
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      if (text.indexOf('\0') >= 0 || text.stripLeading().startsWith("<") || text.length() > 100000)
        throw new IllegalArgumentException("TEXT_FORMAT_UNSUPPORTED");
      return new Document(file.mime().equals("text/csv") ? "csv" : "txt", file.mime(), text, false);
    }
    throw new IllegalArgumentException("UNSUPPORTED_DOCUMENT_TYPE");
  }

  private static Document office(byte[] bytes) throws Exception {
    StringBuilder out = new StringBuilder();
    boolean word = false, sheet = false;
    int entries = 0, total = 0;
    try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (++entries > 500
            || entry.getName().contains("..")
            || entry.getName().toLowerCase(Locale.ROOT).contains("vbaproject"))
          throw new IllegalArgumentException("UNSAFE_OFFICE_ARCHIVE");
        byte[] data = zip.readNBytes(2_000_001);
        total += data.length;
        if (total > 2_000_000) throw new IllegalArgumentException("OFFICE_EXPANSION_LIMIT");
        String name = entry.getName();
        boolean selected =
            name.equals("word/document.xml")
                || name.equals("xl/sharedStrings.xml")
                || name.equals("xl/workbook.xml")
                || name.matches("xl/worksheets/sheet[0-9]+\\.xml");
        if (!selected) continue;
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var parsed = factory.newDocumentBuilder().parse(new ByteArrayInputStream(data));
        word |= name.startsWith("word/");
        sheet |= name.startsWith("xl/");
        // Keep worksheet coordinates, formulas and shared-string indexes; never evaluate formulas.
        out.append("\nFILE PART: ")
            .append(name)
            .append('\n')
            .append(
                sheet
                    ? new String(data, StandardCharsets.UTF_8)
                    : parsed.getDocumentElement().getTextContent());
        if (out.length() > 100000) throw new IllegalArgumentException("OFFICE_TEXT_LIMIT");
      }
    }
    if (word == sheet) throw new IllegalArgumentException("UNSUPPORTED_OFFICE_ARCHIVE");
    return new Document(
        word ? "docx" : "xlsx",
        word
            ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        out.toString(),
        false);
  }
}
