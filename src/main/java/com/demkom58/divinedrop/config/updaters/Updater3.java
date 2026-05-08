package com.demkom58.divinedrop.config.updaters;

import com.demkom58.divinedrop.config.Config;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.function.Consumer;

public class Updater3 implements Consumer<Config> {
    @Override
    public void accept(Config config) {
        final FileConfiguration cfg = config.getConfig();

        cfg.set("config-version", 4);
        cfg.set("max-stack", 512);
        cfg.set("stack-radius", 6);

        config.save();
    }
}
