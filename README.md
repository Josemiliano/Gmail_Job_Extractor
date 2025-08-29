# Gmail Job Extractor

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

## Getting the keys

### 1) OpenAI API key
1. Go to platform.openai.com, log in, visit API Keys
2. Create a new secret key
3. Copy it, you can set it in your shell as `OPENAI_API_KEY`

### 2) Google credentials for Gmail API
1. Go to console.cloud.google.com
2. Create a project, enable **Gmail API**
3. Create **OAuth 2.0 Client ID**, type **Desktop app**
4. Download the JSON, rename it to `credentials.json`
5. Place it at `src/main/resources/credentials.json`
6. First run will open a browser for consent, a local `tokens/` folder will be created

> Do **not** commit `credentials.json` or `tokens/` to Git, the `.gitignore` here already prevents that.

## Configuration

### Change the date cutoff `SEARCH_AFTER`
`SEARCH_AFTER` accepts `yyyy/MM/dd`
Examples:
- `2025/03/01`

**Windows PowerShell, per session**
```powershell
$env:SEARCH_AFTER="2025/06/25"
$env:OPENAI_API_KEY="sk-..."
