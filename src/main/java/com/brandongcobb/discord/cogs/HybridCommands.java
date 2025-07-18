//
//  is.swift
//  
//
//  Created by Brandon Cobb on 7/5/25.
//


/*  HybridCommands.java The purpose of this class is to be a cog
 *  with both slash and text commands on Discord
 *
 *  Copyright (C) 2025  github.com/brandongrahamcobb
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.brandongcobb.discord.cogs;

import com.brandongcobb.discord.Application;
import com.brandongcobb.discord.component.bot.DiscordBot;
import com.brandongcobb.discord.registry.ModelRegistry;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageHistory;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class HybridCommands extends ListenerAdapter implements Cog {

    private JDA api;
    private DiscordBot bot;
    private ModelRegistry registry = new ModelRegistry();
    
    @Override
    public void register (JDA api, DiscordBot bot) {
        this.api = api;
        this.bot = bot.completeGetBot().join();
        api.addEventListener(this);
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) {
            return;
        }
        String messageContent = event.getMessage().getContentRaw().trim();
        if (!messageContent.startsWith(".")) return;
        String[] args = messageContent.substring(".".length()).split("\\s+");
        if (args.length == 0) return;
        String command = args[0].toLowerCase();
        if (command.equals("wipe")) {
            boolean wipeAll = false, wipeBot = false, wipeCommands = false;
            String targetUserId = null;
            for (int i = 1; i < args.length; i++) {
                String arg = args[i].toLowerCase();
                switch (arg) {
                    case "all" -> wipeAll = true;
                    case "bot" -> wipeBot = true;
                    case "commands" -> wipeCommands = true;
                    case "user" -> {
                        if (i + 1 < args.length) {
                            String userMention = args[i + 1];
                            User user = parseUserFromMention(userMention, event.getGuild());
                            if (user != null) targetUserId = user.getId();
                            i++;
                        }
                    }
                }
            }
            TextChannel channel = (TextChannel) event.getChannel();
            String guildId = event.getGuild().getId();
            String channelId = channel.getId();
            wipeMessages(guildId, channelId, wipeAll, wipeBot, wipeCommands, targetUserId)
              .thenRun(() -> channel.sendMessage("Message wipe completed.").queue())
              .exceptionally(ex -> { return null; });
        }
    }

    private CompletableFuture<Void> deleteMessagesInChunks(List<Message> messages, TextChannel channel) {
        if (messages.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        List<Message> chunk = messages.stream().limit(100).collect(Collectors.toList());
        List<String> messageIds = chunk.stream().map(Message::getId).collect(Collectors.toList());
        CompletableFuture<Void> deletionFuture = new CompletableFuture<>();
        channel.deleteMessagesByIds(messageIds).queue(
            success -> deletionFuture.complete(null),
            failure -> deletionFuture.completeExceptionally(failure)
        );
        return deletionFuture.thenCompose(v -> {
            List<Message> remaining = messages.stream().skip(100).collect(Collectors.toList());
            return deleteMessagesInChunks(remaining, channel);
        });
    }
    
    private CompletableFuture<List<Message>> fetchAllMessages(MessageHistory history) {
        CompletableFuture<List<Message>> future = new CompletableFuture<>();
        history.retrievePast(100).queue(messages -> {
            List<Message> allMessages = messages;
            fetchRemainingMessages(history, allMessages, future);
        }, failure -> future.completeExceptionally(failure));
        return future;
    }

    private void fetchRemainingMessages(MessageHistory history, List<Message> accumulated, CompletableFuture<List<Message>> future) {
        if (history.getRetrievedHistory().size() == 0) {
            future.complete(accumulated);
        } else {
            history.retrievePast(100).queue(messages -> {
                if (messages.isEmpty()) {
                    future.complete(accumulated);
                } else {
                    accumulated.addAll(messages);
                    fetchRemainingMessages(history, accumulated, future);
                }
            }, failure -> future.completeExceptionally(failure));
        }
    }
    
    private User parseUserFromMention(String mention, Guild guild) {
        if (mention.startsWith("<@") && mention.endsWith(">")) {
            String id = mention.replaceAll("[<@!>]", "");
            return this.api.getUserById(id);
        }
        return null;
    }


    public CompletableFuture<Void> wipeMessages(
            String guildId,
            String channelId,
            boolean wipeAll,
            boolean wipeBot,
            boolean wipeCommands,
            String userId
    ) {
        Guild guild = this.api.getGuildById(guildId);
        if (guild == null) {
            return CompletableFuture.failedFuture(new Exception("Guild not found"));
        }
        TextChannel channel = guild.getTextChannelById(channelId);
        if (channel == null) {
            return CompletableFuture.failedFuture(new Exception("Channel not found"));
        }
        MessageHistory history = channel.getHistory();
        return fetchAllMessages(history).thenCompose(messages -> {
            List<Message> toDelete = messages.stream()
                    .filter(msg -> {
                        if (userId != null && !msg.getAuthor().getId().equals(userId))
                            return false;
                        if (wipeBot && !msg.getAuthor().isBot())
                            return false;
                        if (wipeCommands && msg.getContentDisplay().startsWith("."))
                            return true;
                        return wipeAll;
                    })
                    .collect(Collectors.toList());
            return deleteMessagesInChunks(toDelete, channel);
        });
    }
}
