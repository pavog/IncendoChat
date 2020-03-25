package com.github.sauilitired.incendochat;

import com.github.sauilitired.incendochat.chat.ChannelConfiguration;
import com.github.sauilitired.incendochat.chat.ChannelRegistry;
import com.github.sauilitired.incendochat.chat.ChatChannel;
import com.github.sauilitired.incendochat.chat.ChatMessage;
import com.github.sauilitired.incendochat.players.BukkitChatPlayer;
import com.github.sauilitired.incendochat.players.ChatPlayer;
import com.google.common.base.Preconditions;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.text.TextComponent;
import net.kyori.text.event.ClickEvent;
import net.kyori.text.event.HoverEvent;
import net.kyori.text.serializer.legacy.LegacyComponentSerializer;
import org.apache.commons.lang3.StringUtils;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

public class ChatHandler {

    private static final Pattern
        STRIP_COLOR_PATTERN = Pattern.compile("(?i)&[0-9A-FK-OR]");

    private static String stripColor(@NotNull final String input) {
        return STRIP_COLOR_PATTERN.matcher(input).replaceAll("");
    }

    public void handleMessage(@Nullable final ChatChannel forcedChannel,
        @NotNull final ChatPlayer player, @NotNull final String text) {
        Preconditions.checkNotNull(player);
        Preconditions.checkNotNull(text);
        Runnable messageTask = () -> {
            final ChatChannel chatChannel;
            if (forcedChannel == null) {
                // Update all channels
                player.updateChannels();
                // Check if the player has a configured active channel
                final ChatChannel activeChannel = player.getActiveChannel();
                if (activeChannel != null && !activeChannel.isValid(player)) {
                    chatChannel = activeChannel;
                } else {
                    // If the player does not have an active channel, select the highest
                    // priority active channel
                    final List<ChatChannel> channels = new ArrayList<>(player.getActiveChannels());
                    if (channels.isEmpty()) {
                        // Use the global channel
                        chatChannel = ChannelRegistry.registry.getGlobalChatChannel();
                    } else {
                        channels.sort(Comparator.<ChatChannel>comparingInt(
                            channel -> channel.getChannelConfiguration().getPriority()).reversed());
                        chatChannel = channels.get(0);
                        // Update the active channel
                        player.setActiveChannel(chatChannel);
                    }
                }
            } else {
                chatChannel = forcedChannel;
            }
            // Verify that the channel is valid
            if (!chatChannel.isValid(player)) {
                return;
            }
            final Collection<ChatPlayer> receivers = chatChannel.getSubscribers();
            for (final ChatPlayer receiver : receivers) {
                // Go through all message parts and compile them
                final var builder = TextComponent.builder();
                for (final ChannelConfiguration.ChannelFormatSection channelFormatSection :
                    chatChannel.getChannelConfiguration().getChannelFormatSections()) {
                    if (!channelFormatSection.getPermission().isEmpty() &&
                        !player.hasPermission(channelFormatSection.getPermission())) {
                        continue;
                    }
                    final String textFormat = this.handleText(chatChannel, player, channelFormatSection.getText());
                    String messageText = stripColor(text);
                    if (chatChannel.getChannelConfiguration().getPingFormat() != null && text.contains(String.format("@%s", player))) {
                        messageText = StringUtils.replaceIgnoreCase(messageText, "@" + receiver.getName(),
                            ChatColor.translateAlternateColorCodes('&',
                                chatChannel.getChannelConfiguration().getPingFormat().replace("%name%", receiver.getName())));
                    }
                    builder.append(LegacyComponentSerializer.INSTANCE.deserialize(textFormat.replace("%message%", messageText), '&'));
                    if (channelFormatSection.getHoverText() != null && !channelFormatSection.getHoverText().isEmpty()) {
                        builder.hoverEvent(HoverEvent.of(HoverEvent.Action.SHOW_TEXT, LegacyComponentSerializer
                            .INSTANCE.deserialize(handleText(chatChannel, player, channelFormatSection.getHoverText()), '&')));
                    }
                    if (channelFormatSection.getClickText() != null && channelFormatSection.getClickAction() != null &&
                        !channelFormatSection.getClickText().isEmpty()) {
                        builder.clickEvent(ClickEvent.of(channelFormatSection.getClickAction(), channelFormatSection.getHoverText()));
                    }
                }
                receiver.sendMessage(new ChatMessage(chatChannel, player, builder.build()));
            }
        };
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTaskAsynchronously(IncendoChat
                .getPlugin(IncendoChat.class), messageTask);
        } else {
            messageTask.run();
        }
    }

    @NotNull private String handleText(@NotNull final ChatChannel channel,
        @NotNull final ChatPlayer sender, @NotNull final String format) {
        Preconditions.checkNotNull(channel);
        Preconditions.checkNotNull(sender);
        Preconditions.checkNotNull(format);
        final Player player;
        if (sender instanceof BukkitChatPlayer) {
            player = ((BukkitChatPlayer) sender).getBukkitPlayer();
        } else {
            player = null;
        }
        return PlaceholderAPI.setPlaceholders(player, format).replace("%channel%",
            channel.getChannelConfiguration().getDisplayName()).replace("%channel_id%",
            channel.getKey().toLowerCase());
    }

}
