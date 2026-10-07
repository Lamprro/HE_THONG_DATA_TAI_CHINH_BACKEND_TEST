package com.hethongdata.taichinh.service.news;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class NewsUrlNormalizerTests {
    @Test void stripsTrackingButKeepsArticleIdentifiersAndEncoding() {
        assertThat(NewsUrlNormalizer.normalize("HTTPS://CAFEF.VN:443/a%20b.chn?id=12&utm_source=list&fbclid=x#top"))
                .isEqualTo("https://cafef.vn/a%20b.chn?id=12");
        assertThat(NewsUrlNormalizer.normalize("https://cafef.vn/a.chn?utm_source=list"))
                .isEqualTo(NewsUrlNormalizer.normalize("https://cafef.vn/a.chn"));
    }
    @Test void rejectsNonHttpAndEmbeddedCredentials() {
        assertThatThrownBy(()->NewsUrlNormalizer.normalize("javascript:alert(1)")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->NewsUrlNormalizer.normalize("https://user:secret@cafef.vn/a")).isInstanceOf(IllegalArgumentException.class);
    }
}
