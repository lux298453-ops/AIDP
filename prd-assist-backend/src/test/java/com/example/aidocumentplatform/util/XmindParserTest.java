package com.example.aidocumentplatform.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XmindParserTest {

    private final XmindParser parser = new XmindParser();

    @Test
    void xmlParserReadsOnlyDirectChildTopicsOnce() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <xmap-content>
                  <sheet>
                    <topic>
                      <title>SEO Root</title>
                      <children>
                        <topics type="attached">
                          <topic>
                            <title>Traffic Analysis</title>
                            <children>
                              <topics type="attached">
                                <topic><title>Keyword Plan</title></topic>
                                <topic><title>Landing Page</title></topic>
                              </topics>
                            </children>
                          </topic>
                          <topic><title>Content Workflow</title></topic>
                        </topics>
                      </children>
                    </topic>
                  </sheet>
                </xmap-content>
                """;

        String outline = parser.parse(xmindZipWithContentXml(xml));

        assertTrue(outline.contains("- SEO Root"));
        assertEquals(1, countOccurrences(outline, "Traffic Analysis"));
        assertEquals(1, countOccurrences(outline, "Keyword Plan"));
        assertEquals(1, countOccurrences(outline, "Landing Page"));
        assertEquals(1, countOccurrences(outline, "Content Workflow"));
    }

    private byte[] xmindZipWithContentXml(String xml) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("content.xml"));
            zip.write(xml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private int countOccurrences(String text, String target) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(target, index)) >= 0) {
            count++;
            index += target.length();
        }
        return count;
    }
}
