package me.taucu.reconnect.util.provider;

import lombok.Data;
import lombok.ToString;

@Data @ToString
public class DependentData {
    
    TitleViewEntry reconnectionTitle;
    TitleViewEntry connectionTitle;
    TitleViewEntry failTitle;
    String failKickMessage;

}
