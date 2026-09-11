package com.example.aidocumentplatform;

import com.example.aidocumentplatform.common.DataUrlPlaceholders;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DataUrlPlaceholdersTest {

    private static final String LONG_BASE64 = "iVBORw0KGgoAAAANSUhEUg".repeat(20);
    private static final String DATA_URL = "data:image/png;base64," + LONG_BASE64;

    @Test
    void shouldReplaceLargeDataUrlsAndRestoreLosslessly() {
        String html = "<body><img src=\"" + DATA_URL + "\"><div style=\"background:url(" + DATA_URL + ")\"></div></body>";

        DataUrlPlaceholders.Compressed compressed = DataUrlPlaceholders.compress(html);

        assertThat(compressed.text()).doesNotContain(LONG_BASE64);
        assertThat(compressed.text()).contains("__PROTO_IMG_1__");
        // 同一张图片复用同一个占位符
        assertThat(compressed.placeholders()).hasSize(1);

        String restored = DataUrlPlaceholders.restore(compressed.text(), compressed.placeholders());
        assertThat(restored).isEqualTo(html);
    }

    @Test
    void shouldAssignDistinctPlaceholdersForDifferentImages() {
        String other = "data:image/jpeg;base64," + "QUJDREVGRw".repeat(30);
        String html = "<img src=\"" + DATA_URL + "\"><img src=\"" + other + "\">";

        DataUrlPlaceholders.Compressed compressed = DataUrlPlaceholders.compress(html);

        assertThat(compressed.placeholders()).hasSize(2);
        assertThat(DataUrlPlaceholders.restore(compressed.text(), compressed.placeholders())).isEqualTo(html);
    }

    @Test
    void shouldKeepShortDataUrlsInline() {
        String html = "<img src=\"data:image/png;base64,iVBORw0KGgo=\">";

        DataUrlPlaceholders.Compressed compressed = DataUrlPlaceholders.compress(html);

        assertThat(compressed.text()).isEqualTo(html);
        assertThat(compressed.placeholders()).isEmpty();
    }
}
