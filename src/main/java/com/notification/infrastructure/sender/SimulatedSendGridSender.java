package com.notification.infrastructure.sender;

import com.notification.model.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Stand-in for the SendGrid email client. Logs the message and returns a fake message id. */
@Component
public class SimulatedSendGridSender implements MessageSender {

    private static final Logger log = LoggerFactory.getLogger(SimulatedSendGridSender.class);

    @Override
    public Channel channel() {
        return Channel.EMAIL;
    }

    @Override
    public SendResult send(UUID consumerId, String renderedBody) {
        String messageId = UUID.randomUUID().toString().replace("-", "") + ".sendgrid";
        log.debug("[SIM-SENDGRID] -> consumer={} msgId={} body={}", consumerId, messageId, renderedBody);
        return SendResult.accepted(messageId);
    }
}
