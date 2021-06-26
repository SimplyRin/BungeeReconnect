package eu.the5zig.reconnect.util;

import java.util.ArrayList;
import java.util.List;

import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.chat.ComponentSerializer;

public abstract class CmdUtil {
    
    public static List<String> copyPartialMatches(List<String> toSearch, String what) {
        List<String> lst = new ArrayList<String>();
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
