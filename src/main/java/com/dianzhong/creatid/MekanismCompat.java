package com.dianzhong.creatid;

import java.util.ArrayList;
import java.util.List;

/**
 * Mekanism 兼容层：编译期依赖 Mekanism API，运行期用 ModList 守卫。
 * <p>
 * 反射拿不到化学品 id（{@code getName()} 返回的是翻译名），这里直接用真实 API：
 * {@code ChemicalStack#getChemicalHolder()} → {@code Holder#unwrapKey()} → {@code ResourceKey#location()}。
 * <p>
 * 引用 Mekanism 类的代码全部放在内部类里，未安装 Mekanism 时不会被加载。
 */
public final class MekanismCompat {
    private static Boolean loaded;

    private MekanismCompat() {
    }

    public static boolean isLoaded() {
        if (loaded == null) {
            try {
                loaded = net.neoforged.fml.ModList.get().isLoaded("mekanism");
            } catch (Throwable t) {
                loaded = false;
            }
        }
        return loaded;
    }

    /** 化学物质的注册名（{@code mekanism:redstone}），取不到返回 null。 */
    public static String chemicalId(Object value) {
        if (!isLoaded() || value == null) {
            return null;
        }
        try {
            return Impl.chemicalId(value);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 化学物质的数量。 */
    public static long chemicalAmount(Object value) {
        if (!isLoaded() || value == null) {
            return 0;
        }
        try {
            return Impl.chemicalAmount(value);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static final class Impl {
        static String chemicalId(Object value) {
            if (!(value instanceof mekanism.api.chemical.ChemicalStack stack)) {
                return null;
            }
            var key = stack.getChemicalHolder().unwrapKey();
            return key.map(k -> k.location().toString()).orElse(null);
        }

        static long chemicalAmount(Object value) {
            if (!(value instanceof mekanism.api.chemical.ChemicalStack stack)) {
                return 0;
            }
            return stack.getAmount();
        }

        private static void collect(Object value, List<Object> out) {
            if (value == null) {
                return;
            }
            if (value instanceof Iterable<?> iterable) {
                for (Object item : iterable) {
                    collect(item, out);
                }
                return;
            }
            out.add(value);
        }
    }
}
