package com.dianzhong.creatid;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 只读配方扫描器。
 * <p>
 * 每条配方输出两部分：
 * <ul>
 *   <li>{@code inputs}：摊平的输入 id（便于阅读）</li>
 *   <li>{@code slots}：按槽位分组的输入，槽内用 {@code |} 分隔可选代表，数量用 {@code #} 表示</li>
 * </ul>
 * 槽位信息是校验准确的关键：标签槽必须保留"任选其一"的语义。
 */
public final class RecipeScanner {
    private RecipeScanner() {
    }

    public record Snapshot(String id, String type, List<String> inputs, List<String> outputs,
                           List<String> slots) {
        public String toTsv() {
            return id + "\t" + type + "\t" + String.join(",", inputs) + "\t"
                    + String.join(",", outputs) + "\t" + String.join(";", slots);
        }
    }

    public static List<Snapshot> scanAll(RecipeManager manager) {
        List<Snapshot> out = new ArrayList<>();
        for (RecipeHolder<?> holder : manager.getRecipes()) {
            out.add(scan(holder));
        }
        out.sort((a, b) -> a.id().compareTo(b.id()));
        return out;
    }

    public static Snapshot scan(RecipeHolder<?> holder) {
        String id = String.valueOf(holder.id());
        net.minecraft.world.item.crafting.Recipe<?> recipe = holder.value();
        String type;
        try {
            var serializer = BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer());
            type = serializer == null ? "unknown" : serializer.toString();
        } catch (Throwable t) {
            type = "unknown";
        }

        List<String> slots;
        try {
            slots = slots(recipe);
        } catch (Throwable t) {
            slots = new ArrayList<>();
        }

        List<String> inputs = new ArrayList<>();
        for (String slot : slots) {
            int hash = slot.lastIndexOf('#');
            String options = hash > 0 ? slot.substring(0, hash) : slot;
            for (String option : options.split("\\|")) {
                inputs.add(option);
            }
        }
        if (inputs.isEmpty()) {
            try {
                inputs.addAll(flatInputs(recipe));
            } catch (Throwable ignored) {
                // 读不到就算了
            }
        }

        return new Snapshot(id, type, inputs, outputs(recipe), slots);
    }

    // ------------------------------------------------------------------
    // 输入：摊平版（兼容旧格式）
    // ------------------------------------------------------------------

    private static List<String> flatInputs(Object recipe) {
        List<String> raw = new ArrayList<>();
        if (recipe instanceof CraftingRecipe crafting) {
            for (Ingredient ingredient : crafting.getIngredients()) {
                if (ingredient.isEmpty()) {
                    continue;
                }
                var items = ingredient.getItems();
                if (items.length > 0) {
                    raw.add(idOf(items[0]));
                }
            }
            return raw;
        }
        for (Method method : recipe.getClass().getMethods()) {
            String name = method.getName();
            if (!name.equals("getIngredients") && !name.matches("get[A-Za-z]*Input[A-Za-z]*")) {
                continue;
            }
            if (method.getParameterCount() != 0) {
                continue;
            }
            try {
                collectFlat(method.invoke(recipe), raw);
            } catch (Throwable ignored) {
                // 该重载不可用
            }
        }
        return raw;
    }

    private static void collectFlat(Object value, List<String> out) {
        if (value == null) {
            return;
        }
        if (value instanceof ItemStack stack) {
            if (!stack.isEmpty()) {
                out.add(idOf(stack));
            }
            return;
        }
        if (value instanceof Ingredient ingredient) {
            var items = ingredient.getItems();
            if (items.length > 0) {
                out.add(idOf(items[0]));
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                collectFlat(item, out);
            }
            return;
        }
        try {
            Object reps = value.getClass().getMethod("getRepresentations").invoke(value);
            if (reps instanceof Iterable<?> iterableReps) {
                for (Object rep : iterableReps) {
                    collectFlat(rep, out);
                }
                return;
            }
        } catch (Throwable ignored) {
            // 不是配料包装
        }
        String id = nonItemId(value);
        if (id != null) {
            out.add(id);
        }
    }

    // ------------------------------------------------------------------
    // 输入：按槽分组版
    // ------------------------------------------------------------------

    private static List<String> slots(Object recipe) {
        List<String> out = new ArrayList<>();
        if (recipe instanceof CraftingRecipe crafting) {
            for (Ingredient ingredient : crafting.getIngredients()) {
                if (ingredient.isEmpty()) {
                    continue;
                }
                var items = ingredient.getItems();
                if (items.length == 0) {
                    continue;
                }
                LinkedHashSet<String> ids = new LinkedHashSet<>();
                for (ItemStack item : items) {
                    ids.add(idOf(item));
                }
                out.add(String.join("|", ids) + "#1");
            }
            return out;
        }
        for (Method method : recipe.getClass().getMethods()) {
            String name = method.getName();
            if (!name.equals("getIngredients") && !name.matches("get[A-Za-z]*Input[A-Za-z]*")) {
                continue;
            }
            if (method.getParameterCount() != 0) {
                continue;
            }
            try {
                collectSlots(method.invoke(recipe), out);
            } catch (Throwable ignored) {
                // 该重载不可用
            }
        }
        return out;
    }

    private static void collectSlots(Object value, List<String> out) {
        if (value == null) {
            return;
        }
        if (value instanceof ItemStack stack) {
            if (!stack.isEmpty()) {
                out.add(idOf(stack) + "#" + stack.getCount());
            }
            return;
        }
        if (value instanceof Ingredient ingredient) {
            var items = ingredient.getItems();
            if (items.length > 0) {
                LinkedHashSet<String> ids = new LinkedHashSet<>();
                for (ItemStack item : items) {
                    ids.add(idOf(item));
                }
                out.add(String.join("|", ids) + "#1");
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                collectSlots(item, out);
            }
            return;
        }
        // Mekanism 配料包装：一个包装 = 一个槽，代表们是可选值
        try {
            Object reps = value.getClass().getMethod("getRepresentations").invoke(value);
            if (reps instanceof Iterable<?> iterableReps) {
                LinkedHashSet<String> ids = new LinkedHashSet<>();
                long amount = 1;
                for (Object rep : iterableReps) {
                    if (rep instanceof ItemStack stack && !stack.isEmpty()) {
                        ids.add(idOf(stack));
                        amount = Math.max(amount, stack.getCount());
                    } else {
                        String id = nonItemId(rep);
                        if (id != null) {
                            ids.add(id);
                            amount = Math.max(amount, nonItemAmount(rep));
                        }
                    }
                }
                if (!ids.isEmpty()) {
                    out.add(String.join("|", ids) + "#" + amount);
                }
                return;
            }
        } catch (Throwable ignored) {
            // 不是配料包装
        }
        String id = nonItemId(value);
        if (id != null) {
            out.add(id + "#" + nonItemAmount(value));
        }
    }

    // ------------------------------------------------------------------
    // 输出
    // ------------------------------------------------------------------

    private static List<String> outputs(Object recipe) {
        List<String> out = new ArrayList<>();
        for (Method method : recipe.getClass().getMethods()) {
            String name = method.getName();
            // 覆盖 getOutput / getMainOutputDefinition / getSecondaryOutputDefinition 等常见命名
            boolean looksLikeOutput = name.equals("output") || name.equals("outputs")
                    || name.equals("result") || name.equals("results")
                    || name.equals("getResultItem") || name.equals("getResultStacks")
                    || name.matches("get[A-Za-z]*Output[A-Za-z]*")
                    || name.matches("get[A-Za-z]*Result[A-Za-z]*");
            if (!looksLikeOutput) {
                continue;
            }
            if (method.getParameterCount() > 1) {
                continue;
            }
            try {
                Object result = method.getParameterCount() == 0
                        ? method.invoke(recipe)
                        : method.invoke(recipe, new Object[]{null});
                collectOutputsDeep(result, out, 0);
            } catch (Throwable ignored) {
                // 该重载不可用
            }
        }
        var unique = new LinkedHashSet<>(out);
        out = new ArrayList<>(unique);
        if (out.isEmpty()) {
            out.add("UNKNOWN");
        }
        return out;
    }

    private static final String[] OUTPUT_ACCESSORS =
            {"getStack", "stack", "getItem", "item", "getChancedStack", "getResult", "result", "getValue",
             "value", "getOutput", "output", "left", "right", "getFirst", "getSecond"};

    /** Recursively dig out item stacks from a mod-specific output wrapper (depth-limited). */
    private static void collectOutputsDeep(Object value, List<String> out, int depth) {
        if (value == null || depth > 3) {
            return;
        }
        if (value instanceof ItemStack stack) {
            if (!stack.isEmpty()) {
                out.add(idOf(stack) + "x" + stack.getCount());
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                collectOutputsDeep(item, out, depth + 1);
            }
            return;
        }
        String id = nonItemId(value);
        if (id != null) {
            out.add(id + "x" + nonItemAmount(value));
            return;
        }
        for (String accessor : OUTPUT_ACCESSORS) {
            try {
                Object inner = value.getClass().getMethod(accessor).invoke(value);
                if (inner != null && inner != value) {
                    collectOutputsDeep(inner, out, depth + 1);
                    return;
                }
            } catch (Throwable ignored) {
                // try next accessor
            }
        }
    }
    private static void collectOutputs(Object value, List<String> out) {
        if (value == null) {
            return;
        }
        if (value instanceof ItemStack stack) {
            if (!stack.isEmpty()) {
                out.add(idOf(stack) + "x" + stack.getCount());
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                collectOutputs(item, out);
            }
            return;
        }
        String id = nonItemId(value);
        if (id != null) {
            out.add(id + "x" + nonItemAmount(value));
            return;
        }
        // EnderIO OutputStack and similar wrappers: unwrap to the inner ItemStack
        for (String accessor : new String[]{"getStack", "stack", "getItem", "item"}) {
            try {
                Object inner = value.getClass().getMethod(accessor).invoke(value);
                if (inner instanceof ItemStack innerStack) {
                    if (!innerStack.isEmpty()) {
                        out.add(idOf(innerStack) + "x" + innerStack.getCount());
                    }
                    return;
                }
            } catch (Throwable ignored) {
                // try next accessor
            }
        }
    }

    // ------------------------------------------------------------------
    // 通用
    // ------------------------------------------------------------------

    private static String idOf(ItemStack stack) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key == null ? "unknown" : key.toString();
    }

    private static String nonItemId(Object value) {
        String mekId = MekanismCompat.chemicalId(value);
        if (mekId != null) {
            return mekId;
        }
        String cls = value.getClass().getSimpleName();
        try {
            if (cls.contains("FluidStack")) {
                Object fluid = value.getClass().getMethod("getFluid").invoke(value);
                if (fluid instanceof net.minecraft.world.level.material.Fluid f) {
                    var key = BuiltInRegistries.FLUID.getKey(f);
                    return key == null ? null : key.toString();
                }
            }
            if (cls.contains("ChemicalStack")) {
                try {
                    Object holder = value.getClass().getMethod("getChemicalHolder").invoke(value);
                    Object optKey = holder.getClass().getMethod("unwrapKey").invoke(holder);
                    if (optKey instanceof java.util.Optional<?> opt && opt.isPresent()) {
                        Object key = opt.get();
                        Object loc = key.getClass().getMethod("location").invoke(key);
                        if (loc != null) {
                            return String.valueOf(loc);
                        }
                    }
                } catch (Throwable ignoredHolder) {
                    // fall through
                }
                Object chemical = value.getClass().getMethod("getChemical").invoke(value);
                if (chemical != null) {
                    for (String name : new String[]{"getRegistryName", "getId", "getName"}) {
                        try {
                            Object id = chemical.getClass().getMethod(name).invoke(chemical);
                            if (id != null) {
                                String text = String.valueOf(id);
                                var matcher2 = java.util.regex.Pattern
                                        .compile("([a-z0-9_.-]+:[a-z0-9_/.-]+)")
                                        .matcher(text);
                                if (matcher2.find()) {
                                    return matcher2.group(1);
                                }
                                int slash = text.lastIndexOf('/');
                                return slash >= 0 ? text.substring(slash + 1) : text;
                            }
                        } catch (Throwable ignored) {
                            // 换下一个方法名
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // 结构不认识
        }
        // 兜底：从对象的描述里抓命名空间 id（Mekanism 的 getName 返回翻译名，不能直接用）
        var matcher = java.util.regex.Pattern
                .compile("([a-z0-9_.-]+:[a-z0-9_/.-]+)")
                .matcher(String.valueOf(value));
        return matcher.find() ? matcher.group(1) : null;
    }

    private static long nonItemAmount(Object value) {
        long mekAmount = MekanismCompat.chemicalAmount(value);
        if (mekAmount > 0) {
            return mekAmount;
        }
        for (String name : new String[]{"getAmount", "amount"}) {
            try {
                Object amount = value.getClass().getMethod(name).invoke(value);
                if (amount instanceof Number number) {
                    return number.longValue();
                }
            } catch (Throwable ignored) {
                // 换下一个方法名
            }
        }
        return 1L;
    }
}
