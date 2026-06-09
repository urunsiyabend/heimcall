package com.urunsiyabend.heimcall.notification.sender;

import com.urunsiyabend.heimcall.notification.domain.NotificationChannel;
import com.urunsiyabend.heimcall.notification.domain.NotificationDelivery;
import com.urunsiyabend.heimcall.notification.domain.NotificationRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** Sends notifications as plain-text email over SMTP (mailhog locally). */
@Component
public class EmailSender implements NotificationSender {

    private final JavaMailSender mailSender;
    private final String from;

    public EmailSender(JavaMailSender mailSender,
                       @Value("${notification.email.from:heimcall@localhost}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public void send(NotificationDelivery delivery, NotificationRequest request) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(delivery.getDestination());
        message.setSubject(subject(request));
        message.setText(body(request));
        mailSender.send(message);
    }

    private String subject(NotificationRequest r) {
        String sev = r.getSeverity() != null ? r.getSeverity().name() : "INCIDENT";
        String title = r.getTitle() != null ? r.getTitle() : "Incident " + r.getIncidentId();
        return "[" + sev + "] " + title;
    }

    private String body(NotificationRequest r) {
        return "Heimcall incident notification (escalation level " + r.getLevel() + ").\n\n"
                + "Incident: " + r.getIncidentId() + "\n"
                + "Title: " + (r.getTitle() != null ? r.getTitle() : "(none)") + "\n"
                + "Severity: " + (r.getSeverity() != null ? r.getSeverity().name() : "(none)") + "\n";
    }
}
