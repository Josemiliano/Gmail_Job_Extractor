# Gmail Job Extractor - Josemiliano Cohen

Scans your Gmail for job application confirmations, extracts **Company**, **Position**, **Date**, **From**, **Subject**, and writes them to `job_applications.xlsx`. Results are ordered **from your cutoff date (`SEARCH_AFTER`) toward today**. It deduplicates by subject and uses content heuristics to avoid false positives.

## Features
- Filters using Gmail’s internal timestamp for correct date handling
- Detects job application confirmations via keyword heuristics
- Uses an LLM to extract Company and Position when needed
- Outputs a clean Excel with headers: Company, Position, Interview, Offer, Date, From, Subject

## Prerequisites
- Java 17 or newer
- Maven
- A Google Cloud OAuth client with Gmail API enabled, the downloaded `credentials.json`
- An OpenAI API key

## Getting Started
Download the ZIP project and extract it

## Getting the keys

### 1) OpenAI API key
1. Go to platform.openai.com, log in, visit API Keys
2. Create a new secret key
3. Copy it, you can set it in your shell as `OPENAI_API_KEY` //example at the bottom

### 2) Google credentials for Gmail API
(I can add the first 100 test users on my own, please don't hesitate to reach out)
(If you want it to be an independent venture, follow steps)
Goal: create an OAuth Desktop App client in Google Cloud and place the downloaded JSON at src/main/resources/credentials.json.

1. Create/select a Google Cloud project
Go to https://console.cloud.google.com
.
Top bar → click the Project selector → New Project.
Name it (e.g., Gmail Job Extractor) → Create → then Select Project when it’s ready.

2. Enable the Gmail API
Left sidebar → APIs & Services → Library.
Search Gmail API → click it → Enable.

3. Configure the OAuth consent screen (one time)
Left sidebar → APIs & Services → OAuth consent screen.
User Type: choose External → Create.
Fill App name, User support email, Developer contact info → Save and continue.

Scopes: you can leave defaults (the code requests gmail.readonly at runtime) → Save and continue.
Test users:

Go to Audience, click Add users, enter the Gmail address you will use (e.g., you@gmail.com) → Add → Save and continue → back to dashboard.

Your app can stay in Testing. Only listed Test users can authorize.
If your school/work account blocks unverified apps, use a personal Gmail.

4. Create OAuth client credentials (Desktop app)

Left sidebar → APIs & Services → Credentials.
Click Create credentials → OAuth client ID.
Application type = Desktop app → name it → Create.
Click Download JSON (this is your OAuth client file).

5. Put the JSON where the app expects it

In your project, ensure this folder exists: src/main/resources.
If not, create a blank resources folder in src/main

Copy the downloaded file there and rename it exactly to: credentials.json.
Verify (PowerShell in project root):
Test-Path .\src\main\resources\credentials.json

If False, re-check the path/filename (Windows sometimes saves as credentials.json.txt).

6) First run & consent

On first run (commands below), a browser opens: sign in with the same Gmail you added under Test users.
If you see “Google hasn’t verified this app,” click Advanced → Go to {your app name} (unsafe).
Approve the requested permission (read-only Gmail).
A local tokens folder is created; future runs won’t ask again.

7) Switching accounts or fixing 403 access_denied

If you used the wrong Google account or get Error 403: access_denied:
Make sure that Gmail address is added under Test users in the OAuth consent screen.
Delete local tokens and re-run to trigger login again:
Remove-Item -Recurse -Force .\tokens

Run the app again and choose the correct account.

8) Make sure the credentials are from the same project

If consent fails repeatedly, ensure the credentials.json you placed came from this project (the one with Gmail API enabled and the consent screen set up). If in doubt, re-download the Desktop OAuth client from APIs & Services → Credentials, replace the file, and re-run.

Do not commit credentials.json or tokens/ to Git — the provided .gitignore already prevents that.

## Configuration

### Change the date cutoff `SEARCH_AFTER`
`SEARCH_AFTER` accepts `yyyy/MM/dd`
Examples:
- `2025/03/01`

**Windows PowerShell, per session**
```powershell
$env:SEARCH_AFTER="2025/06/25"
$env:OPENAI_API_KEY="sk...YOUR OPEN_AI_KEY_HERE"
