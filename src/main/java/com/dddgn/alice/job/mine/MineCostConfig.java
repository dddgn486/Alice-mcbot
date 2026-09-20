package com.dddgn.alice.job.mine;

import com.dddgn.alice.log.BotLog;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * **挖矿成本模型配置**（`config/alice-mine.json`，用户 2026-09-20 裁定其二：权重放配置、默认 0）。
 *
 * <p>沿用 `LlmConfig` 的 JSON 形态（`config/alice-llm.json`，`FMLPaths`）：文件不存在 ⇒
 * **不报错、不猜**，写一份模板并如实登记"用默认值"。理由：权重属于**部署环境**（每台机器/每个存档的偏好
 * 不同），不是代码。
 *
 * <p>**`valueWeight` 默认 0.0 = 关闭**（用户裁定）：<b>关闭时成本模型仍然按成本排序</b>，但"哪块矿更值钱"
 * 完全不参与 ⇒ 现有行为的风险面最小，判据也能咬住（"把权重设回 0 ⇒ 选点必须回到纯成本结果"）。
 *
 * <p>单位（可解释性）：`valueWeight` = **"最高档矿最多值多少格的额外路程"**。归一化价值见
 * {@link MineValueTable#normalized}。
 */
public final class MineCostConfig {

    private static final String FILE_NAME = "alice-mine.json";

    /** 默认权重 = 0（关闭价值项）。 */
    public static final double DEFAULT_VALUE_WEIGHT = 0.0D;

    private static MineCostConfig cached;

    private final double valueWeight;
    private final String loadNote;

    private MineCostConfig(double valueWeight, String loadNote) {
        this.valueWeight = valueWeight;
        this.loadNote = loadNote;
    }

    /** 夹具/测试用：显式权重（**不读文件**，确定性）。 */
    public static MineCostConfig of(double valueWeight) {
        return new MineCostConfig(valueWeight, "显式构造（夹具/调用方给定）");
    }

    /** 生产：读配置（不存在则写模板 + 用默认值）。 */
    public static synchronized MineCostConfig load() {
        if (cached != null) {
            return cached;
        }
        Path path = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
        if (!Files.isRegularFile(path)) {
            writeTemplate(path);
            cached = new MineCostConfig(DEFAULT_VALUE_WEIGHT, "配置文件不存在（已写模板）⇒ 用默认权重 "
                    + DEFAULT_VALUE_WEIGHT);
            BotLog.info("[MineCost] {}", cached.loadNote);
            return cached;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            double weight = root.has("valueWeight")
                    ? root.get("valueWeight").getAsDouble() : DEFAULT_VALUE_WEIGHT;
            cached = new MineCostConfig(weight, "读取自 config/" + FILE_NAME);
        } catch (Exception exception) {
            // 坏了也**不许**让挖矿起不来：用默认值 + 如实告警（与 LlmConfig 同口径）
            cached = new MineCostConfig(DEFAULT_VALUE_WEIGHT,
                    "配置读取失败（" + exception.getClass().getSimpleName() + "）⇒ 用默认权重");
            BotLog.warn("[MineCost] config/{} 读取失败：{} ⇒ 用默认权重 {}",
                    FILE_NAME, exception.toString(), DEFAULT_VALUE_WEIGHT);
        }
        return cached;
    }

    /** 夹具收尾用（避免测试间互相污染）。 */
    public static synchronized void resetCache() {
        cached = null;
    }

    public double valueWeight() {
        return valueWeight;
    }

    /**
     * 价值项**是否启用**：用户裁定 = **只在多目标种类任务里启用**。
     *
     * @param multiKind 本次候选里是否出现了 **≥2 种方块**（由 {@code CostOptimalPolicy} 现场判定）
     */
    public boolean valueEnabled(boolean multiKind) {
        return multiKind && valueWeight != 0.0D;
    }

    public String describe() {
        return "valueWeight=" + valueWeight + (valueWeight == 0.0D ? "（价值项关闭）" : "") + " · " + loadNote;
    }

    private static void writeTemplate(Path path) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, """
                    {
                      "// valueWeight": "最高档矿（tier3）最多值多少格的额外路程；0 = 关闭价值项（默认）",
                      "// 价值表": "数据包标签 #alice:mine_value/tier1|tier2|tier3；未登记的方块一律按 0（不猜）",
                      "// 启用范围": "价值项只在多目标种类任务里生效（候选里出现 ≥2 种方块）",
                      "valueWeight": 0.0
                    }
                    """, StandardCharsets.UTF_8);
            BotLog.info("[MineCost] 已写模板 config/{}（valueWeight 默认 {}）", FILE_NAME,
                    DEFAULT_VALUE_WEIGHT);
        } catch (Exception exception) {
            BotLog.warn("[MineCost] 写模板 config/{} 失败：{}（继续用默认值）", FILE_NAME, exception.toString());
        }
    }
}
