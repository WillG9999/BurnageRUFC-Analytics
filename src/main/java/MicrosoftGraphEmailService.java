import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

public class MicrosoftGraphEmailService {

    private static final String RESEND_API_KEY = "re_PpobeyXE_N4ycg9UF367AhvMwMpXBS44v";
    private static final String FROM_EMAIL = "onboarding@resend.dev";

    private final List<String> recipients;

    public MicrosoftGraphEmailService(List<String> recipients) {
        this.recipients = recipients;
    }

    public void sendReport(String reportFilePath) {
        System.out.println("\n========================================");
        System.out.println("SENDING EMAIL REPORT");
        System.out.println("========================================");
        System.out.println("Recipients: " + recipients.size() + " recipient(s)");

        try {
            String htmlContent = Files.readString(Path.of(reportFilePath));
            String subject = "Burnage Analytics Report - " + LocalDate.now();

            Resend resend = new Resend(RESEND_API_KEY);

            for (String recipient : recipients) {
                CreateEmailOptions params = CreateEmailOptions.builder()
                        .from(FROM_EMAIL)
                        .to(recipient)
                        .subject(subject)
                        .html(htmlContent)
                        .build();

                CreateEmailResponse response = resend.emails().send(params);
                System.out.println("Sent to: " + recipient);
            }

            System.out.println("Email sent successfully!");
            System.out.println("========================================\n");

        } catch (ResendException e) {
            System.out.println("Failed to send email: " + e.getMessage());
            e.printStackTrace();
            System.out.println("========================================\n");
        } catch (Exception e) {
            System.out.println("Failed to send email: " + e.getMessage());
            e.printStackTrace();
            System.out.println("========================================\n");
        }
    }

    public boolean isConfigured() {
        return recipients != null && !recipients.isEmpty();
    }

    public static MicrosoftGraphEmailService fromEnvironment() {
        String recipientList = System.getenv("EMAIL_RECIPIENTS");

        List<String> recipients = List.of();
        if (recipientList != null && !recipientList.isEmpty()) {
            recipients = Arrays.stream(recipientList.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }

        return new MicrosoftGraphEmailService(recipients);
    }
}
