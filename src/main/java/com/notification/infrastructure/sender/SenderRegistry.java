package com.notification.infrastructure.sender;

import com.notification.model.Channel;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Routes a channel to its MessageSender impl. */
@Component
public class SenderRegistry {

    private final Map<Channel, MessageSender> byChannel = new EnumMap<>(Channel.class);

    public SenderRegistry(List<MessageSender> senders) {
        for (MessageSender s : senders) {
            byChannel.put(s.channel(), s);
        }
    }

    public MessageSender forChannel(Channel channel) {
        MessageSender s = byChannel.get(channel);
        if (s == null) {
            throw new IllegalStateException("No sender registered for channel " + channel);
        }
        return s;
    }
}
