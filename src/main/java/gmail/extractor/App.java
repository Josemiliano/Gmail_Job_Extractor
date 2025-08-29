package gmail.extractor;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class App {
    private static final String APPLICATION_NAME = "Gmail Job Extractor";
    private static final JacksonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    private static final String TOKENS_DIRECTORY_PATH = "tokens";
    private static final List<String> SCOPES = Collections.singletonList(GmailScopes.GMAIL_READONLY);
    private static final String CREDENTIALS_FILE_PATH = "/credentials.json";

    // 🔑 API key: set OPENAI_API_KEY env var or paste key below (less secure)
    private static final String OPENAI_API_KEY = System.getenv("OPENAI_API_KEY");

    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";

    // Positive signals that strongly indicate a job application confirmation
    private static final Pattern[] POSITIVE_PATTERNS = new Pattern[] {
        Pattern.compile("(?i)\\bthanks for applying\\b"),
        Pattern.compile("(?i)\\bwe( have)? received your application\\b"),
        Pattern.compile("(?i)\\byour application (for|to)\\b"),
        Pattern.compile("(?i)\\bapplication for (the )?.+\\b(position|role)\\b"),
        Pattern.compile("(?i)\\bwe('?re| are) reviewing your application\\b"),
        Pattern.compile("(?i)\\byou applied to\\b"),
        Pattern.compile("(?i)\\bfor the (.+?) (position|role)\\b")
    };

    // Negative signals to exclude common non-job "application" emails
    private static final Pattern[] NEGATIVE_PATTERNS = new Pattern[] {
        Pattern.compile("(?i)\\bcredit card\\b"),
        Pattern.compile("(?i)\\bstatement\\b"),
        Pattern.compile("(?i)\\bnewsletter\\b"),
        Pattern.compile("(?i)\\bmobile application\\b"),
        Pattern.compile("(?i)\\bapp store\\b"),
        Pattern.compile("(?i)\\bprogram application\\b"),
        Pattern.compile("(?i)\\bvisiting students\\b"),
        Pattern.compile("(?i)\\bfinancial aid\\b"),
        Pattern.compile("(?i)\\bbilling\\b")
    };

    // Parse SEARCH_AFTER from env, like 2025/03/01, and convert to epoch ms at local midnight (America/Chicago)
private static long getSearchAfterEpochMs() {
    String afterDateString = System.getenv("SEARCH_AFTER"); // e.g., 2025/03/01
    if (afterDateString == null || afterDateString.isBlank()) {
        afterDateString = "2025/03/01";
    }
    // Accept 2025/03/01 or 2025-03-01
    String norm = afterDateString.trim().replace('-', '/');
    DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    try {
        LocalDate ld = LocalDate.parse(norm, fmt);
        // User is in America/Chicago
        ZonedDateTime zdt = ld.atStartOfDay(ZoneId.of("America/Chicago"));
        return zdt.toInstant().toEpochMilli();
    } catch (DateTimeParseException e) {
        // Fallback: treat as today @ 00:00 in America/Chicago
        ZonedDateTime zdt = LocalDate.now(ZoneId.of("America/Chicago")).atStartOfDay(ZoneId.of("America/Chicago"));
        return zdt.toInstant().toEpochMilli();
    }
}

    private static boolean isLikelyJobApplication(String subject, String body) {
        String s = (subject == null ? "" : subject);
        String b = (body == null ? "" : body);
        String text = (s + "\n" + b);

        boolean hasPositive = false;
        for (Pattern p : POSITIVE_PATTERNS) {
            if (p.matcher(text).find()) { hasPositive = true; break; }
        }
        if (!hasPositive) return false;

        for (Pattern p : NEGATIVE_PATTERNS) {
            if (p.matcher(text).find()) return false;
        }
        return true;
    }

    public static void main(String[] args) throws IOException, GeneralSecurityException, InterruptedException {
    final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
    Gmail service = new Gmail.Builder(HTTP_TRANSPORT, JSON_FACTORY, getCredentials(HTTP_TRANSPORT))
            .setApplicationName(APPLICATION_NAME)
            .build();

    if (OPENAI_API_KEY == null || OPENAI_API_KEY.isBlank()) {
        throw new IllegalStateException("Missing OPENAI_API_KEY. Please set it as an environment variable.");
    }

    // ---- CONFIG: hard cutoff date (epoch ms) from SEARCH_AFTER
    final long searchAfterEpochMs = getSearchAfterEpochMs();

    // Gmail query: keep it broad, we will STILL hard-filter by internalDate and content below
    // (You can leave out "after:" entirely; it just helps shrink the result set.)
    String afterDateString = System.getenv("SEARCH_AFTER");
    if (afterDateString == null || afterDateString.isBlank()) afterDateString = "2025/03/01";

    String query = "(" +
            "\"thanks for applying\" OR " +
            "\"received your application\" OR " +
            "\"your application for\" OR " +
            "\"application for the\" OR " +
            "\"we're reviewing your application\" OR " +
            "application" +
        ") after:" + afterDateString;

    // Collect all message ids (paginate)
    List<Message> messages = new ArrayList<>();
    String pageToken = null;
    do {
        ListMessagesResponse response = service.users().messages()
                .list("me").setQ(query).setPageToken(pageToken).execute();
        if (response.getMessages() != null) messages.addAll(response.getMessages());
        pageToken = response.getNextPageToken();
    } while (pageToken != null);

    if (messages.isEmpty()) {
        System.out.println("No messages found.");
        return;
    }

    // We'll accumulate all rows, then sort by date ascending, then apply your limit.
    List<Map<String, String>> results = new ArrayList<>();
    Set<String> seenSubjects = new HashSet<>();

    // Optional safety cap while testing. Apply **after** sorting for best ordering.
    //final int limit = 15;
    //int count= 0;

    for (Message stub : messages) {
        //if (count >= limit) break;
        Message full = service.users().messages().get("me", stub.getId()).setFormat("FULL").execute();

        // Use Gmail's authoritative internalDate for time filtering
        Long internalMs = full.getInternalDate();
        if (internalMs == null) continue; // should not happen, but guard anyway
        if (internalMs < searchAfterEpochMs) continue; // HARD FILTER: respect SEARCH_AFTER

        String subject = getHeader(full, "Subject");
        String from    = getHeader(full, "From");
        String body    = getPlainTextFromMessage(full);

        // Dedup by subject
        if (subject != null && seenSubjects.contains(subject)) continue;
        if (subject != null) seenSubjects.add(subject);

        // Keep only real application confirmations
        if (!isLikelyJobApplication(subject, body)) continue;

        // AI extraction (company, position)
        Map<String, String> ai = aiExtractCompanyAndPosition(body);
        String company  = ai.getOrDefault("company", "Unknown");
        String position = ai.getOrDefault("position", "Unknown");

        Map<String, String> row = new HashMap<>();
        row.put("Company", company);
        row.put("Position", position);
        row.put("From", from == null ? "" : from);
        row.put("Subject", subject == null ? "" : subject);

        // Format date as yyyy-MM-dd from internalDate
        String formatted = new SimpleDateFormat("yyyy-MM-dd").format(new Date(internalMs));
        row.put("Date", formatted);

        // Keep internal ms for sorting (store as hidden field)
        row.put("_epochMs", String.valueOf(internalMs));

        results.add(row);
        //count++;
    }

    if (results.isEmpty()) {
        System.out.println("No matching job application confirmations after SEARCH_AFTER.");
        return;
    }

    // Sort ASCENDING by internal date so Excel starts at SEARCH_AFTER and walks forward to today
    results.sort((a, b) -> Long.compare(Long.parseLong(a.get("_epochMs")), Long.parseLong(b.get("_epochMs"))));

    // Remove helper field before writing
    for (Map<String, String> r : results) r.remove("_epochMs");

    writeToExcel(results);
    System.out.println("✅ job_applications.xlsx created, ordered from SEARCH_AFTER → today.");
}


    // ---------- Gmail helpers ----------

    private static Credential getCredentials(final NetHttpTransport HTTP_TRANSPORT) throws IOException {
        InputStream in = App.class.getResourceAsStream(CREDENTIALS_FILE_PATH);
        if (in == null) throw new FileNotFoundException("Resource not found: " + CREDENTIALS_FILE_PATH);

        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));
        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                HTTP_TRANSPORT, JSON_FACTORY, clientSecrets, SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(new java.io.File(TOKENS_DIRECTORY_PATH)))
                .setAccessType("offline")
                .build();

        LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
        return new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
    }

    private static String getHeader(Message message, String name) {
        if (message.getPayload() == null) return "";
        return message.getPayload().getHeaders().stream()
                .filter(h -> h.getName().equalsIgnoreCase(name))
                .findFirst()
                .map(h -> h.getValue())
                .orElse("");
    }

    // Prefer text/plain; if not present, try HTML and strip tags as fallback
    private static String getPlainTextFromMessage(Message message) {
        try {
            MessagePart payload = message.getPayload();
            if (payload == null) return "";

            if ("text/plain".equalsIgnoreCase(payload.getMimeType()) && payload.getBody() != null) {
                byte[] data = payload.getBody().decodeData();
                return new String(data);
            }

            String text = traverseParts(payload.getParts());
            if (!text.isEmpty()) return text;

            String html = extractFirstHtml(payload.getParts());
            if (!html.isEmpty()) return htmlToText(html);

        } catch (Exception ignored) {}
        return "";
    }

    private static String traverseParts(List<MessagePart> parts) {
        if (parts == null) return "";
        for (MessagePart part : parts) {
            if (part == null) continue;
            if ("text/plain".equalsIgnoreCase(part.getMimeType()) && part.getBody() != null) {
                byte[] data = part.getBody().decodeData();
                return new String(data);
            }
            String nested = traverseParts(part.getParts());
            if (!nested.isEmpty()) return nested;
        }
        return "";
    }

    private static String extractFirstHtml(List<MessagePart> parts) {
        if (parts == null) return "";
        for (MessagePart part : parts) {
            if (part == null) continue;
            if ("text/html".equalsIgnoreCase(part.getMimeType()) && part.getBody() != null) {
                byte[] data = part.getBody().decodeData();
                return new String(data);
            }
            String nested = extractFirstHtml(part.getParts());
            if (!nested.isEmpty()) return nested;
        }
        return "";
    }

    private static String htmlToText(String html) {
        return html.replaceAll("<[^>]+>", " ")
                   .replaceAll("&nbsp;", " ")
                   .replaceAll("\\s+", " ")
                   .trim();
    }

    // ---------- Excel ----------

// Write extracted results to Excel file (with Interview & Offer before From & Subject)
private static void writeToExcel(List<Map<String, String>> results) throws IOException {
    Workbook workbook = new XSSFWorkbook();
    Sheet sheet = workbook.createSheet("Job Confirmations");

    // Bold style for headers
    CellStyle headerStyle = workbook.createCellStyle();
    Font headerFont = workbook.createFont();
    headerFont.setBold(true);
    headerStyle.setFont(headerFont);

    // Header
    String[] headers = {"Company", "Position", "Interview", "Offer", "Date", "From", "Subject"};
    Row header = sheet.createRow(0);
    for (int i = 0; i < headers.length; i++) {
        Cell cell = header.createCell(i);
        cell.setCellValue(headers[i]);
        cell.setCellStyle(headerStyle);
    }

    // Rows
    int rowNum = 1;
    for (Map<String, String> row : results) {
        Row excelRow = sheet.createRow(rowNum++);
        excelRow.createCell(0).setCellValue(row.getOrDefault("Company", ""));
        excelRow.createCell(1).setCellValue(row.getOrDefault("Position", ""));
        excelRow.createCell(2).setCellValue(""); // Interview (manual)
        excelRow.createCell(3).setCellValue(""); // Offer (manual)
        excelRow.createCell(4).setCellValue(row.getOrDefault("Date", ""));
        excelRow.createCell(5).setCellValue(row.getOrDefault("From", ""));
        excelRow.createCell(6).setCellValue(row.getOrDefault("Subject", ""));
    }

    // Auto-size only Company, Position, and Date
    sheet.autoSizeColumn(0); // Company
    sheet.autoSizeColumn(1); // Position
    sheet.autoSizeColumn(4); // Date

    try (FileOutputStream fileOut = new FileOutputStream("job_applications.xlsx")) {
        workbook.write(fileOut);
    }
    workbook.close();
}



    // ---------- OpenAI ----------

    private static Map<String, String> aiExtractCompanyAndPosition(String emailBody) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        Gson gson = new Gson();

        JsonObject messageObj = new JsonObject();
        messageObj.addProperty("role", "user");
        messageObj.addProperty("content",
            "Extract the company name and position title from this job application confirmation email. " +
            "Respond ONLY in JSON exactly like: {\"company\":\"...\", \"position\":\"...\"}.\n\n" +
            "Email:\n" + emailBody);

        JsonArray messages = new JsonArray();
        messages.add(messageObj);

        JsonObject payload = new JsonObject();
        payload.addProperty("model", "gpt-3.5-turbo");
        payload.add("messages", messages);
        payload.addProperty("temperature", 0);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(OPENAI_URL))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + OPENAI_API_KEY)
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        System.out.println("OpenAI API raw response: " + response.body());

        Map<String, String> result = new HashMap<>();
        try {
            JsonObject responseBody = gson.fromJson(response.body(), JsonObject.class);
            if (!responseBody.has("choices")) {
                System.err.println("OpenAI API error: No 'choices' in response. Using fallback.");
                result.put("company", "Unknown");
                result.put("position", "Unknown");
                return result;
            }
            String content = responseBody.getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();

            JsonObject aiResult = gson.fromJson(content, JsonObject.class);
            result.put("company", aiResult.has("company") ? aiResult.get("company").getAsString() : "Unknown");
            result.put("position", aiResult.has("position") ? aiResult.get("position").getAsString() : "Unknown");
        } catch (Exception e) {
            System.err.println("Failed to parse OpenAI response. Using fallback. Error: " + e.getMessage());
            result.put("company", "Unknown");
            result.put("position", "Unknown");
        }
        return result;
    }
}
