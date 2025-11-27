-- 1. Create the Database container
CREATE DATABASE IF NOT EXISTS hostel_db;

-- 2. Select the database to use
USE hostel_db;

-- 3. Create the 'Complaints' Table
-- Uses the standard column names expected by the Java backend: room_number, type, priority
CREATE TABLE IF NOT EXISTS complaints (
    id INT AUTO_INCREMENT PRIMARY KEY,
    student_name VARCHAR(100) NOT NULL,
    room_number VARCHAR(20) NOT NULL,
    type VARCHAR(50) NOT NULL,        
    description TEXT,
    priority INT NOT NULL,  -- 1=Critical, 2=High, 3=Medium, 4=Low
    status VARCHAR(20) DEFAULT 'PENDING',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 4. Create the 'Announcements' Table
CREATE TABLE IF NOT EXISTS announcements (
    id INT AUTO_INCREMENT PRIMARY KEY,
    message TEXT NOT NULL,
    post_date DATE NOT NULL
);

-- 5. Add some starter data
INSERT INTO complaints (student_name, room_number, type, description, priority, status) VALUES 
('Rahul Kumar', '101', 'Furniture', 'Table leg broken', 3, 'PENDING'),
('Priya Singh', '204', 'Electrical (Danger)', 'Short circuit in switch board', 1, 'PENDING');

INSERT INTO announcements (message, post_date) VALUES 
('Hostel gates will close at 10:00 PM strictly.', CURDATE());