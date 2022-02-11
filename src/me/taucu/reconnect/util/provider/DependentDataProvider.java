package me.taucu.reconnect.util.provider;

import java.util.HashMap;
import java.util.Map;

public class DependentDataProvider {
    
    Map<String, DependentData> dataByLocale = new HashMap<>();

    String defaultLocale;


    DependentDataProvider load() {
        return null;
    }

    DependentData getForLocale(String locale) {
        DependentData data = dataByLocale.get(locale);
        if (data == null) {
            return dataByLocale.get(defaultLocale);
        } else {
            return data;
        }
    }
}
