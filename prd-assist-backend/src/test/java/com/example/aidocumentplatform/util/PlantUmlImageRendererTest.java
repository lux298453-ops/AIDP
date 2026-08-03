package com.example.aidocumentplatform.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlantUmlImageRendererTest {

    /** LLM/编辑器把换行全部丢掉后的拼接源码（用户实际遇到的报错形式） */
    @Test
    void repairsGluedPlantUmlSource() {
        String glued = "@startuml!pragma layout smetana left to right direction skinparam rectangle { "
                + "BorderColor #2F6BFF RoundCorner12 } @enduml";
        String fixed = PlantUmlImageRenderer.repairLostNewlines(glued);
        assertTrue(fixed.contains("@startuml\n"), fixed);
        assertTrue(fixed.contains("\nleft to right direction"), fixed);
        assertTrue(fixed.contains("\nskinparam rectangle {"), fixed);
        assertTrue(fixed.contains("\n@enduml"), fixed);
    }

    /** 空格粘连而不是完全拼死的情况也要能修 */
    @Test
    void repairsSpaceJoinedPlantUmlSource() {
        String joined = "@startuml left to right direction skinparam ArrowColor #4A4A4A "
                + "rectangle \"企业门户首页\" as home rectangle \"个人工作台\" as workbench home --> workbench @enduml";
        String fixed = PlantUmlImageRenderer.repairLostNewlines(joined);
        assertTrue(fixed.contains("@startuml\n"), fixed);
        assertTrue(fixed.contains("\nleft to right direction"), fixed);
        assertTrue(fixed.contains("\nskinparam ArrowColor"), fixed);
        assertTrue(fixed.contains("\nrectangle \"企业门户首页\""), fixed);
        assertTrue(fixed.contains("\n@enduml"), fixed);
    }

    /** 正常多行源码必须是 no-op，不能误拆 skinparam rectangle {} */
    @Test
    void leavesNormalMultiLineSourceUntouched() {
        String normal = "@startuml\n"
                + "left to right direction\n"
                + "skinparam rectangle {\n"
                + " BackgroundColor #F8FBFF\n"
                + " BorderColor #2F6BFF\n"
                + " RoundCorner12\n"
                + "}\n"
                + "rectangle \"企业门户首页\" as home\n"
                + "@enduml";
        assertTrue(normal.equals(PlantUmlImageRenderer.repairLostNewlines(normal)));
    }

    /** skinparam 参数名与值粘连要补空格：RoundCorner12 → RoundCorner 12、BackgroundColor#F8FBFF → BackgroundColor #F8FBFF */
    @Test
    void repairsGluedSkinparamValues() {
        String src = "skinparam rectangle {\n"
                + " BackgroundColor#F8FBFF\n"
                + " BorderColor #2F6BFF\n"
                + " RoundCorner12\n"
                + "}\n";
        String fixed = PlantUmlImageRenderer.repairSkinparamValues(src);
        assertTrue(fixed.contains("BackgroundColor #F8FBFF"), fixed);
        assertTrue(fixed.contains("BorderColor #2F6BFF"), fixed);
        assertTrue(fixed.contains("RoundCorner 12"), fixed);
    }

    /** 全局 skinparam 行粘连也要修，且不误伤块外节点别名（A1/B2 不能拆） */
    @Test
    void repairsGluedSkinparamLineButKeepsNodeAliases() {
        String src = "@startuml\nskinparam defaultFontSize16\nrectangle \"列表页\" as B1\nA1 --> B1\n@enduml";
        String fixed = PlantUmlImageRenderer.repairSkinparamValues(src);
        assertTrue(fixed.contains("skinparam defaultFontSize 16"), fixed);
        assertTrue(fixed.contains("as B1"), fixed);
        assertTrue(fixed.contains("A1 --> B1"), fixed);
    }

    /** 单行 skinparam 块（{ 和 } 挤在一行）在 PlantUML 1.2026.x 中是硬错误，必须把 { } 展开到独立行 */
    @Test
    void expandsSingleLineSkinparamBlock() {
        String src = "@startuml\n"
                + "skinparam rectangle { BackgroundColor #F8FBFF BorderColor #2F6BFF RoundCorner12 }\n"
                + "rectangle \"企业门户首页\" as home\n"
                + "@enduml";
        String fixed = PlantUmlImageRenderer.repairSkinparamValues(src);
        assertTrue(fixed.contains("skinparam rectangle {\n"), fixed);
        assertTrue(fixed.contains("\n}\n"), fixed);
        assertTrue(fixed.contains("RoundCorner 12"), fixed);
        assertFalse(fixed.contains("RoundCorner12"), fixed);
    }

    /** 修复后的源码能真正渲染出图（回归：RoundCorner12 粘连在 PlantUML 1.2026.x 中会整图失败） */
    @Test
    void repairedGluedSourceRendersSuccessfully() {
        String glued = "@startuml!pragma layout smetana left to right direction skinparam rectangle { "
                + "BorderColor #2F6BFF RoundCorner12 } "
                + "rectangle \"企业门户首页\" as home rectangle \"个人工作台\" as workbench "
                + "home --> workbench @enduml";
        byte[] png = new PlantUmlImageRenderer().renderPlantUmlToPng(glued);
        assertTrue(png != null && png.length > 2000, "拼接源码修复后应渲染出真实 PNG，而非占位图");
        assertFalse(isFallbackPlaceholder(png), "不应是占位图（图表渲染失败）");
    }

    /** 完整 23 节点页面结构图（PRD 84/82 同款，含 RoundCorner12）修复后应真正渲染出图 */
    @Test
    void fullPageStructureDiagramWithRoundCorner12Renders() {
        String src = "@startuml\n"
                + "left to right direction\n"
                + "skinparam rectangle {\n"
                + " BackgroundColor #F8FBFF\n"
                + " BorderColor #2F6BFF\n"
                + " RoundCorner12\n"
                + "}\n"
                + "rectangle \"企业门户首页\" as home\n"
                + "rectangle \"登录页\" as login\n"
                + "rectangle \"个人工作台\" as workbench\n"
                + "rectangle \"消息中心\" as message\n"
                + "rectangle \"系统设置\" as settings\n"
                + "home --> login\n"
                + "home --> workbench\n"
                + "workbench --> message\n"
                + "workbench --> settings\n"
                + "@enduml";
        byte[] png = new PlantUmlImageRenderer().renderPlantUmlToPng(src);
        assertTrue(png != null && png.length > 2000, "RoundCorner12 修复后应渲染出真实 PNG");
        assertFalse(isFallbackPlaceholder(png), "不应是占位图");
    }

    private boolean isFallbackPlaceholder(byte[] png) {
        if (png == null) return true;
        // 占位图固定 1400x760；ensureMinWidth 归一化后可能放大为 4200x2280 或 3600x1952
        int[] wh = pngSize(png);
        if (wh == null) return true;
        int w = wh[0], h = wh[1];
        return (w == 1400 && h == 760) || (w == 4200 && h == 2280) || (w == 3600 && h == 1952);
    }

    private int[] pngSize(byte[] png) {
        if (png == null || png.length < 24 || (png[0] & 0xFF) != 0x89) return null;
        int w = ((png[16] & 0xFF) << 24) | ((png[17] & 0xFF) << 16) | ((png[18] & 0xFF) << 8) | (png[19] & 0xFF);
        int h = ((png[20] & 0xFF) << 24) | ((png[21] & 0xFF) << 16) | ((png[22] & 0xFF) << 8) | (png[23] & 0xFF);
        return new int[]{w, h};
    }
}
