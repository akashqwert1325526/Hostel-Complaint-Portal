import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.PriorityQueue;
import java.time.LocalDate;

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

    public Complaint(int id, String name, String room, String type, String desc, int priority, String status, Timestamp ts) {
        this.id = id;
        this.studentName = name;
        this.roomNumber = room;
        this.type = type;
        this.description = desc;
        this.priority = priority;
        this.status = status;
        this.timestamp = ts;
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
    private PriorityQueue<Complaint> complaintQueue;
    
    // DATABASE CONFIGURATION (PASSWORD SET TO Sigma#5778)
    private static final String DB_URL = "jdbc:mysql://localhost:3306/hostel_db";
    private static final String USER = "root";
    private static final String PASS = "Sigma#5778"; 

    public HostelBackend() {
        this.complaintQueue = new PriorityQueue<>();
    }

    public void lodgeComplaint(String name, String room, String type, String desc, int priority) {
        int dbId = saveComplaintToDB(name, room, type, desc, priority);
        // Fallback logic to show Queue working even if DB save fails
        if (dbId != -1) {
            Complaint newC = new Complaint(dbId, name, room, type, desc, priority, "PENDING", new Timestamp(System.currentTimeMillis()));
            complaintQueue.add(newC);
            System.out.println(">> Complaint Lodged: " + type + " (Priority " + priority + ")");
        } else {
             Complaint newC = new Complaint(-1, name, room, type, desc, priority, "PENDING", new Timestamp(System.currentTimeMillis()));
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

    // --- DATABASE METHODS ---
    private int saveComplaintToDB(String name, String room, String type, String desc, int priority) {
        // SQL query uses standard names: room_number, type, priority, which matches the schema.sql file.
        String sql = "INSERT INTO complaints (student_name, room_number, type, description, priority, status) VALUES (?, ?, ?, ?, ?, 'PENDING')";
        
        try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS);
             PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            
            pstmt.setString(1, name);      // student_name
            pstmt.setString(2, room);      // room_number
            pstmt.setString(3, type);      // type
            pstmt.setString(4, desc);      // description
            pstmt.setInt(5, priority);     // priority
            
            pstmt.executeUpdate();

            ResultSet rs = pstmt.getGeneratedKeys();
            if (rs.next()) return rs.getInt(1);

        } catch (SQLException e) {
            System.out.println("Database Error (Save Complaint): " + e.getMessage());
        }
        return -1;
    }

    private void updateComplaintStatusInDB(int id, String status) {
        String sql = "UPDATE complaints SET status = ? WHERE id = ?";
        try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, status);
            pstmt.setInt(2, id);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.out.println("Database Error (Update Status): " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        HostelBackend system = new HostelBackend();
        
        System.out.println("--- HOSTEL BACKEND SYSTEM STARTED ---");

        system.postAnnouncement("Water tank cleaning scheduled for Sunday.");

        // Testing Priority Queue Logic
        // 1. Low Priority (3)
        system.lodgeComplaint("John", "101", "Furniture", "Broken Chair", 3); 
        // 2. Critical Priority (1)
        system.lodgeComplaint("Alice", "205", "Electrical (Danger)", "Sparking Socket", 1); 
        // 3. Low Priority (3)
        system.lodgeComplaint("Bob", "302", "Internet", "Wifi Slow", 3); 

        System.out.println("\n--- WARDEN STARTING WORK ---");
        // Should resolve Alice (Priority 1) first
        system.resolveNextComplaint(); 
        // Should resolve John (Priority 3) next
        system.resolveNextComplaint();
    }
}