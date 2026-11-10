package com.dianzhong.creatid;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * /recipes dump [命名空间]      —— 导出全部配方到 logs/recipes-*.tsv
 * /recipes find &lt;物品id&gt;    —— 反查哪些配方能产出它
 * /recipes inputs &lt;物品id&gt;  —— 反查哪些配方把它当输入
 */
public final class RecipeDumpCommand {
    private RecipeDumpCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("recipes")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("dump")
                        .executes(ctx -> dump(ctx, null))
                        .then(Commands.argument("namespace", StringArgumentType.word())
                                .executes(ctx -> dump(ctx, StringArgumentType.getString(ctx, "namespace")))))
                .then(Commands.literal("find")
                        .then(Commands.argument("item", StringArgumentType.greedyString())
                                .executes(ctx -> find(ctx, StringArgumentType.getString(ctx, "item"), true))))
                .then(Commands.literal("inputs")
                        .then(Commands.argument("item", StringArgumentType.greedyString())
                                .executes(ctx -> find(ctx, StringArgumentType.getString(ctx, "item"), false))))
                .then(Commands.literal("count")
                        .executes(RecipeDumpCommand::count)));
    }

    private static int count(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        RecipeIndex.ensure(server);
        int size = RecipeIndex.all().size();
        ctx.getSource().sendSuccess(() -> Component.literal("已加载配方数：" + size), false);
        return size;
    }

    private static int dump(CommandContext<CommandSourceStack> ctx, String namespace) {
        MinecraftServer server = ctx.getSource().getServer();
        RecipeIndex.ensure(server);
        var snapshots = RecipeIndex.all();

        StringBuilder sb = new StringBuilder("id\ttype\tinputs\toutputs\tslots\n");
        int written = 0;
        for (var snapshot : snapshots) {
            if (namespace != null && !snapshot.id().startsWith(namespace + ":")) {
                continue;
            }
            sb.append(snapshot.toTsv()).append('\n');
            written++;
        }

        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
        String suffix = namespace == null ? "" : "-" + namespace;
        Path file = Path.of("logs", "recipes" + suffix + "-" + stamp + ".tsv");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, sb.toString());
        } catch (Exception e) {
            CreatId.LOGGER.error("导出配方失败", e);
            ctx.getSource().sendFailure(Component.literal("导出失败：" + e.getMessage()));
            return 0;
        }

        int total = written;
        ctx.getSource().sendSuccess(
                () -> Component.literal("已导出 " + total + " 条配方到 " + file.toAbsolutePath()), false);
        CreatId.LOGGER.info("recipes dumped: {} entries -> {}", total, file.toAbsolutePath());
        return total;
    }

    private static int find(CommandContext<CommandSourceStack> ctx, String query, boolean asOutput) {
        MinecraftServer server = ctx.getSource().getServer();
        RecipeIndex.ensure(server);
        List<RecipeScanner.Snapshot> snapshots = RecipeIndex.all();

        List<String> hits = new java.util.ArrayList<>();
        for (var snapshot : snapshots) {
            List<String> side = asOutput ? snapshot.outputs() : snapshot.inputs();
            for (String entry : side) {
                if (entry.startsWith(query)) {
                    hits.add(snapshot.id() + "  [" + snapshot.type() + "]  in=" + snapshot.inputs()
                            + "  out=" + snapshot.outputs());
                    break;
                }
            }
        }

        if (hits.isEmpty()) {
            ctx.getSource().sendSuccess(
                    () -> Component.literal("没有找到与 " + query + " 相关的配方"), false);
            return 0;
        }

        int limit = Math.min(hits.size(), 20);
        for (int i = 0; i < limit; i++) {
            int index = i;
            ctx.getSource().sendSuccess(() -> Component.literal(hits.get(index)), false);
        }
        if (hits.size() > limit) {
            int more = hits.size() - limit;
            ctx.getSource().sendSuccess(() -> Component.literal("...还有 " + more + " 条，用 /recipes dump 导出全部"), false);
        }
        return hits.size();
    }
}
