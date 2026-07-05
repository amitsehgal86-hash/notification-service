package com.notification.infrastructure.sender;

import com.notification.model.Channel;

import java.util.UUID;

/**
 * Abstraction over an outbound provider. Real impls would call Twilio/SendGrid; here they are
 * simulated in-process so the app is plug-and-play with no cloud accounts. Swap the impl to go live.
 */
public interface MessageSender {

    Channel channel();

    SendResult send(UUID consumerId, String renderedBody);
}
