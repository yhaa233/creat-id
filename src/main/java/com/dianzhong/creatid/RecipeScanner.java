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
        if (inputs.isEmpty() || slots.isEmpty()) {
            List<String> fieldSlots = new ArrayList<>();
            List<String> fieldInputs = new ArrayList<>();
            for (java.lang.reflect.Field field : hierarchyFields(recipe.getClass())) {
                try {
                    Object value = field.get(recipe);
                    if (value == null) {
                        continue;
                    }
                    boolean isOutput = field.getName().toLowerCase(java.util.Locale.ROOT).contains("output")
                            || field.getName().toLowerCase(java.util.Locale.ROOT).contains("result");
                    if (isOutput) {
                        continue;
                    }
                    collectSlots(value, fieldSlots);
                    collectFlat(value, fieldInputs);
                } catch (Throwable ignored) {
                    // 读不到就跳过这个字段
                }
            }
            if (slots.isEmpty()) {
                slots.addAll(fieldSlots);
            }
            if (inputs.isEmpty()) {
                inputs.addAll(fieldInputs);
            }
        }

        List<String> outputs = outputs(recipe);
        if (outputs.size() == 1 && outputs.get(0).startsWith("UNKNOWN")) {
            List<String> fromFields = new ArrayList<>();
            for (java.lang.reflect.Field field : hierarchyFields(recipe.getClass())) {
                String fieldName = field.getName().toLowerCase(java.util.Locale.ROOT);
                if (!fieldName.contains("output") && !fieldName.contains("result")) {
                    continue;
                }
                try {
                    collectOutputs(field.get(recipe), fromFields);
                } catch (Throwable ignored) {
                    // 跳过
                }
            }
            if (!fromFields.isEmpty()) {
                outputs = fromFields;
            }
        }
        if (recipe.getClass().getName().contains("DissolutionChamber")) {
            StringBuilder fieldNames = new StringBuilder();
            for (java.lang.reflect.Field field : hierarchyFields(recipe.getClass())) {
                fieldNames.append(field.getName()).append(':').append(field.getType().getSimpleName()).append(' ');
            }
            com.dianzhong.creatid.CreatId.LOGGER.info("dissolution fields {} -> {}", id, fieldNames);
        }
        // 工业先锋专用适配：字段名是确定的，直接按名字读，不走启发式
        String className = recipe.getClass().getName();
        if (className.startsWith("com.buuz135.industrial.recipe.")) {
            for (String fieldName : new String[]{"input", "inputFluid"}) {
                try {
                    java.lang.reflect.Field field = recipe.getClass().getField(fieldName);
                    Object value = field.get(recipe);
                    if (value == null) {
                        continue;
                    }
                    java.util.List<String> found = new ArrayList<>();
                    collectSlots(value, found);
                    if (found.isEmpty() && recipe instanceof net.minecraft.world.item.crafting.Recipe<?> asRecipe) {
                        // getStacks() 对标签型流体返回空：退回到配方自己的 codec 序列化，直接读出 id 与数量
                        try {
                            com.mojang.serialization.Codec rawCodec =
                                    (com.mojang.serialization.Codec) asRecipe.getSerializer().codec().codec();
                            var encoded = rawCodec.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, asRecipe);
                            if (encoded.result().isPresent() && encoded.result().get() instanceof net.minecraft.nbt.CompoundTag tag) {
                                java.util.List<String> fromCodec = new ArrayList<>();
                                collectFluidTags(tag, fromCodec);
                                found.addAll(fromCodec);
                            }
                        } catch (Throwable t) {
                            com.dianzhong.creatid.CreatId.LOGGER.warn("codec fluid read failed for {}", id, t);
                        }
                    }                    if (fieldName.equals("inputFluid")) {
                        com.dianzhong.creatid.CreatId.LOGGER.info(
                                "inputFluid {} -> class={} collected={}", id, value.getClass().getSimpleName(), found);
                    }
                    java.util.List<String> flat = new ArrayList<>();
                    collectFlat(value, flat);
                    for (String slot : found) {
                        if (!slots.contains(slot)) {
                            slots.add(slot);
                        }
                    }
                    for (String option : flat) {
                        if (!inputs.contains(option)) {
                            inputs.add(option);
                        }
                    }
                } catch (Throwable ignored) {
                    // 这个配方没有该字段
                }
            }
        }
        // 机械动力序列装配：材料和加工步骤分散在 sequences 里的子配方中，逐层展开收集
        if (recipe.getClass().getSimpleName().contains("SequencedAssembly")) {
            // 过渡物品（incomplete_xxx）是序列装配过程中的中间产物，不是玩家要准备的материал
            java.util.Set<String> transitional = new java.util.HashSet<>();
            for (java.lang.reflect.Field field : hierarchyFields(recipe.getClass())) {
                String fname = field.getName().toLowerCase(java.util.Locale.ROOT);
                if (!fname.contains("transition")) {
                    continue;
                }
                try {
                    Object value = field.get(recipe);
                    if (value instanceof Iterable<?> list) {
                        for (Object element : list) {
                            String elementId = itemIdOf(element);
                            if (elementId != null) {
                                transitional.add(elementId);
                            }
                        }
                    } else {
                        String valueId = itemIdOf(value);
                        if (valueId != null) {
                            transitional.add(valueId);
                        }
                    }
                } catch (Throwable ignored) {
                    // 跳过这个字段
                }
            }
            for (java.lang.reflect.Field field : hierarchyFields(recipe.getClass())) {
                if (!field.getName().toLowerCase(java.util.Locale.ROOT).contains("sequence")) {
                    continue;
                }
                try {
                    Object value = field.get(recipe);
                    if (!(value instanceof Iterable<?> steps)) {
                        continue;
                    }
                    for (Object step : steps) {
                        Object inner = step;
                        for (String accessor : new String[]{"getRecipe", "recipe", "getInnerRecipe"}) {
                            try {
                                Object candidate = step.getClass().getMethod(accessor).invoke(step);
                                if (candidate != null) {
                                    inner = candidate;
                                    break;
                                }
                            } catch (Throwable ignored) {
                                // 换下一个名字
                            }
                        }
                        java.util.List<String> stepSlots = new ArrayList<>();
                        java.util.List<String> stepFlat = new ArrayList<>();
                        // 子配方本身不是物品：显式读它的材料列表
                        for (String accessor : new String[]{"getIngredients", "getFluidIngredients"}) {
                            try {
                                Object ingredients = inner.getClass().getMethod(accessor).invoke(inner);
                                if (ingredients != null) {
                                    collectSlots(ingredients, stepSlots);
                                    collectFlat(ingredients, stepFlat);
                                }
                            } catch (Throwable ignored) {
                                // 该配方没有这个方法
                            }
                        }
                        for (String slot : stepSlots) {
                            if (isOnlyTransitional(slot, transitional)) {
                                continue;
                            }
                            if (!slots.contains(slot)) {
                                slots.add(slot);
                            }
                        }
                        for (String option : stepFlat) {
                            if (!inputs.contains(option)) {
                                inputs.add(option);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // 读不到就跳过
                }
            }
            // 过渡物品只是过程中的中间产物：从槽位里按 id 剔除
            for (int si = 0; si < slots.size(); si++) {
                String slot = slots.get(si);
                int at = slot.lastIndexOf('#');
                String idsPart = at > 0 ? slot.substring(0, at) : slot;
                String amountPart = at > 0 ? slot.substring(at) : "#1";
                List<String> keep = new ArrayList<>();
                int total = 0;
                for (String rawId : idsPart.split("\\|")) {
                    total++;
                    String trimmed = rawId.trim();
                    boolean isTransitional = transitional.contains(trimmed)
                            || trimmed.contains("incomplete_")
                            || trimmed.contains("unprocessed_");
                    if (!isTransitional) {
                        keep.add(trimmed);
                    }
                }
                if (keep.isEmpty()) {
                    slots.remove(si);
                    si--;
                } else if (keep.size() != total) {
                    slots.set(si, String.join("|", keep) + amountPart);
                }
            }
        }
        // 液体字段单独扫：不管物品槽是否为空都要收集，否则溶解室这类配方会漏掉液体要求
        for (java.lang.reflect.Field field : hierarchyFields(recipe.getClass())) {
            try {
                field.trySetAccessible();
            } catch (Throwable ignored) {
                continue;
            }
            String fieldType = field.getType().getName().toLowerCase(java.util.Locale.ROOT);
            String fieldName = field.getName().toLowerCase(java.util.Locale.ROOT);
            String simpleName = field.getName().toLowerCase(java.util.Locale.ROOT);
            boolean extraInput = fieldType.contains("fluid") || simpleName.contains("fluid")
                    || simpleName.contains("modifier");
            // 充能台：modifiers 是周围材料，base 是底座，都算输入
            if (recipe.getClass().getName().startsWith("de.ellpeck.actuallyadditions")
                    && (simpleName.equals("base") || simpleName.contains("modifier"))) {
                extraInput = true;
            }
            if (!extraInput) {
                continue;
            }
            // 输出侧的字段（output / result / fluidResults）不是材料
            if (fieldName.contains("output") || fieldName.contains("result")) {
                continue;
            }
            try {
                Object value = field.get(recipe);
                if (value == null) {
                    continue;
                }
                java.util.List<String> fluidSlots = new ArrayList<>();
                collectSlots(value, fluidSlots);
                for (String fluidSlot : fluidSlots) {
                    if (!slots.contains(fluidSlot)) {
                        slots.add(fluidSlot);
                    }
                }
            } catch (Throwable ignored) {
                // 跳过这个字段
            }
        }
        // 机械动力：按确切字段名读取，优先于通用扫描
        collectCreate(recipe, slots, inputs);

        slots.removeIf(RecipeScanner::isCatalystSlot);
        return new Snapshot(id, type, inputs, outputs, slots);
    }


    // ------------------------------------------------------------------
    // 输入：摊平版（兼容旧格式）
    // ------------------------------------------------------------------

    /** 从配方 NBT 里找流体条目：含 "fluid" 字符串 + "amount" 数字的复合标签。 */
    private static void collectFluidTags(net.minecraft.nbt.Tag tag, List<String> out) {
        if (tag instanceof net.minecraft.nbt.CompoundTag compound) {
            String fluid = compound.contains("fluid") ? compound.getString("fluid")
                    : (compound.contains("Fluid") ? compound.getString("Fluid") : null);
            if (fluid != null && !fluid.isEmpty() && compound.contains("amount")) {
                out.add(fluid + "#" + compound.getInt("amount"));
            }
            for (String key : compound.getAllKeys()) {
                collectFluidTags(compound.get(key), out);
            }
        } else if (tag instanceof net.minecraft.nbt.ListTag list) {
            for (int i = 0; i < list.size(); i++) {
                collectFluidTags(list.get(i), out);
            }
        }
    }
    /**
     * 整个继承链上的字段。
     * <p>
     * 机械动力这类模组把输入输出放在父类（ProcessingRecipe）里，子类（CrushingRecipe 等）
     * 自己一个字段都没有，所以必须连同父类一起扫。
     */
    private static List<java.lang.reflect.Field> hierarchyFields(Class<?> type) {
        List<java.lang.reflect.Field> fields = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (java.lang.reflect.Field field : current.getDeclaredFields()) {
                try {
                    field.trySetAccessible();
                    fields.add(field);
                } catch (Throwable ignored) {
                    // 读不到的字段跳过
                }
            }
        }
        return fields;
    }
    /** 取出物品 id（兼容 ItemStack / Key / 字符串）。 */
    private static String itemIdOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof ItemStack stack) {
            return stack.isEmpty() ? null : String.valueOf(net.minecraft.core.registries.BuiltInRegistries
                    .ITEM.getKey(stack.getItem()));
        }
        try {
            Object id = value.getClass().getMethod("getId").invoke(value);
            if (id != null) {
                return String.valueOf(id);
            }
        } catch (Throwable ignored) {
            // 换下一种
        }
        try {
            Object unwrapped = value.getClass().getMethod("getItem").invoke(value);
            if (unwrapped != null) {
                return itemIdOf(unwrapped);
            }
        } catch (Throwable ignored) {
            // 无法识别
        }
        return null;
    }

    /** 这个槽位是否只包含过渡物品。 */
    private static boolean isOnlyTransitional(String slot, java.util.Set<String> transitional) {
        if (transitional.isEmpty()) {
            return false;
        }
        int at = slot.lastIndexOf('#');
        String ids = at > 0 ? slot.substring(0, at) : slot;
        for (String id : ids.split("\\|")) {
            if (!transitional.contains(id.trim())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 机械动力专用适配。
     * <p>
     * Create 的配方（ProcessingRecipe 及其子类）把材料放在
     * {@code ingredients} / {@code fluidIngredients} 两个字段里，
     * 序列装配还有 {@code sequences} 与 {@code transitionalItem}。
     * 这里按确切字段名读取，避开通用扫描的各种猜测。
     *
     * @return true 表示这条配方已由本适配处理
     */
    private static boolean collectCreate(Object recipe, List<String> slots, List<String> inputs) {
        if (!recipe.getClass().getName().startsWith("com.simibubi.create")) {
            return false;
        }
        boolean handled = false;
        for (java.lang.reflect.Field field : hierarchyFields(recipe.getClass())) {
            String name = field.getName().toLowerCase(java.util.Locale.ROOT);
            boolean isItemInput = name.equals("ingredients") || name.equals("input")
                    || name.equals("inputs");
            boolean isFluidInput = name.equals("fluidingredients") || name.equals("inputfluid")
                    || name.equals("fluidinput");
            boolean isSequence = name.equals("sequences");
            if (!isItemInput && !isFluidInput && !isSequence) {
                continue;
            }
            try {
                Object value = field.get(recipe);
                if (value == null) {
                    continue;
                }
                handled = true;
                if (isFluidInput) {
                    List<String> fluidSlots = new ArrayList<>();
                    collectFluidValue(value, fluidSlots);
                    for (String slot : fluidSlots) {
                        if (!slots.contains(slot)) {
                            slots.add(slot);
                        }
                    }
                } else if (isSequence) {
                    if (value instanceof Iterable<?> steps) {
                        for (Object step : steps) {
                            Object inner = step;
                            for (String accessor : new String[]{"getRecipe", "recipe"}) {
                                try {
                                    Object candidate = step.getClass().getMethod(accessor).invoke(step);
                                    if (candidate != null) {
                                        inner = candidate;
                                        break;
                                    }
                                } catch (Throwable ignored) {
                                    // 换下一个名字
                                }
                            }
                            collectCreateInner(inner, slots, inputs);
                        }
                    }
                } else {
                    List<String> itemSlots = new ArrayList<>();
                    collectSlots(value, itemSlots);
                    List<String> flat = new ArrayList<>();
                    collectFlat(value, flat);
                    for (String slot : itemSlots) {
                        if (!slots.contains(slot)) {
                            slots.add(slot);
                        }
                    }
                    for (String option : flat) {
                        if (!inputs.contains(option)) {
                            inputs.add(option);
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 读不到就跳过
            }
        }
        return handled;
    }

    /** 序列装配里每一道工序的内层配方。 */
    private static void collectCreateInner(Object inner, List<String> slots, List<String> inputs) {
        java.util.List<String> innerSlots = new ArrayList<>();
        for (String accessor : new String[]{"getIngredients", "getFluidIngredients"}) {
            try {
                Object value = inner.getClass().getMethod(accessor).invoke(inner);
                if (value == null) {
                    continue;
                }
                if (accessor.contains("Fluid")) {
                    collectFluidValue(value, innerSlots);
                } else {
                    collectSlots(value, innerSlots);
                }
            } catch (Throwable ignored) {
                // 该配方没有这个方法
            }
        }
        for (String slot : innerSlots) {
            if (!slots.contains(slot)) {
                slots.add(slot);
            }
        }
    }

    /** 流体列表 / 单个流体 / 流体标签。 */
    private static void collectFluidValue(Object value, List<String> out) {
        if (value == null) {
            return;
        }
        if (value instanceof net.neoforged.neoforge.fluids.FluidStack stack) {
            if (!stack.isEmpty()) {
                out.add(String.valueOf(net.minecraft.core.registries.BuiltInRegistries.FLUID
                        .getKey(stack.getFluid())) + "#" + stack.getAmount());
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object element : iterable) {
                collectFluidValue(element, out);
            }
            return;
        }
        // NeoForge 的 SizedFluidIngredient：里面包着 FluidIngredient 与数量
        for (String accessor : new String[]{"ingredient", "getIngredient"}) {
            try {
                Object inner = value.getClass().getMethod(accessor).invoke(value);
                if (inner != null && inner != value) {
                    long count = 1;
                    try {
                        Object rawCount = value.getClass().getMethod("count").invoke(value);
                        if (rawCount instanceof Number number) {
                            count = Math.max(1, number.longValue());
                        }
                    } catch (Throwable ignored) {
                        // 没有 count 就按 1
                    }
                    List<String> nested = new ArrayList<>();
                    collectFluidValue(inner, nested);
                    // SizedFluidIngredient 的 count 就是毫桶数（例如 500 = 500mB），
                    // 不能拿 1 桶(1000) 去乘
                    for (String slot : nested) {
                        int at = slot.lastIndexOf('#');
                        String ids = at > 0 ? slot.substring(0, at) : slot;
                        out.add(ids + "#" + count);
                    }
                    return;
                }
            } catch (Throwable ignored) {
                // 换下一个方法名
            }
        }
        try {
            Object stacks = value.getClass().getMethod("getStacks").invoke(value);
            if (stacks instanceof net.neoforged.neoforge.fluids.FluidStack[] array && array.length > 0) {
                LinkedHashSet<String> ids = new LinkedHashSet<>();
                long amount = 1;
                for (net.neoforged.neoforge.fluids.FluidStack stack : array) {
                    if (!stack.isEmpty()) {
                        ids.add(String.valueOf(net.minecraft.core.registries.BuiltInRegistries.FLUID
                                .getKey(stack.getFluid())));
                        amount = Math.max(amount, stack.getAmount());
                    }
                }
                if (!ids.isEmpty()) {
                    out.add(String.join("|", ids) + "#" + amount);
                }
            }
        } catch (Throwable ignored) {
            // 不是流体原料
        }
    }

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
            if (!name.equals("getIngredients") && !name.equals("getCatalyst") && !name.equals("catalyst")
                    && !name.matches("(?i)[a-z]*input[a-z]*")) {
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
        if (value instanceof net.neoforged.neoforge.fluids.crafting.FluidIngredient fluidIngredient) {
            net.neoforged.neoforge.fluids.FluidStack[] fluids = fluidIngredient.getStacks();
            if (fluids.length > 0) {
                LinkedHashSet<String> ids = new LinkedHashSet<>();
                long amount = 1;
                for (net.neoforged.neoforge.fluids.FluidStack fluid : fluids) {
                    if (!fluid.isEmpty()) {
                        ids.add(String.valueOf(net.minecraft.core.registries.BuiltInRegistries.FLUID
                                .getKey(fluid.getFluid())));
                        amount = Math.max(amount, fluid.getAmount());
                    }
                }
                if (!ids.isEmpty()) {
                    out.add(String.join("|", ids) + "#" + amount);
                }
            }
            return;
        }        if (value instanceof net.neoforged.neoforge.fluids.FluidStack fluidStack) {
            if (!fluidStack.isEmpty()) {
                var fluidKey = net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fluidStack.getFluid());
                out.add(fluidKey + "#" + fluidStack.getAmount());
            }
            return;
        }        if (value instanceof Ingredient ingredient) {
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

    /** 只参与、不消耗的催化剂（神秘农业注魔水晶等）。 */
    private static final java.util.Set<String> CATALYSTS = java.util.Set.of(
            "mysticalagriculture:infusion_crystal",
            "mysticalagriculture:master_infusion_crystal",
            "mysticalagriculture:awakened_supremium_essence_crystal");

    private static boolean isCatalystSlot(String slot) {
        int at = slot.lastIndexOf('#');
        String ids = at > 0 ? slot.substring(0, at) : slot;
        for (String id : ids.split("\\|")) {
            if (!CATALYSTS.contains(id.trim())) {
                return false;
            }
        }
        return true;
    }
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
            if (!name.equals("getIngredients") && !name.equals("getCatalyst") && !name.equals("catalyst")
                    && !name.matches("(?i)[a-z]*input[a-z]*")) {
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
        // NeoForge 的 SizedIngredient：里面包着一个 Ingredient 和一个数量
        for (String accessor : new String[]{"ingredient", "getIngredient"}) {
            try {
                Object inner = value.getClass().getMethod(accessor).invoke(value);
                if (inner != null && inner != value) {
                    long count = 1;
                    try {
                        Object rawCount = value.getClass().getMethod("count").invoke(value);
                        if (rawCount instanceof Number number) {
                            count = number.longValue();
                        }
                    } catch (Throwable ignored) {
                        // 没有 count 就按 1 处理
                    }
                    java.util.List<String> nested = new ArrayList<>();
                    collectSlots(inner, nested);
                    for (String slot : nested) {
                        int at = slot.lastIndexOf('#');
                        String ids = at > 0 ? slot.substring(0, at) : slot;
                        long base = at > 0 ? Long.parseLong(slot.substring(at + 1)) : 1;
                        out.add(ids + "#" + (base * count));
                    }
                    return;
                }
            } catch (Throwable ignored) {
                // 换下一个方法名
            }
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
