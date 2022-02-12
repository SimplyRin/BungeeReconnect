package me.taucu.reconnect.util.provider;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import net.md_5.bungee.api.ChatColor;

@Data @ToString @NoArgsConstructor @AllArgsConstructor
public class TitleViewEntry {
        
    String title; 
    String subTitle;
    String actionBar;

    protected String translate(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
    
    public String getTitle() {
        return translate(title);
    }

    public String getActionBar() {
        return translate(actionBar);
    }

    public String getSubTitle() {
        return translate(subTitle);
    }
}