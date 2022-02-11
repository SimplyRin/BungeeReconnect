package me.taucu.reconnect.util.provider;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data @ToString @NoArgsConstructor @AllArgsConstructor
public class DependentData {
    
    TitleViewEntry reconnectionTitle;
    TitleViewEntry connectionTitle;
    TitleViewEntry failTitle;
    String failKickMessage;

}
