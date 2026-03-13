/*
 HealthAIProPlus.java
 - Single-file Java backend serving a professional health chatbot UI and /ask endpoint.
 - Integrates Google Gemini (contents->parts->text payload).
 - Local disease DB fallback, in-memory cache, simple CORS, robust error handling.
 - Requires org.json (json-20230227.jar) on classpath.

 Compile:
   javac -cp ".;json-20230227.jar" HealthAIProPlus.java
 Run:
   java -cp ".;json-20230227.jar" HealthAIProPlus
*/

import com.sun.net.httpserver.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

public class HealthAIProPlus {

    // === CONFIG ===
    private static final int PORT = 8080;
    private static final String GEMINI_API_KEY = ""; // <-- put key here
    private static final String GEMINI_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" + GEMINI_API_KEY;


    // Local DB and cache
    private static final Map<String, JSONObject> localDB = new ConcurrentHashMap<>();
    private static final Map<String, String> cache = new ConcurrentHashMap<>();
    private static final Map<String, Long> cacheTs = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 1000L * 60 * 60 * 6; // 6 hours

    public static void main(String[] args) throws Exception {
        System.out.println("Starting HealthAI Pro+ on http://localhost:" + PORT + " ...");
        initializeLocalDB();

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", new RootHandler());
        server.createContext("/ask", new AskHandler());
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();

        System.out.println("✅ HealthAI Pro+ ready. Open http://localhost:" + PORT);
    }

    // Serve index.html from working dir
    static class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String method = ex.getRequestMethod();
            if (!"GET".equalsIgnoreCase(method)) {
                sendPlain(ex, 405, "Method Not Allowed");
                return;
            }
            File f = new File("index.html");
            if (!f.exists()) {
                sendPlain(ex, 404, "index.html not found");
                return;
            }
            byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        }
    }

    // Chat endpoint
    static class AskHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            enableCORS(ex);
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
                ex.sendResponseHeaders(204, -1);
                return;
            }
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 405, new JSONObject().put("error", "Method Not Allowed"));
                return;
            }

            String body = readAll(ex.getRequestBody());
            if (body == null || body.isBlank()) {
                sendJson(ex, 400, new JSONObject().put("error", "Empty body"));
                return;
            }

            try {
                JSONObject req = new JSONObject(body);
                String message = req.optString("message", "").trim();
                if (message.isEmpty()) {
                    sendJson(ex, 400, new JSONObject().put("error", "Missing 'message'"));
                    return;
                }

                // Process message (greeting, local, cache, or Gemini)
                JSONObject resp = processQuery(message);
                sendJson(ex, 200, resp);

            } catch (JSONException je) {
                sendJson(ex, 400, new JSONObject().put("error", "Invalid JSON"));
            } catch (Exception e) {
                e.printStackTrace();
                sendJson(ex, 500, new JSONObject().put("error", "Internal server error"));
            }
        }
    }

    // Core processing
    private static JSONObject processQuery(String message) {
        JSONObject out = new JSONObject();
        out.put("timestamp", Instant.now().toString());
        out.put("query", message);

        String key = message.toLowerCase().trim();

        // Greeting
        if (isGreeting(key)) {
            out.put("structured", false);
            out.put("reply", "👋 Hello — I’m HealthAI Pro+. Ask about diseases, symptoms, prevention, and general treatments. This assistant provides informational content only.");
            out.put("source", "local");
            return out;
        }

        // Cache hit
        if (cache.containsKey(key) && (System.currentTimeMillis() - cacheTs.getOrDefault(key, 0L) < CACHE_TTL_MS)) {
            out.put("structured", true);
            out.put("reply", cache.get(key));
            out.put("source", "cache");
            return out;
        }

        // Local DB
        JSONObject local = findLocal(key);
        if (local != null) {
            StringBuilder sb = new StringBuilder();
            sb.append(capitalizeWords(local.optString("name", key))).append("\n\n");
            sb.append(local.optString("description", "")).append("\n\n");
            JSONArray sym = local.optJSONArray("symptoms");
            if (sym != null && sym.length() > 0) {
                sb.append("Symptoms: ");
                for (int i = 0; i < sym.length(); i++) {
                    sb.append(sym.optString(i));
                    if (i < sym.length() - 1) sb.append(", ");
                }
                sb.append(".\n\n");
            }
            JSONArray prev = local.optJSONArray("prevention");
            if (prev != null && prev.length() > 0) {
                sb.append("Prevention: ");
                for (int i = 0; i < prev.length(); i++) {
                    sb.append(prev.optString(i));
                    if (i < prev.length() - 1) sb.append(", ");
                }
                sb.append(".\n\n");
            }
            sb.append("⚕️ Disclaimer: This information is for educational purposes only.");
            String replyText = sb.toString();
            cache.put(key, replyText);
            cacheTs.put(key, System.currentTimeMillis());

            out.put("structured", true);
            out.put("reply", replyText);
            out.put("source", "local");
            return out;
        }

        // Call Gemini
        String ai = callGemini(message);
        if (ai != null) {
            cache.put(key, ai);
            cacheTs.put(key, System.currentTimeMillis());
            out.put("structured", true);
            out.put("reply", ai);
            out.put("source", "gemini");
            return out;
        }

        out.put("structured", false);
        out.put("reply", "Sorry, I couldn't fetch details at the moment. Try again later.");
        out.put("source", "none");
        return out;
    }

    // === Gemini call with correct contents->parts->text payload ===
    private static String callGemini(String message) {
        if (GEMINI_API_KEY == null || GEMINI_API_KEY.isBlank() || GEMINI_API_KEY.startsWith("YOUR_")) {
            System.err.println("Gemini API key missing or default—skipping external call.");
            return null;
        }

        try {
            URL url = new URL(GEMINI_ENDPOINT);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(16000);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);

            // Build payload
            String systemPrompt =
                "You are HealthAI Pro+, a concise professional medical assistant. " +
                "Answer only about medical topics: causes, symptoms, prevention, and general treatments. " +
                "Do NOT provide prescriptions or emergency instructions. " +
                "Always include a short disclaimer at the end: '⚕️ Disclaimer: This information is for educational purposes only.'";

            String userPart = "User question: " + message + "\n\nProvide a clear paragraph-style response with short paragraphs (no lists).";

            JSONObject part1 = new JSONObject().put("text", systemPrompt + "\n\n" + userPart);

            JSONArray parts = new JSONArray().put(part1);
            JSONObject contentEntry = new JSONObject().put("parts", parts);
            JSONArray contents = new JSONArray().put(contentEntry);

            JSONObject payload = new JSONObject().put("contents", contents);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) return null;
            String resp = readAll(is);

            // parse response
            JSONObject j = new JSONObject(resp);
            // typical structure: candidates[0].content.parts[0].text
            if (j.has("candidates")) {
                JSONArray c = j.getJSONArray("candidates");
                if (c.length() > 0) {
                    JSONObject first = c.getJSONObject(0);
                    if (first.has("content")) {
                        JSONObject content = first.getJSONObject("content");
                        if (content.has("parts")) {
                            JSONArray p = content.getJSONArray("parts");
                            if (p.length() > 0) {
                                return p.getJSONObject(0).optString("text", null);
                            }
                        }
                    }
                }
            }
            // fallback: try 'output' or raw
            if (j.has("output")) return j.optString("output");
            return resp;

        } catch (Exception e) {
            System.err.println("Gemini call failed: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    // === Local DB ===
    private static void initializeLocalDB() {
        addLocal("malaria",
            "Malaria is a life-threatening infectious disease caused by Plasmodium parasites, transmitted by Anopheles mosquitoes.",
            new String[]{"Fever", "Chills", "Headache", "Sweating", "Nausea"},
            new String[]{"Use insecticide-treated nets", "Take antimalarial drugs when prescribed", "Avoid mosquito bites"});

        addLocal("dengue",
            "Dengue is a mosquito-borne viral infection causing high fever, severe headache, pain behind the eyes, joint pain and rash.",
            new String[]{"High fever", "Severe headache", "Joint and muscle pain", "Rash"},
            new String[]{"Avoid mosquito bites", "Remove standing water", "Use repellents and nets"});

        addLocal("jock itch",
            "Jock itch (tinea cruris) is a fungal infection of the groin area causing itchy, red, ring-shaped rashes.",
            new String[]{"Itchy groin rash", "Redness", "Scaling"},
            new String[]{"Keep the area dry", "Use antifungal powders", "Avoid tight clothing"});

        addLocal("diarrhea",
            "Diarrhea (loose motions) involves frequent watery stools, often caused by infections or contaminated food/water. The main risk is dehydration.",
            new String[]{"Loose stools", "Abdominal cramps", "Dehydration"},
            new String[]{"Oral rehydration solutions (ORS)", "Safe drinking water", "Hand hygiene"});

        addLocal("covid-19",
            "COVID-19 is an infectious disease caused by the SARS-CoV-2 virus, affecting the respiratory tract and sometimes other organs.",
            new String[]{"Fever", "Cough", "Loss of smell/taste", "Shortness of breath"},
            new String[]{"Vaccination", "Masking in crowded places", "Hand hygiene"});

        addLocal("pink eye",
            "Conjunctivitis (pink eye) is inflammation of the conjunctiva due to viruses, bacteria or allergies, causing red eye and discharge.",
            new String[]{"Redness", "Discharge", "Itching"},
            new String[]{"Avoid touching eyes", "Hand hygiene", "See doctor if vision changes"});

        addLocal("typhoid",
            "Typhoid fever is an infection by Salmonella Typhi, spread through contaminated food or water, causing prolonged fever and systemic illness.",
            new String[]{"High fever", "Stomach pain", "Headache", "Weakness"},
            new String[]{"Safe water and food", "Hand hygiene", "Vaccination in high-risk areas"});

        addLocal("stomach flu",
            "Gastroenteritis (stomach flu) causes vomiting, diarrhea and abdominal cramps often due to viral or bacterial infections.",
            new String[]{"Nausea", "Vomiting", "Diarrhea", "Abdominal cramps"},
            new String[]{"Hydration", "Safe food handling", "Hand hygiene"});

        addLocal("asthma",
            "Asthma is a chronic lung condition causing wheeze, breathlessness and chest tightness; managed with inhalers and trigger avoidance.",
            new String[]{"Wheezing", "Shortness of breath", "Chest tightness"},
            new String[]{"Use prescribed inhalers", "Avoid triggers", "Regular follow-ups"});
        // add more as needed
    }

    private static void addLocal(String key, String desc, String[] symptoms, String[] prevention) {
        JSONObject o = new JSONObject();
        o.put("name", key);
        o.put("description", desc);
        o.put("symptoms", new JSONArray(Arrays.asList(symptoms)));
        o.put("prevention", new JSONArray(Arrays.asList(prevention)));
        localDB.put(key.toLowerCase(), o);
    }

    private static JSONObject findLocal(String query) {
        String q = query.toLowerCase();
        if (localDB.containsKey(q)) return localDB.get(q);
        // check substring keys
        for (String k : localDB.keySet()) {
            if (q.contains(k) || k.contains(q)) return localDB.get(k);
        }
        // tokens
        for (String token : q.split("\\s+")) {
            if (localDB.containsKey(token)) return localDB.get(token);
        }
        return null;
    }

    // === Utility helpers ===
    private static boolean isGreeting(String low) {
        String[] g = {"hi","hello","hey","good morning","good afternoon","good evening"};
        for (String s : g) if (low.contains(s)) return true;
        return false;
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return null;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = br.readLine()) != null) sb.append(l).append('\n');
            return sb.toString().trim();
        }
    }

    private static void sendJson(HttpExchange ex, int code, JSONObject obj) throws IOException {
        byte[] b = obj.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private static void sendPlain(HttpExchange ex, int code, String txt) throws IOException {
        byte[] b = txt.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private static void enableCORS(HttpExchange ex) {
        Headers h = ex.getResponseHeaders();
        h.add("Access-Control-Allow-Origin", "*");
        h.add("Access-Control-Allow-Headers", "Content-Type");
    }

    private static String capitalizeWords(String s) {
        String[] parts = s.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.length() == 0) continue;
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(" ");
        }
        return sb.toString().trim();
    }
}

