# Burnage Analytics Report Generator

Automated match footage aggregator and analytics report generator for Burnage Rugby Club.

## Features

- Fetches match footage from Veo across multiple clubs in the league
- Scrapes league table and fixtures from England Rugby
- Generates HTML report with upcoming fixture research
- Sends report via MailerLite to subscribers
- Runs automatically every Sunday at 11:00 AM via GitHub Actions

## Run Locally

```bash
mvn compile exec:java -Dexec.mainClass="BurnageAnalyticsReportGenerator"
```

## Environment Variables

| Variable | Description | Required |
|----------|-------------|----------|
| `MAILERLITE_API_TOKEN` | Your MailerLite API token | Yes |
| `MAILERLITE_GROUP_ID` | The MailerLite group ID to send to | Yes |
| `MAILERLITE_FROM_EMAIL` | Verified sender email in MailerLite | Yes |

## GitHub Actions Setup

1. Go to your repository Settings > Secrets and variables > Actions
2. Add the following repository secrets:
   - `MAILERLITE_API_TOKEN` - Your MailerLite API token
   - `MAILERLITE_GROUP_ID` - The group ID for your subscribers
   - `MAILERLITE_FROM_EMAIL` - Your verified sender email address

3. The workflow runs automatically every Sunday at 11:00 AM UTC
4. You can also trigger it manually from Actions > Weekly Burnage Analytics Report > Run workflow

## MailerLite Setup

1. Go to https://app.mailerlite.com and sign in
2. Navigate to Integrations > MailerLite API
3. Click "Generate new token" and copy the token
4. Verify your sender email in Account Settings > Domains
5. Create a Group for your subscribers (Subscribers > Groups > Create group)
6. Get the Group ID from the URL when viewing the group (or via API)
7. Add subscribers to the group via the MailerLite dashboard

## Managing Subscribers

Subscribers are managed directly in MailerLite:
- Add/remove subscribers in the MailerLite dashboard
- No code changes needed to update the recipient list
- Subscribers can unsubscribe themselves via the email footer

