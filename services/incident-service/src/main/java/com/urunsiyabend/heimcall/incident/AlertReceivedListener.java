package com.urunsiyabend.heimcall.incident;

import com.urunsiyabend.heimcall.common.events.AlertReceivedEvent;
import com.urunsiyabend.heimcall.common.events.Topics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Consumes normalized alert events and drives the incident engine. */
@Component
public class AlertReceivedListener {

    private final IncidentService incidentService;

    public AlertReceivedListener(IncidentService incidentService) {
        this.incidentService = incidentService;
    }

    @KafkaListener(topics = Topics.ALERT_RECEIVED, groupId = "incident-service.alert-received-consumer")
    public void onAlertReceived(AlertReceivedEvent event) {
        incidentService.handle(event);
    }
}
