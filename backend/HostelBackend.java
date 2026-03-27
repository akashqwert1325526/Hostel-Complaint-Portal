import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// --- DATA STRUCTURE: COMPLAINT ---
class Complaint implements Comparable<Complaint> {
    int id;
    String studentName;
    String roomNumber;
    String type;
    String description;
    int priority;
    String status;
    Timestamp timestamp;
    Timestamp resolvedTimestamp;

    public Complaint(int id, String name, String room, String type, String desc, int priority, String status, Timestamp ts, Timestamp resolvedTs) {
        this.id = id;
        this.studentName = name;
        this.roomNumber = room;
        this.type = type;
        this.description = desc;
        this.priority = priority;
        this.status = status;
        this.timestamp = ts;
        this.resolvedTimestamp = resolvedTs;
    }

    @Override
    public int compareTo(Complaint other) {
        if (this.priority != other.priority) {
            return this.priority - other.priority;
        }
        return this.timestamp.compareTo(other.timestamp);
    }

    @Override
    public String toString() {
        return String.format("[Priority: %d] %s (Room %s): %s", priority, type, roomNumber, description);
    }
}

// --- MAIN SYSTEM CLASS ---
public class HostelBackend {
    private final PriorityQueue<Complaint> complaintQueue;

    // DATABASE CONFIGURATION
    private static final String DB_SERVER_URL = "jdbc:mysql://localhost:3306/";
    private static final String DB_URL = "jdbc:mysql://localhost:3306/hostel_db";
    private static final String USER = "root";
    private static final String PASS = "Sigma#5778";

    private static final int SERVER_PORT = 8080;

    public HostelBackend() {
        this.complaintQueue = new PriorityQueue<>();
    }

    private void ensureDatabaseAndTables() {
        String createDb = "CREATE DATABASE IF NOT EXISTS hostel_db";
        String useDbAndCreateComplaints =
            "CREATE TABLE IF NOT EXISTS complaints (" +
            "id INT AUTO_INCREMENT PRIMARY KEY," +
            "student_name VARCHAR(100) NOT NULL," +
            "room_number VARCHAR(20) NOT NULL," +
            "type VARCHAR(50) NOT NULL," +
            "description TEXT," +
            "priority INT NOT NULL," +
            "status VARCHAR(20) DEFAULT 'PENDING'," +
            "resolved_at TIMESTAMP NULL DEFAULT NULL," +
            "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
            ")";
        String useDbAndCreateAnnouncements =
            "CREATE TABLE IF NOT EXISTS announcements (" +
            "id INT AUTO_INCREMENT PRIMARY KEY," +
            "message TEXT NOT NULL," +
            "post_date DATE NOT NULL" +
            ")";

        try (Connection serverConn = DriverManager.getConnection(DB_SERVER_URL, USER, PASS);
             Statement serverStmt = serverConn.createStatement()) {
            serverStmt.executeUpdate(createDb);
        } catch (SQLException e) {
            System.out.println("Database bootstrap error (create DB): " + e.getMessage());
            return;
        }

        try (Connection dbConn = DriverManager.getConnection(DB_URL, USER, PASS);
             Statement dbStmt = dbConn.createStatement()) {
            dbStmt.executeUpdate(useDbAndCreateComplaints);
            try {
                dbStmt.executeUpdate("ALTER TABLE complaints ADD COLUMN resolved_at TIMESTAMP NULL DEFAULT NULL");
            } catch (SQLException alterErr) {
                // 1060 = duplicate column name. Safe to ignore when column already exists.
                if (alterErr.getErrorCode() != 1060) {
                    throw alterErr;
                }
            }
            dbStmt.executeUpdate(useDbAndCreateAnnouncements);
            System.out.println("Database bootstrap complete.");
        } catch (SQLException e) {
            System.out.println("Database bootstrap error (create tables): " + e.getMessage());
        }
    }

    public void lodgeComplaint(String name, String room, String type, String desc, int priority) {
        int dbId = saveComplaintToDB(name, room, type, desc, priority);
        if (dbId != -1) {
            Complaint newC = new Complaint(dbId, name, room, type, desc, priority, "PENDING", new Timestamp(System.currentTimeMillis()), null);
            complaintQueue.add(newC);
            System.out.println(">> Complaint Lodged: " + type + " (Priority " + priority + ")");
        } else {
            Complaint newC = new Complaint(-1, name, room, type, desc, priority, "PENDING", new Timestamp(System.currentTimeMillis()), null);
            complaintQueue.add(newC);
            System.out.println(">> Complaint Lodged (Queue Only): " + type + " (Priority " + priority + ")");
        }
    }

    public void resolveNextComplaint() {
        if (complaintQueue.isEmpty()) {
            System.out.println("No pending complaints.");
            return;
        }
        Complaint resolved = complaintQueue.poll();
        if (resolved.id != -1) {
            updateComplaintStatusInDB(resolved.id, "RESOLVED");
        }
        System.out.println(">> RESOLVED: " + resolved);
    }

    public void postAnnouncement(String text) {
        String sql = "INSERT INTO announcements (message, post_date) VALUES (?, ?)";
        try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, text);
            pstmt.setDate(2, java.sql.Date.valueOf(LocalDate.now()));
            pstmt.executeUpdate();
            System.out.println(">> Announcement Posted: " + text);
        } catch (SQLException e) {
            System.out.println("Database Error (Announcement): " + e.getMessage());
        }
    }

    private int saveComplaintToDB(String name, String room, String type, String desc, int priority) {
        String sql = "INSERT INTO complaints (student_name, room_number, type, description, priority, status) VALUES (?, ?, ?, ?, ?, 'PENDING')";

        try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS);
             PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            pstmt.setString(1, name);
            pstmt.setString(2, room);
            pstmt.setString(3, type);
            pstmt.setString(4, desc);
            pstmt.setInt(5, priority);

            pstmt.executeUpdate();

            try (ResultSet rs = pstmt.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }

        } catch (SQLException e) {
            System.out.println("Database Error (Save Complaint): " + e.getMessage());
        }
        return -1;
    }

    private boolean updateComplaintStatusInDB(int id, String status) {
        String sql;
        boolean isResolved = "RESOLVED".equalsIgnoreCase(status);
        if (isResolved) {
            sql = "UPDATE complaints SET status = ?, resolved_at = CURRENT_TIMESTAMP WHERE id = ?";
        } else {
            sql = "UPDATE complaints SET status = ?, resolved_at = NULL WHERE id = ?";
        }
        try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, status);
            pstmt.setInt(2, id);
            int updated = pstmt.executeUpdate();
            return updated > 0;
        } catch (SQLException e) {
            System.out.println("Database Error (Update Status): " + e.getMessage());
        }
        return false;
    }

    private List<Complaint> fetchComplaintsFromDB() {
        String sql = "SELECT id, student_name, room_number, type, description, priority, status, created_at, resolved_at FROM complaints";
        List<Complaint> result = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS);
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                result.add(new Complaint(
                    rs.getInt("id"),
                    rs.getString("student_name"),
                    rs.getString("room_number"),
                    rs.getString("type"),
                    rs.getString("description"),
                    rs.getInt("priority"),
                    rs.getString("status"),
                    rs.getTimestamp("created_at"),
                    rs.getTimestamp("resolved_at")
                ));
            }
        } catch (SQLException e) {
            System.out.println("Database Error (Fetch Complaints): " + e.getMessage());
        }
        return result;
    }

    private void startApiServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(SERVER_PORT), 0);
        server.createContext("/api/health", new HealthHandler());
        server.createContext("/api/complaints", new ComplaintHandler(this));
        server.createContext("/api/complaints/", new ComplaintHandler(this));
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("HTTP API started on http://localhost:" + SERVER_PORT);
    }

    private static void addCorsHeaders(Headers headers) {
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET,POST,PUT,OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type, Access-Control-Request-Private-Network");
        headers.set("Access-Control-Allow-Private-Network", "true");
        headers.set("Content-Type", "application/json; charset=utf-8");
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        InputStream in = exchange.getRequestBody();
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String jsonEscape(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r");
    }

    private static String extractString(String json, String fieldName) {
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(fieldName) + "\\\"\\s*:\\s*\\\"(.*?)\\\"");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            Pattern escapedPattern = Pattern.compile("\\\\\"" + Pattern.quote(fieldName) + "\\\\\"\\s*:\\s*\\\\\"(.*?)\\\\\"");
            Matcher escapedMatcher = escapedPattern.matcher(json);
            if (!escapedMatcher.find()) {
                return null;
            }
            return escapedMatcher.group(1)
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\\\", "\\");
        }
        return matcher.group(1)
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\\\", "\\");
    }

    private static Integer extractInt(String json, String fieldName) {
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(fieldName) + "\\\"\\s*:\\s*(\\d+)");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            return null;
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static void sendJson(HttpExchange exchange, int statusCode, String jsonBody) throws IOException {
        byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        addCorsHeaders(exchange.getResponseHeaders());
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 204, "{}");
                return;
            }

            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }

            sendJson(exchange, 200, "{\"status\":\"ok\"}");
        }
    }

    private static class ComplaintHandler implements HttpHandler {
        private final HostelBackend backend;

        ComplaintHandler(HostelBackend backend) {
            this.backend = backend;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            if ("OPTIONS".equalsIgnoreCase(method)) {
                sendJson(exchange, 204, "{}");
                return;
            }

            if ("GET".equalsIgnoreCase(method)) {
                if (!"/api/complaints".equals(path) && !"/api/complaints/".equals(path)) {
                    sendJson(exchange, 404, "{\"error\":\"Endpoint not found\"}");
                    return;
                }
                List<Complaint> complaints = backend.fetchComplaintsFromDB();
                StringBuilder json = new StringBuilder("[");
                for (int i = 0; i < complaints.size(); i++) {
                    Complaint c = complaints.get(i);
                    if (i > 0) {
                        json.append(",");
                    }
                    json.append("{")
                        .append("\"id\":").append(c.id).append(",")
                        .append("\"name\":\"").append(jsonEscape(c.studentName)).append("\",")
                        .append("\"room\":\"").append(jsonEscape(c.roomNumber)).append("\",")
                        .append("\"type\":\"").append(jsonEscape(c.type)).append("\",")
                        .append("\"desc\":\"").append(jsonEscape(c.description == null ? "" : c.description)).append("\",")
                        .append("\"priority\":").append(c.priority).append(",")
                        .append("\"status\":\"").append(jsonEscape(c.status == null ? "PENDING" : c.status)).append("\",")
                        .append("\"timestamp\":").append(c.timestamp == null ? 0 : c.timestamp.getTime()).append(",")
                        .append("\"resolvedTimestamp\":").append(c.resolvedTimestamp == null ? "null" : c.resolvedTimestamp.getTime())
                        .append("}");
                }
                json.append("]");
                sendJson(exchange, 200, json.toString());
                return;
            }

            if ("PUT".equalsIgnoreCase(method)) {
                Pattern routePattern = Pattern.compile("^/api/complaints/(\\d+)/status/?$");
                Matcher routeMatcher = routePattern.matcher(exchange.getRequestURI().getPath());
                if (!routeMatcher.matches()) {
                    sendJson(exchange, 404, "{\"error\":\"Endpoint not found\"}");
                    return;
                }

                int complaintId = Integer.parseInt(routeMatcher.group(1));
                String body = readBody(exchange);
                String status = extractString(body, "status");
                if (status == null || status.isBlank()) {
                    sendJson(exchange, 400, "{\"error\":\"Missing status field\"}");
                    return;
                }

                boolean ok = backend.updateComplaintStatusInDB(complaintId, status);
                if (!ok) {
                    sendJson(exchange, 404, "{\"error\":\"Complaint not found or not updated\"}");
                    return;
                }

                sendJson(exchange, 200, "{\"message\":\"Status updated\"}");
                return;
            }

            if (!"POST".equalsIgnoreCase(method)) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            if (!"/api/complaints".equals(path) && !"/api/complaints/".equals(path)) {
                sendJson(exchange, 404, "{\"error\":\"Endpoint not found\"}");
                return;
            }

            String body = readBody(exchange);
            String name = extractString(body, "name");
            String room = extractString(body, "room");
            String type = extractString(body, "type");
            String desc = extractString(body, "desc");
            Integer priority = extractInt(body, "priority");

            if (name == null || room == null || type == null || desc == null || priority == null) {
                sendJson(exchange, 400, "{\"error\":\"Missing required fields\"}");
                return;
            }

            int id = backend.saveComplaintToDB(name, room, type, desc, priority);
            if (id == -1) {
                sendJson(exchange, 500, "{\"error\":\"Failed to store complaint in database\"}");
                return;
            }

            backend.complaintQueue.add(new Complaint(
                id,
                name,
                room,
                type,
                desc,
                priority,
                "PENDING",
                new Timestamp(System.currentTimeMillis()),
                null
            ));

            String response = "{\"message\":\"Complaint stored\",\"id\":" + id + ",\"name\":\"" + jsonEscape(name) + "\"}";
            sendJson(exchange, 201, response);
        }
    }

    public static void main(String[] args) {
        HostelBackend system = new HostelBackend();
        system.ensureDatabaseAndTables();
        try {
            system.startApiServer();
        } catch (IOException e) {
            System.out.println("Failed to start HTTP API: " + e.getMessage());
        }
    }
}
