-- 데이터베이스 생성 및 선택 (한글 깨짐 방지 설정 포함)
CREATE DATABASE IF NOT EXISTS autoschedule DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE autoschedule;

-- 1. 사용자 테이블
CREATE TABLE IF NOT EXISTS user (
    user_id INT AUTO_INCREMENT PRIMARY KEY,
    kakao_id VARCHAR(100) UNIQUE NOT NULL COMMENT '소셜 로그인 식별자',
    role ENUM('EMPLOYER', 'WORKER') NOT NULL COMMENT '역할',
    name VARCHAR(50) NOT NULL COMMENT '이름',
    contact VARCHAR(20) NOT NULL COMMENT '연락처'
);

-- 2. 매장 테이블
CREATE TABLE IF NOT EXISTS store (
    store_id INT AUTO_INCREMENT PRIMARY KEY,
    employer_id INT NOT NULL COMMENT '고용주(User) 참조',
    store_name VARCHAR(100) NOT NULL COMMENT '상호명',
    is_under_5 BOOLEAN NOT NULL DEFAULT TRUE COMMENT '5인 미만 여부',
    FOREIGN KEY (employer_id) REFERENCES user(user_id) ON DELETE CASCADE
);

-- 3. 매장-직원 연결 테이블 (N:M 해소)
CREATE TABLE IF NOT EXISTS store_worker (
    mapping_id INT AUTO_INCREMENT PRIMARY KEY,
    store_id INT NOT NULL,
    worker_id INT NOT NULL,
    hourly_wage INT NOT NULL COMMENT '적용 시급',
    tax_type ENUM('BUSINESS_TAX', '4_INSURANCE', 'NONE') NOT NULL DEFAULT 'NONE' COMMENT '공제 유형',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '근무 상태',
    FOREIGN KEY (store_id) REFERENCES store(store_id) ON DELETE CASCADE,
    FOREIGN KEY (worker_id) REFERENCES user(user_id) ON DELETE CASCADE
);

-- 4. 근무표 테이블
CREATE TABLE IF NOT EXISTS schedule (
    schedule_id INT AUTO_INCREMENT PRIMARY KEY,
    mapping_id INT NOT NULL COMMENT '직원 매핑 참조',
    work_date DATE NOT NULL COMMENT '근무 일자',
    start_time TIME NOT NULL COMMENT '예정 출근 시간',
    end_time TIME NOT NULL COMMENT '예정 퇴근 시간',
    is_substitute BOOLEAN NOT NULL DEFAULT FALSE COMMENT '대타 근무 여부(주휴수당 제외)',
    FOREIGN KEY (mapping_id) REFERENCES store_worker(mapping_id) ON DELETE CASCADE
);

-- 5. 출퇴근 기록 테이블
CREATE TABLE IF NOT EXISTS attendance (
    attendance_id INT AUTO_INCREMENT PRIMARY KEY,
    schedule_id INT NOT NULL,
    actual_start DATETIME COMMENT '실제 출근 시간',
    actual_end DATETIME COMMENT '실제 퇴근 시간',
    overtime_minutes INT DEFAULT 0 COMMENT '연장 근무 시간(분)',
    check_method ENUM('QR') NOT NULL COMMENT '인증 방식',
    holiday_pay_rate INT DEFAULT 0 COMMENT '휴일 근로 수당 가산율(%)',
    FOREIGN KEY (schedule_id) REFERENCES schedule(schedule_id) ON DELETE CASCADE
);

-- 6. 대타/휴무 신청 테이블
CREATE TABLE IF NOT EXISTS substitute_request (
    request_id INT AUTO_INCREMENT PRIMARY KEY,
    schedule_id INT NOT NULL,
    requester_id INT NOT NULL COMMENT '신청자 ID',
    taker_id INT COMMENT '수락자 ID (수락 전엔 NULL)',
    reason TEXT COMMENT '휴무 사유',
    status ENUM('PENDING', 'ACCEPTED', 'CANCELED') NOT NULL DEFAULT 'PENDING',
    FOREIGN KEY (schedule_id) REFERENCES schedule(schedule_id) ON DELETE CASCADE,
    FOREIGN KEY (requester_id) REFERENCES user(user_id) ON DELETE CASCADE,
    FOREIGN KEY (taker_id) REFERENCES user(user_id) ON DELETE SET NULL
);

-- 7. 급여 정산 테이블 (오류 수정: year_month -> payroll_month)
CREATE TABLE IF NOT EXISTS payroll (
    payroll_id INT AUTO_INCREMENT PRIMARY KEY,
    mapping_id INT NOT NULL,
    payroll_month VARCHAR(7) NOT NULL COMMENT '정산 연월 (예: 2026-10)',
    total_work_time INT NOT NULL COMMENT '총 근무 시간(분 단위 권장)',
    total_holiday_allowance INT DEFAULT 0 COMMENT '휴일 가산 수당 총액',
    total_overtime_allowance INT DEFAULT 0 COMMENT '연장 근로 수당 총액',
    weekly_holiday_allowance INT DEFAULT 0 COMMENT '주휴수당 금액',
    deduction_amount INT DEFAULT 0 COMMENT '공제 금액(세금/4대보험)',
    final_salary INT NOT NULL COMMENT '실수령액',
    FOREIGN KEY (mapping_id) REFERENCES store_worker(mapping_id) ON DELETE CASCADE
);

-- 8. 노무 서류 테이블
CREATE TABLE IF NOT EXISTS compliance (
    compliance_id INT AUTO_INCREMENT PRIMARY KEY,
    mapping_id INT NOT NULL,
    health_cert_expiry DATE COMMENT '보건증 만료일',
    contract_doc_url VARCHAR(255) COMMENT '근로계약서 경로',
    FOREIGN KEY (mapping_id) REFERENCES store_worker(mapping_id) ON DELETE CASCADE
);

-- 9. 공지사항 테이블
CREATE TABLE IF NOT EXISTS notice (
    notice_id INT AUTO_INCREMENT PRIMARY KEY,
    store_id INT NOT NULL,
    content TEXT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (store_id) REFERENCES store(store_id) ON DELETE CASCADE
);