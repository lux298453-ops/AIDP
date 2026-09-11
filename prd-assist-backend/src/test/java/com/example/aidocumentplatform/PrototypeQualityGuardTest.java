package com.example.aidocumentplatform;

import com.example.aidocumentplatform.model.enums.Platform;
import com.example.aidocumentplatform.service.impl.PrototypeQualityGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PrototypeQualityGuardTest {

    private final PrototypeQualityGuard guard = new PrototypeQualityGuard();

    @Test
    void shouldReplaceAssetAndApplyDeterministicConstraints() {
        String html = """
                <!doctype html><html><head></head><body>
                <nav>导航</nav><main><img src="__PROTOTYPE_PRIMARY_ASSET__">
                <button>领取</button><button>详情</button></main>
                <script>const template = '<button>动态按钮</button>';</script>
                </body></html>
                """;

        PrototypeQualityGuard.Result result = guard.inspectAndRepair(
                html, Platform.APP, "主体居中；禁止导航；下方仅一个按钮", true, "data:image/png;base64,abc");

        assertThat(result.html()).contains("data:image/png;base64,abc", "data-prototype-quality-guard");
        assertThat(result.html()).doesNotContain("<nav>导航</nav>", "__PROTOTYPE_PRIMARY_ASSET__", "<button>详情</button>");
        assertThat(result.html()).contains("const template = '<button>动态按钮</button>'");
    }

    @Test
    void shouldInsertGeneratedAssetWhenModelMissesPlaceholder() {
        String html = "<!doctype html><html><head></head><body><main>内容</main></body></html>";
        PrototypeQualityGuard.Result result = guard.inspectAndRepair(
                html, Platform.APP, "主体居中展示", true, "data:image/png;base64,abc");

        assertThat(result.html()).contains("data-generated-primary-asset", "proto-generated-primary-asset--centered");
        assertThat(result.findings()).anyMatch(item -> item.contains("自动补入主体图"));
    }

    @Test
    void shouldRemoveBrokenImageWhenAssetGenerationFails() {
        String html = "<html><head></head><body><img src=\"__PROTOTYPE_PRIMARY_ASSET__\" alt=\"核心视觉素材\"><img src=\"\" alt=\"核心视觉素材\"></body></html>";
        PrototypeQualityGuard.Result result = guard.inspectAndRepair(
                html, Platform.APP, "主体展示", false, null);

        assertThat(result.html()).doesNotContain("<img", "__PROTOTYPE_PRIMARY_ASSET__", "核心视觉素材");
    }

    @Test
    void shouldKeepOnlyFirstPrimaryAssetPlaceholder() {
        String html = "<html><head></head><body><img src=\"__PROTOTYPE_PRIMARY_ASSET__\"><img src=\"__PROTOTYPE_PRIMARY_ASSET__\"></body></html>";
        PrototypeQualityGuard.Result result = guard.inspectAndRepair(
                html, Platform.APP, "主体展示", true, "data:image/png;base64,abc");

        assertThat(count(result.html(), "data:image/png;base64,abc")).isEqualTo(1);
        assertThat(count(result.html(), "<img")).isEqualTo(1);
    }

    @Test
    void shouldUsePrimaryAssetOnlyOnceAcrossMultiplePages() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String pages = mapper.writeValueAsString(java.util.List.of(
                java.util.Map.of(
                        "title", "首页",
                        "order", 1,
                        "html", "<html><head></head><body><img src=\"__PROTOTYPE_PRIMARY_ASSET__\"></body></html>"),
                java.util.Map.of(
                        "title", "详情",
                        "order", 2,
                        "html", "<html><head></head><body><main>详情</main></body></html>")));
        PrototypeQualityGuard.Result result = guard.inspectAndRepair(
                pages, Platform.APP, "主体展示", true, "data:image/png;base64,abc");

        assertThat(count(result.html(), "data:image/png;base64,abc")).isEqualTo(1);
    }

    @Test
    void shouldReusePrimaryAssetWhenRequirementSaysEveryPage() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String pages = mapper.writeValueAsString(java.util.List.of(
                java.util.Map.of("title", "锁屏", "order", 1,
                        "html", "<html><head></head><body><img src=\"__PROTOTYPE_PRIMARY_ASSET__\"></body></html>"),
                java.util.Map.of("title", "主屏", "order", 2,
                        "html", "<html><head></head><body><img src=\"__PROTOTYPE_PRIMARY_ASSET__\"></body></html>")));

        PrototypeQualityGuard.Result result = guard.inspectAndRepair(
                pages, Platform.APP, "小猫主题，小猫必须贯穿每个页面", true, "data:image/png;base64,abc");

        assertThat(count(result.html(), "data:image/png;base64,abc")).isEqualTo(2);
    }

    @Test
    void shouldBindDifferentAssetsToTheirTargetPages() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String pages = mapper.writeValueAsString(java.util.List.of(
                java.util.Map.of("title", "锁屏页", "order", 1,
                        "html", "<html><head></head><body><img src=\"__PROTOTYPE_ASSET_lock_screen__\"></body></html>"),
                java.util.Map.of("title", "主屏页", "order", 2,
                        "html", "<html><head></head><body><img src=\"__PROTOTYPE_ASSET_home_screen__\"></body></html>")));

        PrototypeQualityGuard.Result result = guard.inspectAndRepairAssets(
                pages,
                Platform.APP,
                "锁屏和主屏分别使用独立素材",
                java.util.List.of(
                        new PrototypeQualityGuard.Asset("lock_screen", "锁屏页", "data:image/png;base64,lock"),
                        new PrototypeQualityGuard.Asset("home_screen", "主屏页", "data:image/png;base64,home")));

        assertThat(result.html()).contains("data:image/png;base64,lock", "data:image/png;base64,home");
        assertThat(result.html()).doesNotContain("__PROTOTYPE_ASSET_");
    }

    @Test
    void shouldRemoveOnlyFailedPageAssetPlaceholder() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String pages = mapper.writeValueAsString(java.util.List.of(
                java.util.Map.of("title", "锁屏页", "order", 1,
                        "html", "<html><head></head><body><img src=\"__PROTOTYPE_ASSET_lock_screen__\"></body></html>"),
                java.util.Map.of("title", "主屏页", "order", 2,
                        "html", "<html><head></head><body><img src=\"__PROTOTYPE_ASSET_home_screen__\"></body></html>")));

        PrototypeQualityGuard.Result result = guard.inspectAndRepairAssets(
                pages,
                Platform.APP,
                "锁屏和主屏分别使用独立素材",
                java.util.List.of(
                        new PrototypeQualityGuard.Asset("lock_screen", "锁屏页", "data:image/png;base64,lock")));

        assertThat(result.html()).contains("data:image/png;base64,lock");
        assertThat(result.html()).doesNotContain("__PROTOTYPE_ASSET_home_screen__", "src=\"\"");
    }

    private int count(String value, String needle) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
