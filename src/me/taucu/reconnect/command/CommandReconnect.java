package me.taucu.reconnect.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import me.taucu.reconnect.Reconnect;
import me.taucu.reconnect.net.DownstreamInboundHandler;
import me.taucu.reconnect.util.CmdUtil;
import net.md_5.bungee.UserConnection;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.TabExecutor;

public class CommandReconnect extends Command implements TabExecutor {
    
    private final Reconnect instance;
    
    private static final BaseComponent[] help = new ComponentBuilder()
            .color(ChatColor.RED)
            .append("Usage: /reconnect")
            .append(CmdUtil.jnline)
            .append(" reload")
            .append(CmdUtil.jnline)
            .append(" test")
            .create();
    private static final BaseComponent[] cmdSubNotFound = new ComponentBuilder()
            .color(ChatColor.RED)
            .append("Subcommand not found")
            .create();
    
    private static final BaseComponent[] cmdFeedbackReloadAttempt = new ComponentBuilder()
            .color(ChatColor.GOLD)
            .append("Reloading...")
            .create();
    private static final BaseComponent[] cmdFeedbackReloadError = new ComponentBuilder()
            .color(ChatColor.RED)
            .append("Reload failed!")
            .create();
    private static final BaseComponent[] cmdFeedbackReloadComplete = new ComponentBuilder()
            .color(ChatColor.GREEN)
            .append("Reload complete.")
            .create();
    
    private static final BaseComponent[] cmdFeedbackTesting = new ComponentBuilder()
            .color(ChatColor.GREEN)
            .append("Testing reconnect")
            .create();
    
    private static final BaseComponent[] cmdFeedbackTestingConsoleError = new ComponentBuilder()
            .color(ChatColor.RED)
            .append("you must use this command in-game")
            .create();
    
    public CommandReconnect(Reconnect instance) {
        super("bungee-reconnect", "reconnect.command", new String[] { "reconnect" });
        this.instance = instance;
    }
    
    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length > 0) {
            switch (args[0].toLowerCase()) {
            case "reload":
                sender.sendMessage(cmdFeedbackReloadAttempt);
                if (instance.reload()) {
                    sender.sendMessage(cmdFeedbackReloadComplete);
                } else {
                    sender.sendMessage(cmdFeedbackReloadError);
                }
                break;
            case "test":
                if (sender instanceof UserConnection) {
                    sender.sendMessage(cmdFeedbackTesting);
                    UserConnection ucon = (UserConnection) sender;
                    ucon.getServer().getCh().close();
                } else {
                    sender.sendMessage(cmdFeedbackTestingConsoleError);
                }
                break;
            default:
                sender.sendMessage(cmdSubNotFound);
                break;
            }
        } else {
            sender.sendMessage(help);
        }
    }
    
    private static final List<String> baseComplete = Collections.unmodifiableList(Arrays.asList("reload", "test"));
    
    @Override
    public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length > 0) {
            if (args.length > 1) {
                return new ArrayList<String>();
            } else {
                return CmdUtil.copyPartialMatches(baseComplete, args[0]);
            }
        }
        return baseComplete;
    }
    
}
