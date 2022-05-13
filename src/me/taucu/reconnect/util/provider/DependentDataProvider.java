package me.taucu.reconnect.util.provider;

import com.google.common.io.ByteStreams;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import me.taucu.reconnect.util.ConfigUtil;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor 
public class DependentDataProvider {
    
    Map<Locale, DependentData> dataByLocale = new ConcurrentHashMap<>();
    Map<String, Locale> localeByLang = new ConcurrentHashMap<>();

    Locale defaultLocale = new Locale("en", "US");

    final Plugin plugin;
    
    Path getLocaleFolder() {
        return plugin.getDataFolder().toPath()
            .resolve("lang");
    }

    @SneakyThrows
    void loadFiles() {
        ConfigurationProvider provider = ConfigurationProvider.getProvider(YamlConfiguration.class);
        Configuration defaultConf = provider.load(plugin.getResourceAsStream("lang/en_US.yml"));

        ConcurrentHashMap<Locale, DependentData> dataByLocale = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, Locale> localeByLang = new ConcurrentHashMap<>();

        for (File file : getLocaleFolder().toFile().listFiles()) {
            String[] localeEntry = file.getName()
                .replace(".yml", "")
                .split("_");

            Configuration langConf = provider.load(file);

            if (langConf.getKeys().isEmpty()) {
                plugin.getLogger().warning("Language file " + file.getName() + " is an empty file!");
            } else if (ConfigUtil.checkConfigVersion(langConf, defaultConf)) {
                dataByLocale.put(
                        new Locale(localeEntry[0], localeEntry[1]), new DependentData(
                                new TitleViewEntry(langConf.getString("reconnectionTitle.title"), langConf.getString("reconnectionTitle.subTitle"), langConf.getString("reconnectionTitle.actionBar")),
                                new TitleViewEntry(langConf.getString("connectionTitle.title"), langConf.getString("connectionTitle.subTitle"), langConf.getString("connectionTitle.actionBar")),
                                new TitleViewEntry(langConf.getString("failTitle.title"), langConf.getString("failTitle.subTitle"), langConf.getString("failTitle.actionBar")),
                                ChatColor.translateAlternateColorCodes('&', langConf.getString("failKickMessage"))
                        )
                );
                dataByLocale.keySet().forEach(locale -> localeByLang.put(locale.getLanguage(), locale));
            } else {
                plugin.getLogger().warning("lang file \"" + file.getName() + "\" is of an outdated config version and will not be loaded");
            }
        }

        this.dataByLocale = dataByLocale;
        this.localeByLang = localeByLang;
    }

    static final String [] langs = {
        "en_US.yml", "ru_RU.yml", "de_DE.yml", "zh_CN.yml"
    };

    @SneakyThrows
    void loadResources() {
        if (getLocaleFolder().toFile().listFiles().length == 0) {
            for (String filename : langs) {

                File file = getLocaleFolder().resolve(filename).toFile();

                try (InputStream is = plugin.getResourceAsStream("lang/" + filename); OutputStream os = new FileOutputStream(file)) {
                    ByteStreams.copy(is, os);
                }

            }
        }
        if (!getLocaleFolder().resolve(getTag(defaultLocale) + ".yml").toFile().exists()) {
            plugin.getLogger().warning("Default language file \"" + getTag(defaultLocale) + "\" is missing. Regenerating.");
            String defaultLangFilename = getTag(defaultLocale) + ".yml";
            try (InputStream is = plugin.getResourceAsStream("lang/" + defaultLangFilename);
                 OutputStream os = new FileOutputStream(getLocaleFolder().resolve(defaultLangFilename).toFile())) {
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
        if (locale == null) {
            return getDefault();
        }
        DependentData data = dataByLocale.get(locale);
        // if we can't find the exact locale, find the nearest one by language.
        if (data == null) {
            locale = localeByLang.get(locale.getLanguage());
            if (locale != null) {
                data = dataByLocale.get(locale);
            }
        }
        if (data == null) {
            return getDefault();
        } else {
            return data;
        }
    }

    public String getTag(Locale locale) {
        return locale.getLanguage() + "_" + locale.getCountry();
    }

}
