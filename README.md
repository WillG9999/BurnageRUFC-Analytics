# Burnage Analytics Report Generator

Automated match footage aggregator and analytics report generator for Burnage Rugby Club.

## Features

- Fetches match footage from Veo across multiple clubs in the league
- Scrapes league table and fixtures from England Rugby
- Generates HTML report with upcoming fixture research
- Sends report via email to configured recipients
- Runs automatically every Sunday at 11:00 AM via GitHub Actions

## Run Locally

```bash
mvn compile exec:java -Dexec.mainClass="BurnageAnalyticsReportGenerator"
```

## Environment Variables

| Variable | Description | Required |
|----------|-------------|----------|
| `SMTP_USERNAME` | Your Outlook email address | Yes |
| `SMTP_PASSWORD` | Your Outlook app password | Yes |
| `EMAIL_RECIPIENTS` | Comma-separated list of recipient emails | Yes |

## GitHub Actions Setup

1. Go to your repository Settings > Secrets and variables > Actions
2. Add the following repository secrets:
   - `SMTP_USERNAME` - Your Outlook email (e.g., `your-email@live.com`)
   - `SMTP_PASSWORD` - Your Outlook app password (generate at https://account.live.com/proofs/AppPassword)
   - `EMAIL_RECIPIENTS` - Comma-separated emails (e.g., `will_graham@live.com,coach@burnage.com`)

3. The workflow runs automatically every Sunday at 11:00 AM UTC
4. You can also trigger it manually from Actions > Weekly Burnage Analytics Report > Run workflow

## Outlook App Password

To generate an app password for Outlook/Hotmail:
1. Go to https://account.live.com/proofs/AppPassword
2. Sign in with your Microsoft account
3. Click "Create a new app password"
4. Copy the generated password and use it as `SMTP_PASSWORD`
