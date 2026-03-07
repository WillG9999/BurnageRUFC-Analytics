import io.github.bonigarcia.wdm.WebDriverManager;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.chromium.ChromiumDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class LeagueTableScraper {

    private static final String LEAGUE_TABLE_URL = "https://www.englandrugby.com/fixtures-and-results/search-results?competition=1623&division=66490&season=2025-2026";
    private static final String BURNAGE_TEAM_ID = "3684";

    public LeagueData fetchLeagueData() {
        System.out.println("Fetching league data from England Rugby using Selenium...");

        WebDriverManager.chromedriver().setup();

        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new");
        options.addArguments("--disable-blink-features=AutomationControlled");
        options.addArguments("--disable-infobars");
        options.addArguments("--disable-extensions");
        options.addArguments("--disable-gpu");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--window-size=1920,1080");
        options.addArguments("--user-agent=Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        options.setExperimentalOption("excludeSwitches", List.of("enable-automation"));
        options.setExperimentalOption("useAutomationExtension", false);

        ChromeDriver driver = null;
        try {
            driver = new ChromeDriver(options);

            try {
                driver.executeCdpCommand("Page.addScriptToEvaluateOnNewDocument",
                    Map.of("source", "Object.defineProperty(navigator, 'webdriver', {get: () => undefined})"));
            } catch (Exception cdpError) {
                System.out.println("CDP command not available, continuing without it");
            }

            System.out.println("Navigating to England Rugby...");
            driver.get(LEAGUE_TABLE_URL);

            System.out.println("Waiting for WAF challenge to complete...");
            Thread.sleep(10000);

            System.out.println("Waiting for page content to load...");
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(60));

            try {
                wait.until(ExpectedConditions.presenceOfElementLocated(By.id("league-table")));
            } catch (Exception e) {
                System.out.println("league-table not found, trying alternative selectors...");
                try {
                    wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(".resultWrapper")));
                } catch (Exception e2) {
                    System.out.println("No fixture data found either, page may be blocked");
                    String pageSource = driver.getPageSource();
                    if (pageSource.contains("awsWafCookieDomainList") || pageSource.contains("challenge.js")) {
                        System.out.println("WAF challenge detected - page is still blocked");
                    }
                }
            }

            Thread.sleep(3000);

            String html = driver.getPageSource();
            Document document = Jsoup.parse(html);

            String leagueName = extractLeagueName(document);
            List<LeagueTableEntry> tableEntries = parseLeagueTable(document);
            List<Fixture> burnageFixtures = parseBurnageFixtures(document);

            System.out.println("Found " + tableEntries.size() + " teams in " + leagueName);
            System.out.println("Found " + burnageFixtures.size() + " Burnage fixtures");

            return new LeagueData(leagueName, tableEntries, burnageFixtures);

        } catch (Exception e) {
            System.out.println("Failed to fetch league data: " + e.getMessage());
            e.printStackTrace();
            return createEmptyLeagueData();
        } finally {
            if (driver != null) {
                driver.quit();
            }
        }
    }

    public LeagueTableData fetchLeagueTable() {
        LeagueData data = fetchLeagueData();
        return new LeagueTableData(data.leagueName(), data.tableEntries());
    }

    private String extractLeagueName(Document document) {
        Element leagueNameElement = document.selectFirst("#league-table .league-name");
        if (leagueNameElement != null) {
            return leagueNameElement.text().trim();
        }
        return "Regional 2 North West";
    }

    private List<LeagueTableEntry> parseLeagueTable(Document document) {
        List<LeagueTableEntry> entries = new ArrayList<>();

        Element leagueTable = document.selectFirst("#league-table");
        if (leagueTable == null) {
            System.out.println("League table element not found");
            return entries;
        }

        Element table = leagueTable.selectFirst("table");
        if (table == null) {
            System.out.println("No table found inside league-table");
            return entries;
        }

        Element tbody = table.selectFirst("tbody");
        if (tbody == null) {
            System.out.println("No tbody found");
            return entries;
        }

        Elements rows = tbody.select("tr");
        System.out.println("Found " + rows.size() + " rows in league table");

        for (Element row : rows) {
            LeagueTableEntry entry = parseTableRow(row);
            if (entry != null) {
                entries.add(entry);
            }
        }

        return entries;
    }

    private List<Fixture> parseBurnageFixtures(Document document) {
        List<Fixture> fixtures = new ArrayList<>();
        Set<String> seenFixtures = new HashSet<>();

        Elements resultWrappers = document.select(".resultWrapper");

        for (Element wrapper : resultWrappers) {
            Element dateElement = wrapper.selectFirst(".coh-style-card-left-date");
            String dateStr = dateElement != null ? dateElement.text().trim() : "";

            Elements fixtureCards = wrapper.select(".coh-style-card-body.dataContainer");

            for (Element card : fixtureCards) {
                Fixture fixture = parseFixtureCard(card, dateStr);
                if (fixture != null && fixture.involvesBurnage()) {
                    String fixtureKey = fixture.date() + "-" + fixture.homeTeam() + "-" + fixture.awayTeam();
                    if (!seenFixtures.contains(fixtureKey)) {
                        seenFixtures.add(fixtureKey);
                        fixtures.add(fixture);
                    }
                }
            }
        }

        return fixtures;
    }

    private Fixture parseFixtureCard(Element card, String dateStr) {
        try {
            Element homeTeamLink = card.selectFirst(".coh-style-hometeam a.c065-link");
            Element awayTeamLink = card.selectFirst(".coh-style-away-team a.c065-link");

            if (homeTeamLink == null || awayTeamLink == null) {
                return null;
            }

            String homeTeam = homeTeamLink.text().trim();
            String awayTeam = awayTeamLink.text().trim();

            String homeTeamId = extractTeamId(homeTeamLink.attr("href"));
            String awayTeamId = extractTeamId(awayTeamLink.attr("href"));

            Elements scoreElements = card.select(".fnr-scores a");
            String homeScore = "";
            String awayScore = "";
            boolean isResult = false;

            if (scoreElements.size() >= 2) {
                homeScore = scoreElements.get(0).text().trim();
                awayScore = scoreElements.get(1).text().trim();
                isResult = !homeScore.isEmpty() && !awayScore.isEmpty() &&
                           !homeScore.equals("-") && !awayScore.equals("-") &&
                           homeScore.matches("\\d+") && awayScore.matches("\\d+");
            }

            boolean isHomeGame = BURNAGE_TEAM_ID.equals(homeTeamId);

            return new Fixture(
                dateStr, homeTeam, awayTeam, homeTeamId, awayTeamId,
                homeScore, awayScore, isResult, isHomeGame
            );

        } catch (Exception e) {
            return null;
        }
    }

    private String extractTeamId(String href) {
        if (href == null || href.isEmpty()) {
            return "";
        }
        int idx = href.indexOf("team=");
        if (idx >= 0) {
            String teamPart = href.substring(idx + 5);
            int ampIdx = teamPart.indexOf("&");
            if (ampIdx > 0) {
                return teamPart.substring(0, ampIdx);
            }
            return teamPart;
        }
        return "";
    }

    private LeagueTableEntry parseTableRow(Element row) {
        try {
            Elements cells = row.select("td");
            if (cells.size() < 6) {
                return null;
            }

            int position = parseIntSafe(cells.get(0).text().trim());

            Element teamLink = cells.get(1).selectFirst("a.c074-link");
            String teamName = teamLink != null ? teamLink.text().trim() : cells.get(1).text().trim();

            if (teamName.isEmpty()) {
                return null;
            }

            int played = parseIntSafe(cells.get(2).text().trim());
            int won = parseIntSafe(cells.get(3).text().trim());
            int drawn = parseIntSafe(cells.get(4).text().trim());
            int lost = parseIntSafe(cells.get(5).text().trim());

            int pointsFor = 0;
            int pointsAgainst = 0;
            int pointsDiff = 0;
            int tryBonus = 0;
            int lossBonus = 0;
            int points = 0;

            if (cells.size() >= 12) {
                pointsFor = parseIntSafe(cells.get(6).text().trim());
                pointsAgainst = parseIntSafe(cells.get(7).text().trim());
                pointsDiff = parseIntSafe(cells.get(8).text().trim());
                tryBonus = parseIntSafe(cells.get(9).text().trim());
                lossBonus = parseIntSafe(cells.get(10).text().trim());
                points = parseIntSafe(cells.get(11).text().trim());
            } else if (cells.size() >= 7) {
                points = parseIntSafe(cells.get(cells.size() - 1).text().trim());
            }

            return new LeagueTableEntry(
                position, teamName, played, won, drawn, lost,
                pointsFor, pointsAgainst, pointsDiff, tryBonus, lossBonus, points
            );
        } catch (Exception e) {
            return null;
        }
    }

    private int parseIntSafe(String text) {
        try {
            String cleaned = text.replaceAll("[^\\d-]", "").trim();
            return cleaned.isEmpty() ? 0 : Integer.parseInt(cleaned);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private LeagueData createEmptyLeagueData() {
        return new LeagueData("Regional 2 North West", new ArrayList<>(), new ArrayList<>());
    }

    public record LeagueTableEntry(
        int position, String teamName, int played, int won, int drawn, int lost,
        int pointsFor, int pointsAgainst, int pointsDiff, int tryBonus, int lossBonus, int points
    ) {
        public boolean isBurnage() {
            return teamName.toLowerCase().contains("burnage");
        }
    }

    public record LeagueTableData(String leagueName, List<LeagueTableEntry> entries) {
        public boolean hasData() {
            return entries != null && !entries.isEmpty();
        }
    }

    public record Fixture(
        String date, String homeTeam, String awayTeam,
        String homeTeamId, String awayTeamId,
        String homeScore, String awayScore,
        boolean isResult, boolean isHomeGame
    ) {
        public boolean involvesBurnage() {
            return "3684".equals(homeTeamId) || "3684".equals(awayTeamId);
        }

        public String getOpponent() {
            return isHomeGame ? awayTeam : homeTeam;
        }

        public String getVenue() {
            return isHomeGame ? "Home" : "Away";
        }

        public String getResultDisplay() {
            if (!isResult) {
                return "TBD";
            }
            int home = Integer.parseInt(homeScore);
            int away = Integer.parseInt(awayScore);
            if (isHomeGame) {
                if (home > away) return "W " + homeScore + "-" + awayScore;
                if (home < away) return "L " + homeScore + "-" + awayScore;
                return "D " + homeScore + "-" + awayScore;
            } else {
                if (away > home) return "W " + awayScore + "-" + homeScore;
                if (away < home) return "L " + awayScore + "-" + homeScore;
                return "D " + awayScore + "-" + homeScore;
            }
        }
    }

    public record LeagueData(
        String leagueName,
        List<LeagueTableEntry> tableEntries,
        List<Fixture> burnageFixtures
    ) {
        public List<Fixture> getUpcomingFixtures() {
            return burnageFixtures.stream()
                .filter(f -> !f.isResult())
                .toList();
        }

        public List<Fixture> getResults() {
            return burnageFixtures.stream()
                .filter(Fixture::isResult)
                .toList();
        }

        public List<Fixture> getHomeFixtures() {
            return burnageFixtures.stream()
                .filter(Fixture::isHomeGame)
                .toList();
        }

        public List<Fixture> getAwayFixtures() {
            return burnageFixtures.stream()
                .filter(f -> !f.isHomeGame())
                .toList();
        }
    }
}
