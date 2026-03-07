import jakarta.mail.*;
import jakarta.mail.internet.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class EmailService {

    private static final String SMTP_HOST = "smtp.office365.com";
    private static final int SMTP_PORT = 587;

    private final String username;
    private final String password;
    private final List<String> recipients;

    public EmailService(String username, String password, List<String> recipients) {
        this.username = username;
        this.password = password;
        this.recipients = recipients;
    }

    public void sendReport(String reportFilePath) {
        if (recipients == null || recipients.isEmpty()) {
            System.out.println("No email recipients configured. Set EMAIL_RECIPIENTS env var.");
            return;
        }

        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            System.out.println("Email credentials not configured. Set SMTP_USERNAME and SMTP_PASSWORD env vars.");
            return;
        }

        System.out.println("\n========================================");
        System.out.println("SENDING EMAIL REPORT");
        System.out.println("========================================");
        System.out.println("From: " + username);
        System.out.println("Recipients: " + String.join(", ", recipients));

        try {
            String htmlContent = Files.readString(Path.of(reportFilePath));

            Properties props = new Properties();
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.host", SMTP_HOST);
            props.put("mail.smtp.port", String.valueOf(SMTP_PORT));
            props.put("mail.smtp.ssl.protocols", "TLSv1.2");

            Session session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(username, password);
                }
            });

            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(username, "Burnage Analytics"));

            List<InternetAddress> validAddresses = new ArrayList<>();
            for (String email : recipients) {
                try {
                    validAddresses.add(new InternetAddress(email.trim()));
                } catch (AddressException e) {
                    System.out.println("Invalid email address skipped: " + email);
                }
            }

            if (validAddresses.isEmpty()) {
                System.out.println("No valid email addresses. Skipping email.");
                return;
            }

            message.setRecipients(Message.RecipientType.TO, validAddresses.toArray(new InternetAddress[0]));
            message.setSubject("Burnage Analytics Report - " + LocalDate.now());

            MimeMultipart multipart = new MimeMultipart("mixed");

            MimeBodyPart htmlPart = new MimeBodyPart();
            htmlPart.setContent(htmlContent, "text/html; charset=utf-8");
            multipart.addBodyPart(htmlPart);

            MimeBodyPart attachmentPart = new MimeBodyPart();
            attachmentPart.attachFile(reportFilePath);
            attachmentPart.setFileName("burnage_analytics_report.html");
            multipart.addBodyPart(attachmentPart);

            message.setContent(multipart);

            System.out.println("Sending...");
            Transport.send(message);
            System.out.println("Email sent successfully!");
            System.out.println("========================================\n");

        } catch (MessagingException e) {
            System.out.println("Failed to send email: " + e.getMessage());
        } catch (IOException e) {
            System.out.println("Failed to read report file: " + e.getMessage());
        }
    }

    public boolean isConfigured() {
        return username != null && !username.isEmpty()
                && password != null && !password.isEmpty()
                && recipients != null && !recipients.isEmpty();
    }

    public static EmailService fromEnvironment() {
        String username = System.getenv("SMTP_USERNAME");
        String password = System.getenv("SMTP_PASSWORD");
        String recipientsStr = System.getenv("EMAIL_RECIPIENTS");

        List<String> recipients = new ArrayList<>();
        if (recipientsStr != null && !recipientsStr.isEmpty()) {
            for (String email : recipientsStr.split(",")) {
                recipients.add(email.trim());
            }
        }

        return new EmailService(username, password, recipients);
    }
}

