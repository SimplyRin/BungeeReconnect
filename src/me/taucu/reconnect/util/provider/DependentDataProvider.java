package me.taucu.reconnect.util.provider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.plugin.Plugin;

@RequiredArgsConstructor 
public class DependentDataProvider {
    
    Map<Locale, DependentData> dataByLocale = new HashMap<>();

    //todo
    Locale defaultLocale = new Locale("en", "US");

    final Plugin plugin;

    Yaml yaml = new Yaml(
        new Constructor(DependentData.class)
    );
    
    Path getLocaleFolder() {
        return plugin.getDataFolder().toPath()
            .resolve("lang");
    }

    @SneakyThrows
    void loadFiles() {
        for (File file : getLocaleFolder().toFile().listFiles()) {
            String[] localeEntry = file.getName()
                .replace(".yml", "")
                .split("_");

            dataByLocale.put(
                new Locale(localeEntry[0], localeEntry[1]), yaml.<DependentData>load(new FileInputStream(file))
                );
        }
    }

    static final String [] langs = {
        "en_US.yml", "ru_RU.yml"
    };

    @SneakyThrows
    void loadResources() {
        for (String filename : langs) {

            File file = getLocaleFolder().resolve(filename).toFile();
            if (file.exists()) {
                continue;
            }

            FileOutputStream out = new FileOutputStream(file);
            out.write(
                plugin.getResourceAsStream("lang/" + filename).readAllBytes());
            out.close();
        }
    }    

    public void load() {
        getLocaleFolder().toFile().mkdir();
        loadResources();
        loadFiles();
    }

    public DependentData getDefault() {
        return dataByLocale.get(defaultLocale);
    }

    public DependentData getForLocale(Locale locale) {
        DependentData data = dataByLocale.get(locale);
        if (data == null) {
            return getDefault();
        } else {
            return data;
        }
    }
}
