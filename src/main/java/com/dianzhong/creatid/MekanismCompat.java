package com.dianzhong.creatid;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

/**
 * 版本无关的 Mekanism 化学品读取。
 * <p>
 * 不使用编译期 API，全部走反射并对多种历史写法做兜底，这样任何 Mekanism 版本都能工作：
 * <ol>
 *   <li>{@code getChemicalHolder()} → {@code Holder#unwrapKey()} → {@code location()}</li>
 *   <li>{@code getChemical()} → {@code getRegistryName()} / {@code getRegistryKey()} / {@code builtInRegistryHolder()}</li>
 *   <li>通过 {@code MekanismAPI.CHEMICAL_REGISTRY} 或 {@code registryAccess()} 反查注册名</li>
 *   <li>最后从对象的 {@code toString()} 里抽取 {@code namespace:path}</li>
 * </ol>
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

    /** 化学物质的注册名（如 {@code mekanism:redstone}）；读不到返回 null。 */
    public static String chemicalId(Object value) {
        if (!isLoaded() || value == null) {
            return null;
        }
        if (!value.getClass().getName().contains("ChemicalStack")) {
            return null;
        }
        try {
            String byHolder = fromHolder(value);
            if (byHolder != null) {
                return byHolder;
            }
            Object chemical = invokeNoArg(value, "getChemical");
            if (chemical != null) {
                String byChemical = fromChemical(chemical);
                if (byChemical != null) {
                    return byChemical;
                }
            }
        } catch (Throwable ignored) {
            // 下面还有兜底
        }
        return scanId(String.valueOf(value));
    }

    /** 化学物质数量；读不到返回 0。 */
    public static long chemicalAmount(Object value) {
        if (!isLoaded() || value == null) {
            return 0;
        }
        for (String name : new String[]{"getAmount", "amount"}) {
            Object amount = invokeNoArg(value, name);
            if (amount instanceof Number number) {
                return number.longValue();
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------

    private static String fromHolder(Object stack) {
        Object holder = invokeNoArg(stack, "getChemicalHolder");
        if (holder == null) {
            return null;
        }
        Object key = invokeNoArg(holder, "unwrapKey");
        if (key instanceof Optional<?> opt && opt.isPresent()) {
            String id = fromRegistryKey(opt.get());
            if (id != null) {
                return id;
            }
        }
        Object chemical = invokeNoArg(holder, "value");
        return chemical == null ? null : fromChemical(chemical);
    }

    private static String fromChemical(Object chemical) {
        for (String name : new String[]{"getRegistryName", "getRegistryKey", "getName", "getId"}) {
            Object result = invokeNoArg(chemical, name);
            if (result == null) {
                continue;
            }
            String id = fromRegistryKey(result);
            if (id != null) {
                return id;
            }
            String text = String.valueOf(result);
            if (text.contains(":")) {
                return scanId(text);
            }
        }
        Object holder = invokeNoArg(chemical, "builtInRegistryHolder");
        if (holder != null) {
            return fromHolderKey(holder);
        }
        return registryLookup(chemical);
    }

    private static String fromHolderKey(Object holder) {
        Object key = invokeNoArg(holder, "unwrapKey");
        if (key instanceof Optional<?> opt && opt.isPresent()) {
            return fromRegistryKey(opt.get());
        }
        return null;
    }

    private static String fromRegistryKey(Object key) {
        if (key == null) {
            return null;
        }
        Object location = invokeNoArg(key, "location");
        if (location == null) {
            location = invokeNoArg(key, "registry");
        }
        if (location != null) {
            String text = String.valueOf(location);
            return text.contains(":") ? text : null;
        }
        return null;
    }

    /** 通过 Mekanism 的化学品注册表反查。 */
    private static String registryLookup(Object chemical) {
        try {
            Class<?> api = Class.forName("mekanism.api.MekanismAPI");
            for (String fieldName : new String[]{"CHEMICAL_REGISTRY", "CHEMICAL"}) {
                try {
                    Field field = api.getField(fieldName);
                    Object registry = field.get(null);
                    String id = registryGetKey(registry, chemical);
                    if (id != null) {
                        return id;
                    }
                } catch (Throwable ignored) {
                    // 换下一个字段
                }
            }
        } catch (Throwable ignored) {
            // 没有 MekanismAPI
        }
        return null;
    }

    private static String registryGetKey(Object registry, Object chemical) {
        if (registry == null) {
            return null;
        }
        try {
            Method getKey = registry.getClass().getMethod("getKey", Object.class);
            Object key = getKey.invoke(registry, chemical);
            return key == null ? null : String.valueOf(key);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object invokeNoArg(Object target, String name) {
        try {
            Method method = target.getClass().getMethod(name);
            if (method.getParameterCount() != 0) {
                return null;
            }
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String scanId(String text) {
        var matcher = java.util.regex.Pattern.compile("([a-z0-9_.-]+:[a-z0-9_/.-]+)").matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }
}
