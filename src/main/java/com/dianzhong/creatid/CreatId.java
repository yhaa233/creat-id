package com.dianzhong.creatid;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * creat-id：只读的配方探测器。
 * <p>
 * 不做任何改动，只在玩家执行命令时把服务端的配方表（全部配方、输入、输出）导出成文件，
 * 或者按物品反查“谁能产出它 / 它需要什么”。
 */
@Mod(CreatId.MODID)
public class CreatId {
    public static final String MODID = "creat_id";
    public static final Logger LOGGER = LoggerFactory.getLogger("creat-id");

    public CreatId(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        LOGGER.info("creat-id loaded: recipe probing is read-only");
    }

    /** 进游戏（服务端启动）就自动建立索引并导出一份完整配方表，不需要玩家敲命令。 */
    private void onServerStarted(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        try {
            RecipeIndex.build(event.getServer());
            var snapshots = RecipeIndex.all();
            String stamp = java.time.format.DateTimeFormatter
                    .ofPattern("yyyyMMdd-HHmmss")
                    .format(java.time.LocalDateTime.now());
            var file = java.nio.file.Path.of("logs", "recipes-startup-" + stamp + ".tsv");
            StringBuilder sb = new StringBuilder("id\ttype\tinputs\toutputs\tslots\n");
            for (var snapshot : snapshots) {
                sb.append(snapshot.toTsv()).append('\n');
            }
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(file, sb.toString());
            LOGGER.info("recipes dumped on startup: {} entries -> {}", snapshots.size(), file.toAbsolutePath());
        } catch (Throwable t) {
            LOGGER.error("startup recipe dump failed", t);
        }
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        RecipeDumpCommand.register(event.getDispatcher());
    }
}
