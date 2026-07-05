package com.notification.infrastructure.sender;

import com.notification.model.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Stand-in for the Twilio SMS client. Logs the message and returns a fake SID. */
@Component
public class SimulatedTwilioSender implements MessageSender {

    private static final Logger log = LoggerFactory.getLogger(SimulatedTwilioSender.class);

    @Override
    public Channel channel() {
        return Channel.SMS;
    }

    @Override
    public SendResult send(UUID consumerId, String renderedBody) {
        String sid = "SM" + UUID.randomUUID().toString().replace("-", "");
        log.debug("[SIM-TWILIO] -> consumer={} sid={} body={}", consumerId, sid, renderedBody);
        return SendResult.accepted(sid);
    }
}
