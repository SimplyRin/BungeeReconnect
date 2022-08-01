package me.taucu.reconnect.util;

import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.chat.ComponentSerializer;

import java.util.ArrayList;
import java.util.List;

public abstract class CmdUtil {
    
    public static List<String> copyPartialMatches(List<String> toSearch, String what) {
        List<String> lst = new ArrayList<>();
        what = what.toLowerCase();
        for (String s : toSearch) {
            if (s.toLowerCase().contains(what)) {
                lst.add(s);
            }
        }
        return lst;
    }
    
    public static final TextComponent jnline = new TextComponent(ComponentSerializer.parse("{text: \"\n\"}"));
    
}
