import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BurnageAnalyticsReportGenerator {

    private static final String API_BASE = "https://app.veo.co/api/app";
    private static final String BASE_URL = "https://app.veo.co";
    private static final Pattern MATCH_URL_PATTERN = Pattern.compile("\"url\"\\s*:\\s*\"(/matches/[^\"]+)\"");
    private static final Pattern MATCH_TITLE_PATTERN = Pattern.compile("\"title\"\\s*:\\s*\"([^\"]+)\"");
    private static final String OUTPUT_FILE = "burnage_analytics_report.html";

    private final HttpClient httpClient;
    private final TemplateEngine templateEngine;
    private final LeagueTableScraper leagueTableScraper;
    private final List<MatchRecord> allMatches = new ArrayList<>();
    private final List<MatchRecord> firstTeamMatches = new ArrayList<>();
    private LeagueTableScraper.LeagueData leagueData;

    public static void main(String[] args) {
        BurnageAnalyticsReportGenerator generator = new BurnageAnalyticsReportGenerator();
        generator.run();
    }

    public BurnageAnalyticsReportGenerator() {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.templateEngine = createTemplateEngine();
        this.leagueTableScraper = new LeagueTableScraper();
    }

    private TemplateEngine createTemplateEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");

        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    private void run() {
        System.out.println("========================================");
        System.out.println("BURNAGE ANALYTICS REPORT GENERATOR");
        System.out.println("========================================\n");

        leagueData = leagueTableScraper.fetchLeagueData();
        fetchMatchesFromAllClubs();
        categorizeMatches();
        sortMatchesByDate();
        generateHtmlReport();
        sendEmailReport();
    }

    private void sendEmailReport() {
        MicrosoftGraphEmailService emailService = MicrosoftGraphEmailService.fromEnvironment();
        if (emailService.isConfigured()) {
            emailService.sendReport(OUTPUT_FILE);
            return;
        }

        System.out.println("\n========================================");
        System.out.println("EMAIL NOT CONFIGURED");
        System.out.println("========================================");
        System.out.println("Set these environment variables:");
        System.out.println("  RESEND_API_KEY   - Your Resend API key");
        System.out.println("  EMAIL_RECIPIENTS - Comma-separated list of emails");
        System.out.println("========================================\n");
    }

    private void fetchMatchesFromAllClubs() {
        System.out.println("Fetching matches from " + ClubConstants.ALL_CLUBS.size() + " clubs...\n");

        for (ClubConstants.Club club : ClubConstants.ALL_CLUBS) {
            System.out.println("Club: " + club.title() + " (" + club.slug() + ")");
            fetchClubRecordings(club);
            System.out.println();
        }
    }

    private void fetchClubRecordings(ClubConstants.Club club) {
        String recordingsUrl = API_BASE + "/clubs/" + club.slug() + "/recordings/?limit=50";

        String response = makeApiRequest(recordingsUrl);
        if (response == null) {
            System.out.println("  No recordings found");
            return;
        }

        extractMatches(response, club);
        fetchAllPages(response, club);
    }

    private void fetchAllPages(String jsonResponse, ClubConstants.Club club) {
        Pattern nextPattern = Pattern.compile("\"next\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = nextPattern.matcher(jsonResponse);

        if (matcher.find()) {
            String nextUrl = matcher.group(1)
                    .replace("\\u0026", "&")
                    .replace("\\/", "/");

            String response = makeApiRequest(nextUrl);
            if (response != null) {
                extractMatches(response, club);
                fetchAllPages(response, club);
            }
        }
    }

    private void extractMatches(String json, ClubConstants.Club club) {
        Matcher urlMatcher = MATCH_URL_PATTERN.matcher(json);
        while (urlMatcher.find()) {
            String matchPath = urlMatcher.group(1);
            String fullUrl = BASE_URL + matchPath;
            if (fullUrl.endsWith("/")) {
                fullUrl = fullUrl.substring(0, fullUrl.length() - 1);
            }

            String title = extractTitleForMatch(json, matchPath);
            String date = extractDateFromUrl(fullUrl);

            MatchRecord record = new MatchRecord(fullUrl, title, club.title(), club.slug(), club.filterType(), date);
            allMatches.add(record);
            System.out.println("  Found: " + title);
        }
    }

    private String extractTitleForMatch(String json, String matchPath) {
        int matchIndex = json.indexOf(matchPath);
        if (matchIndex > 0) {
            int searchStart = Math.max(0, matchIndex - 500);
            int searchEnd = Math.min(json.length(), matchIndex + 500);
            String context = json.substring(searchStart, searchEnd);

            Matcher titleMatcher = MATCH_TITLE_PATTERN.matcher(context);
            if (titleMatcher.find()) {
                return titleMatcher.group(1);
            }
        }
        return "Unknown";
    }

    private String extractDateFromUrl(String url) {
        Pattern datePattern = Pattern.compile("/matches/(\\d{8})-");
        Matcher matcher = datePattern.matcher(url);
        if (matcher.find()) {
            String dateStr = matcher.group(1);
            return dateStr.substring(0, 4) + "-" + dateStr.substring(4, 6) + "-" + dateStr.substring(6, 8);
        }
        return "0000-00-00";
    }

    private void categorizeMatches() {
        System.out.println("Categorizing matches...\n");

        for (MatchRecord match : allMatches) {
            if (isFirstTeamMatch(match)) {
                firstTeamMatches.add(match);
            }
        }

        System.out.println("Total matches: " + allMatches.size());
        System.out.println("First team matches: " + firstTeamMatches.size());
    }

    private boolean isFirstTeamMatch(MatchRecord match) {
        String title = match.title().toLowerCase();
        String url = match.url().toLowerCase();

        if (isExcludedMatch(title, url)) {
            return false;
        }

        return true;
    }

    private boolean isExcludedMatch(String title, String url) {
        String combined = (title + " " + url).toLowerCase();

        if (combined.contains("ladies") || combined.contains("women") ||
            combined.contains("female") || combined.contains("girls") ||
            combined.contains("lady") || combined.contains("womens") ||
            combined.contains("women's")) {
            return true;
        }

        if (combined.matches(".*\\bu\\d{1,2}s?\\b.*") ||
            combined.matches(".*\\bunder\\s*\\d{1,2}s?\\b.*") ||
            combined.matches(".*\\bu-\\d{1,2}\\b.*") ||
            combined.matches(".*\\bunder-\\d{1,2}\\b.*") ||
            combined.matches(".*\\bu\\d{1,2}'s\\b.*") ||
            combined.matches(".*u\\d{1,2}.*") ||
            combined.matches(".*under\\s?\\d{1,2}.*") ||
            combined.contains("under 8") || combined.contains("under 9") ||
            combined.contains("under 10") || combined.contains("under 11") ||
            combined.contains("under 12") || combined.contains("under 13") ||
            combined.contains("under 14") || combined.contains("under 15") ||
            combined.contains("under 16") || combined.contains("under 17") ||
            combined.contains("under 18") || combined.contains("under 19")) {
            return true;
        }

        if (combined.contains("colts") || combined.contains("colt") ||
            combined.contains("junior") || combined.contains("juniors") ||
            combined.contains("youth") || combined.contains("mini") ||
            combined.contains("minis") || combined.contains("cubs") ||
            combined.contains("academy") || combined.contains("age grade") ||
            combined.contains("age-grade")) {
            return true;
        }

        if (combined.contains("training") || combined.contains("fire vs") ||
            combined.contains("warm up") || combined.contains("warmup") ||
            combined.contains("practice") || combined.contains("drill") ||
            combined.contains("session")) {
            return true;
        }

        if (combined.contains("ljmu")) {
            return true;
        }

        if (combined.contains("transition") || combined.contains("development") ||
            combined.contains("dev ") || combined.contains(" dev")) {
            return true;
        }

        if (combined.contains("untitled recording") || combined.contains("untitled")) {
            return true;
        }

        return false;
    }

    private void sortMatchesByDate() {
        firstTeamMatches.sort((a, b) -> b.date().compareTo(a.date()));
        allMatches.sort((a, b) -> b.date().compareTo(a.date()));
    }

    private void generateHtmlReport() {
        System.out.println("\nGenerating HTML report...");

        List<ClubReportData> clubDataList = buildClubReportData();

        String nextOpponent = getNextOpponent();
        ClubReportData nextOpponentClub = null;
        ClubReportData burnageClub = null;
        List<ClubReportData> otherClubs = new ArrayList<>();

        for (ClubReportData club : clubDataList) {
            if (club.name().toLowerCase().contains("burnage")) {
                burnageClub = club;
            } else if (nextOpponent != null && clubMatchesOpponent(club.name(), nextOpponent)) {
                nextOpponentClub = club;
            } else {
                otherClubs.add(club);
            }
        }

        Context context = new Context();
        context.setVariable("generatedDate", LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
        context.setVariable("totalClubs", ClubConstants.ALL_CLUBS.size());
        context.setVariable("totalFirstTeamMatches", firstTeamMatches.size());
        context.setVariable("totalAllMatches", allMatches.size());
        context.setVariable("clubs", clubDataList);
        context.setVariable("leagueData", leagueData);
        context.setVariable("leagueTable", new LeagueTableScraper.LeagueTableData(leagueData.leagueName(), leagueData.tableEntries()));
        context.setVariable("upcomingFixtures", leagueData.getUpcomingFixtures());
        context.setVariable("recentResults", leagueData.getResults());
        context.setVariable("homeFixtures", leagueData.getHomeFixtures());
        context.setVariable("awayFixtures", leagueData.getAwayFixtures());

        context.setVariable("nextOpponent", nextOpponent);
        context.setVariable("nextOpponentClub", nextOpponentClub);
        context.setVariable("burnageClub", burnageClub);
        context.setVariable("otherClubs", otherClubs);

        List<MatchData> recentOpponentMatches = new ArrayList<>();
        if (nextOpponent != null && nextOpponentClub == null) {
            recentOpponentMatches = findMatchesAgainstOpponent(nextOpponent);
        }
        context.setVariable("recentOpponentMatches", recentOpponentMatches);
        context.setVariable("hasNoVeoForOpponent", nextOpponent != null && nextOpponentClub == null);

        LeagueTableScraper.Fixture nextFixture = getNextFixture();
        context.setVariable("nextFixture", nextFixture);

        String htmlOutput = templateEngine.process("burnage_analytics_report", context);

        writeHtmlToFile(htmlOutput);
    }

    private String getNextOpponent() {
        List<LeagueTableScraper.Fixture> upcoming = leagueData.getUpcomingFixtures();
        if (upcoming != null && !upcoming.isEmpty()) {
            return upcoming.get(0).getOpponent();
        }
        return null;
    }

    private LeagueTableScraper.Fixture getNextFixture() {
        List<LeagueTableScraper.Fixture> upcoming = leagueData.getUpcomingFixtures();
        if (upcoming != null && !upcoming.isEmpty()) {
            return upcoming.get(0);
        }
        return null;
    }

    private boolean clubMatchesOpponent(String clubName, String opponent) {
        String clubLower = clubName.toLowerCase().trim();
        String opponentLower = opponent.toLowerCase().trim();

        String clubNormalized = normalizeTeamName(clubLower);
        String opponentNormalized = normalizeTeamName(opponentLower);

        if (clubNormalized.equals(opponentNormalized)) {
            return true;
        }

        if (clubNormalized.contains(opponentNormalized) || opponentNormalized.contains(clubNormalized)) {
            if (clubNormalized.length() > 5 && opponentNormalized.length() > 5) {
                return true;
            }
        }

        String[] clubKeyWords = getKeyWords(clubLower);
        String[] opponentKeyWords = getKeyWords(opponentLower);

        for (String clubWord : clubKeyWords) {
            for (String opWord : opponentKeyWords) {
                if (clubWord.equals(opWord) && clubWord.length() > 4) {
                    return true;
                }
            }
        }

        return false;
    }

    private String normalizeTeamName(String name) {
        return name.replaceAll("\\s*(rufc|rfc|rugby|club|rugby club|fc)\\s*", " ")
                   .replaceAll("\\s+", " ")
                   .trim();
    }

    private String[] getKeyWords(String name) {
        String[] commonWords = {"rufc", "rfc", "rugby", "club", "fc", "park", "vale", "of", "the", "st", "helens"};
        String[] words = name.split("\\s+");
        List<String> keyWords = new ArrayList<>();
        for (String word : words) {
            boolean isCommon = false;
            for (String common : commonWords) {
                if (word.equals(common)) {
                    isCommon = true;
                    break;
                }
            }
            if (!isCommon && word.length() > 2) {
                keyWords.add(word);
            }
        }
        return keyWords.toArray(new String[0]);
    }

    private List<MatchData> findMatchesAgainstOpponent(String opponent) {
        List<MatchData> matchesAgainstOpponent = new ArrayList<>();
        String opponentLower = opponent.toLowerCase();
        String[] opponentKeyWords = getKeyWords(opponentLower);

        for (MatchRecord match : firstTeamMatches) {
            String titleLower = match.title().toLowerCase();

            for (String keyword : opponentKeyWords) {
                if (keyword.length() > 3 && titleLower.contains(keyword)) {
                    matchesAgainstOpponent.add(new MatchData(match.date(), match.title() + " (" + match.clubName() + ")", match.url()));
                    break;
                }
            }
        }

        matchesAgainstOpponent.sort((a, b) -> b.date().compareTo(a.date()));

        if (matchesAgainstOpponent.size() > 5) {
            return matchesAgainstOpponent.subList(0, 5);
        }
        return matchesAgainstOpponent;
    }

    private List<ClubReportData> buildClubReportData() {
        List<ClubReportData> clubDataList = new ArrayList<>();

        for (ClubConstants.Club club : ClubConstants.ALL_CLUBS) {
            List<MatchRecord> clubMatches = getMatchesForClub(club.title());

            List<MatchData> firstTeamMatchDataList = new ArrayList<>();
            List<MatchData> otherMatchDataList = new ArrayList<>();

            for (MatchRecord match : clubMatches) {
                MatchData matchData = new MatchData(match.date(), match.title(), match.url());
                if (isFirstTeamIndicator(match.title(), match.url())) {
                    firstTeamMatchDataList.add(matchData);
                } else {
                    otherMatchDataList.add(matchData);
                }
            }

            String filterLabel = "Senior Matches";

            int totalCount = firstTeamMatchDataList.size() + otherMatchDataList.size();

            clubDataList.add(new ClubReportData(
                club.title(),
                filterLabel,
                totalCount,
                firstTeamMatchDataList,
                otherMatchDataList
            ));
        }

        return clubDataList;
    }

    private boolean isFirstTeamIndicator(String title, String url) {
        String combined = (title + " " + url).toLowerCase();
        return combined.contains("1st") ||
               combined.contains("1xv") ||
               combined.contains("first") ||
               combined.contains("1 xv") ||
               combined.contains("1s vs") ||
               combined.contains("1s v ") ||
               combined.contains("-1s-") ||
               combined.contains(" 1s ") ||
               combined.matches(".*\\b1st\\s*(xv|team|fifteen)\\b.*") ||
               combined.matches(".*\\bfirsts?\\b.*");
    }

    private List<MatchRecord> getMatchesForClub(String clubName) {
        List<MatchRecord> clubMatches = new ArrayList<>();
        for (MatchRecord match : firstTeamMatches) {
            if (match.clubName().equals(clubName)) {
                clubMatches.add(match);
            }
        }
        clubMatches.sort((a, b) -> b.date().compareTo(a.date()));
        return clubMatches;
    }

    private void writeHtmlToFile(String htmlContent) {
        try {
            Files.writeString(Path.of(OUTPUT_FILE), htmlContent);
            System.out.println("Report saved to " + OUTPUT_FILE);
        } catch (Exception e) {
            System.out.println("Failed to write report: " + e.getMessage());
        }
    }

    private String makeApiRequest(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15")
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("Veo-App-Id", "hazard")
                    .header("veo-agent", "veo:svc:web-app")
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            }

            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public record MatchRecord(String url, String title, String clubName, String clubSlug, String filterType, String date) {}

    public record MatchData(String date, String title, String url) {}

    public record ClubReportData(String name, String filterType, int matchCount, List<MatchData> firstTeamMatches, List<MatchData> otherMatches) {
        public List<MatchData> getAllMatches() {
            List<MatchData> all = new ArrayList<>();
            all.addAll(firstTeamMatches);
            all.addAll(otherMatches);
            return all;
        }
    }
}

