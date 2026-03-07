import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;

public class EmailService {

    private static final String MAILERLITE_API_BASE = "https://connect.mailerlite.com/api";
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final String apiToken;
    private final String groupId;
    private final String fromEmail;

    public EmailService(String apiToken, String groupId, String fromEmail) {
        this.apiToken = apiToken;
        this.groupId = groupId;
        this.fromEmail = fromEmail;
    }

    public void sendReport(String reportFilePath) {
        if (apiToken == null || apiToken.isEmpty()) {
            System.out.println("MailerLite API token not configured. Set MAILERLITE_API_TOKEN env var.");
            return;
        }

        if (groupId == null || groupId.isEmpty()) {
            System.out.println("MailerLite Group ID not configured. Set MAILERLITE_GROUP_ID env var.");
            return;
        }

        if (fromEmail == null || fromEmail.isEmpty()) {
            System.out.println("Sender email not configured. Set MAILERLITE_FROM_EMAIL env var.");
            return;
        }

        System.out.println("\n========================================");
        System.out.println("SENDING EMAIL VIA MAILERLITE");
        System.out.println("========================================");
        System.out.println("From: " + fromEmail);

        try {
            String htmlContent = Files.readString(Path.of(reportFilePath));
            String subject = "Burnage Analytics Report - " + LocalDate.now();

            String campaignId = createCampaign(subject, htmlContent);
            if (campaignId != null) {
                boolean sent = sendCampaign(campaignId);
                if (sent) {
                    System.out.println("Email campaign sent successfully!");
                }
            }

            System.out.println("========================================\n");

        } catch (IOException e) {
            System.out.println("Failed to read report file: " + e.getMessage());
        }
    }

    private String createCampaign(String subject, String htmlContent) {
        String escapedHtml = escapeJsonString(htmlContent);
        String campaignName = "Burnage Analytics Report " + LocalDate.now();

        String jsonBody = """
            {
                "name": "%s",
                "type": "regular",
                "emails": [{
                    "subject": "%s",
                    "from": "%s",
                    "from_name": "Burnage Analytics",
                    "content": "%s"
                }],
                "groups": ["%s"]
            }
            """.formatted(campaignName, subject, fromEmail, escapedHtml, groupId);

        try {
            System.out.println("Creating campaign...");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(MAILERLITE_API_BASE + "/campaigns"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + apiToken)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                String body = response.body();
                String campaignId = extractJsonValue(body, "id");
                if (campaignId != null) {
                    System.out.println("Campaign created: " + campaignId);
                    return campaignId;
                }
            } else {
                System.out.println("Failed to create campaign: " + response.statusCode());
                System.out.println(response.body());
            }
        } catch (Exception e) {
            System.out.println("Error creating campaign: " + e.getMessage());
        }
        return null;
    }

    private boolean sendCampaign(String campaignId) {
        try {
            System.out.println("Scheduling campaign for immediate delivery...");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(MAILERLITE_API_BASE + "/campaigns/" + campaignId + "/schedule"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + apiToken)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"delivery\": \"instant\"}"))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                System.out.println("Campaign scheduled successfully");
                return true;
            } else {
                System.out.println("Failed to send campaign: " + response.statusCode());
                System.out.println(response.body());
            }
        } catch (Exception e) {
            System.out.println("Error sending campaign: " + e.getMessage());
        }
        return false;
    }

    private String extractJsonValue(String json, String key) {
        String searchKey = "\"" + key + "\":";
        int keyIndex = json.indexOf(searchKey);
        if (keyIndex == -1) return null;

        int valueStart = keyIndex + searchKey.length();
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }

        if (valueStart >= json.length()) return null;

        if (json.charAt(valueStart) == '"') {
            int valueEnd = json.indexOf('"', valueStart + 1);
            if (valueEnd > valueStart) {
                return json.substring(valueStart + 1, valueEnd);
            }
        } else {
            int valueEnd = valueStart;
            while (valueEnd < json.length() &&
                   (Character.isDigit(json.charAt(valueEnd)) || json.charAt(valueEnd) == '.')) {
                valueEnd++;
            }
            if (valueEnd > valueStart) {
                return json.substring(valueStart, valueEnd);
            }
        }
        return null;
    }

    private String escapeJsonString(String input) {
        if (input == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : input.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    public boolean isConfigured() {
        return apiToken != null && !apiToken.isEmpty()
                && groupId != null && !groupId.isEmpty();
    }

    public static EmailService fromEnvironment() {
        String apiToken = System.getenv("MAILERLITE_API_TOKEN");
        String groupId = System.getenv("MAILERLITE_GROUP_ID");
        String fromEmail = System.getenv("MAILERLITE_FROM_EMAIL");
        return new EmailService(apiToken, groupId, fromEmail);
    }
}
