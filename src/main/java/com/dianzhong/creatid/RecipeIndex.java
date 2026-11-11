package com.dianzhong.creatid;

import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配方索引：进游戏自动建立一次，之后所有查询都走内存，不再重复遍历。
 * <p>
 * 供本模组命令使用，也可以被其他模组直接调用：
 * <pre>
 *   boolean real = RecipeIndex.produces("mekanism:alloy_infused");
 *   List&lt;String&gt; who = RecipeIndex.producersOf("minecraft:diamond");
 * </pre>
 */
public final class RecipeIndex {
    private static volatile boolean ready;
    private static List<RecipeScanner.Snapshot> all = List.of();
    /** 产物 id → 产出它的配方 id 列表 */
    private static Map<String, List<String>> byOutput = Map.of();
    /** 输入 id → 使用它的配方 id 列表 */
    private static Map<String, List<String>> byInput = Map.of();
    /** 配方 id → 快照 */
    private static Map<String, RecipeScanner.Snapshot> byId = Map.of();

    private RecipeIndex() {
    }

    /** 首次调用时建立索引；服务端启动事件里也会主动调用。 */
    public static synchronized void ensure(MinecraftServer server) {
        if (ready || server == null) {
            return;
        }
        build(server);
    }

    public static synchronized void build(MinecraftServer server) {
        long started = System.currentTimeMillis();
        List<RecipeScanner.Snapshot> snapshots = RecipeScanner.scanAll(server.getRecipeManager());

        Map<String, List<String>> outputs = new HashMap<>();
        Map<String, List<String>> inputs = new HashMap<>();
        Map<String, RecipeScanner.Snapshot> ids = new HashMap<>();

        for (RecipeScanner.Snapshot snapshot : snapshots) {
            ids.put(snapshot.id(), snapshot);
            for (String entry : snapshot.outputs()) {
                String id = stripCount(entry);
                outputs.computeIfAbsent(id, k -> new ArrayList<>()).add(snapshot.id());
            }
            for (String entry : snapshot.inputs()) {
                String id = stripCount(entry);
                inputs.computeIfAbsent(id, k -> new ArrayList<>()).add(snapshot.id());
            }
        }

        all = List.copyOf(snapshots);
        byOutput = Collections.unmodifiableMap(outputs);
        byInput = Collections.unmodifiableMap(inputs);
        byId = Collections.unmodifiableMap(ids);
        ready = true;

        CreatId.LOGGER.info("recipe index ready: {} recipes, {} outputs, {} inputs ({} ms)",
                all.size(), byOutput.size(), byInput.size(),
                System.currentTimeMillis() - started);
    }

    public static boolean isReady() {
        return ready;
    }

    public static List<RecipeScanner.Snapshot> all() {
        return all;
    }

    /** 某个物品/流体/化学品是否有真实配方产出它。 */
    private static int queried;

    public static boolean produces(String key) {
        boolean found = byOutput.containsKey(key);
        if (queried++ < 12) {
            CreatId.LOGGER.info("RecipeIndex.produces({}) -> {} (known outputs: {})", key, found, byOutput.size());
        }
        return found;
    }

    /** 产出该物品的所有配方 id。 */
    public static List<String> producersOf(String key) {
        return byOutput.getOrDefault(key, List.of());
    }

    /** 使用该物品作为输入的所有配方 id。 */
    public static List<String> consumersOf(String key) {
        return byInput.getOrDefault(key, List.of());
    }

    /** 某条配方的输入槽（槽内 id 用 | 分隔，数量用 # 表示）。 */
    /** Output entries WITH counts (e.g. "mekanism:uranium_hexafluoridex2"). */
    public static List<String> outputAmountsOf(String recipeId) {
        RecipeScanner.Snapshot snapshot = byId.get(recipeId);
        return snapshot == null ? List.of() : snapshot.outputs();
    }
    /** Output ids (without counts) of one recipe. */
    public static List<String> outputsOf(String recipeId) {
        RecipeScanner.Snapshot snapshot = byId.get(recipeId);
        if (snapshot == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String entry : snapshot.outputs()) {
            int at = entry.lastIndexOf('x');
            out.add(at > 0 ? entry.substring(0, at) : entry);
        }
        return out;
    }
    public static List<String> slotsOf(String recipeId) {
        RecipeScanner.Snapshot snapshot = byId.get(recipeId);
        return snapshot == null ? List.of() : snapshot.slots();
    }
    /** 某条配方声明的输入 id（去掉数量），供外部做输入比对。 */
    public static List<String> inputsOf(String recipeId) {
        RecipeScanner.Snapshot snapshot = byId.get(recipeId);
        if (snapshot == null) {
            return List.of();
        }
        // 输入列没有数量后缀，直接返回；不能用 lastIndexOf('x') 去切，否则会把 id 里的 x 切掉
        return new ArrayList<>(snapshot.inputs());
    }
    public static RecipeScanner.Snapshot get(String recipeId) {
        return byId.get(recipeId);
    }

    /** 汇总：每条配方 id → 输入/输出（输入按数量聚合）。 */
    public static Map<String, String> summary() {
        Map<String, String> out = new LinkedHashMap<>();
        for (RecipeScanner.Snapshot snapshot : all) {
            out.put(snapshot.id(), "in=" + snapshot.inputs() + " out=" + snapshot.outputs());
        }
        return out;
    }

    private static String stripCount(String entry) {
        int at = entry.lastIndexOf('x');
        return at > 0 ? entry.substring(0, at) : entry;
    }
}
