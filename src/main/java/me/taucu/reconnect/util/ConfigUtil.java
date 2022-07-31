package me.taucu.reconnect.util;

import com.google.common.io.ByteStreams;
import net.md_5.bungee.config.Configuration;

import java.io.*;
import java.nio.file.Files;

public class ConfigUtil {

    public static boolean checkConfigVersion(Configuration conf, Configuration defaults) {
        return conf.getInt("version", -1) == defaults.getInt("version", -1);
    }

    public static File renameOldConfig(File oldConfig) {
        try {
            String oldExtension = getExtension(oldConfig);
            for (int i = 0; i < 100; i++) {
                File dest = changeExtension(oldConfig, ".old." + i + oldExtension);
                if (!dest.isFile()) {
                    return Files.move(oldConfig.toPath(), dest.toPath()).toFile();
                }
            }
            throw new RuntimeException("Couldn't rename config (too many old configs)");
        } catch (IOException e) {
            throw new RuntimeException("IOException while renaming old config", e);
        }
    }

    public static String getExtension(File f) {
        String name = f.getName();
        return name.substring(name.lastIndexOf('.'));
    }

    public static File changeExtension(File f, String newExtension) {
        String name = f.getName();
        String strippedName = name.substring(0, name.lastIndexOf('.'));
        return new File(f.getParentFile(), strippedName + newExtension);
    }

    public static void copyInternalFile(File dest, String internalFile) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        if (!dest.createNewFile()) {
            throw new IOException("Could not create file!");
        }
        try (InputStream is = ConfigUtil.class.getClassLoader().getResourceAsStream(internalFile); OutputStream os = new FileOutputStream(dest)) {
            ByteStreams.copy(is, os);
        }
    }

}
