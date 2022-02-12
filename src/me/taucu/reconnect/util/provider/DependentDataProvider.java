package me.taucu.reconnect.util.provider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.google.common.io.ByteStreams;

import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
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
    
    Path getLocaleFolder() {
        return plugin.getDataFolder().toPath()
            .resolve("lang");
    }

    @SneakyThrows
    void loadFiles() {
        ConfigurationProvider provider = ConfigurationProvider.getProvider(YamlConfiguration.class);
        for (File file : getLocaleFolder().toFile().listFiles()) {
            String[] localeEntry = file.getName()
                .replace(".yml", "")
                .split("_");

            Configuration langConf = provider.load(file);

            dataByLocale.put(
                new Locale(localeEntry[0], localeEntry[1]), new DependentData(
                        new TitleViewEntry(langConf.getString("reconnectionTitle.title"), langConf.getString("reconnectionTitle.subTitle"), langConf.getString("reconnectionTitle.actionBar")),
                        new TitleViewEntry(langConf.getString("connectionTitle.title"), langConf.getString("connectionTitle.subTitle"), langConf.getString("connectionTitle.actionBar")),
                        new TitleViewEntry(langConf.getString("failTitle.title"), langConf.getString("failTitle.subTitle"), langConf.getString("failTitle.actionBar")),
                        langConf.getString("failKickMessage")
                    )
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

            try (InputStream is = plugin.getResourceAsStream("lang/" + filename); OutputStream os = new FileOutputStream(file)) {
                ByteStreams.copy(is, os);
            }
  
        }
    }    

    public void setDefaultLocale(Locale defaultLocale) {
        this.defaultLocale = defaultLocale;
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
