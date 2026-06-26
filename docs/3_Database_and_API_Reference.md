# 3. Database Schema & API Reference

Tài liệu này cung cấp chi tiết về cách hệ thống lưu trữ dữ liệu State của Agent, cũng như cách tương tác thông qua REST API.

## 3.1 Database Schema (PostgreSQL)

Hệ thống sử dụng **PostgreSQL** để lưu trữ State (phục vụ việc khôi phục và rollback khi có lỗi) và **Redis** để làm Memory lưu trữ Context trò chuyện ngắn hạn. Do hệ thống sử dụng Hibernate (`ddl-auto: update`), một số bảng sẽ được tạo tự động từ JPA Entities.

Các bảng chính trong hệ thống (dựa theo Entities trong `adapters/memory`):

1. **`agent_runs`**: Lưu trữ trạng thái tổng thể của một lần kích hoạt hệ thống.
   - `id` (UUID - PK): Khóa chính.
   - `thread_id` (String): ID của cuộc trò chuyện (Nhóm các run lại với nhau).
   - `status` (Enum): Trạng thái của run (VD: `RUNNING`, `SUCCESS`, `FAILED`, `AWAITING_APPROVAL`).
   - `memento_data` (JSONB): Chứa toàn bộ state của DAG, các steps, observations được serialize dạng JSON. Cho phép khôi phục nguyên trạng (Hydrate) khi resume.
   - `created_at` / `updated_at`: Timestamps.

2. **`evaluation` schema / bảng (từ `schema.sql` / `AgentEvaluationEntity`)**:
   Lưu trữ các đánh giá về chất lượng, chi phí, hoặc metics đo lường agent.

> [!NOTE]
> Mọi thay đổi state trong vòng lặp ReAct đều được thực hiện qua `BatchTransactionCoordinator` để đảm bảo nếu một Step bị Exception không mong muốn (Crash server), hệ thống sẽ không bị mất dấu vết (Rollback an toàn).

## 3.2 REST API Reference

Ứng dụng phơi bày REST APIs phục vụ cho Front-end Dashboard hoặc các client bên ngoài gọi tới.

### 1. Initiate Agent Task
Gửi yêu cầu mới bằng ngôn ngữ tự nhiên để Agent xử lý.

- **URL**: `/api/v1/agent/chat`
- **Method**: `POST`
- **Headers**: `Content-Type: application/json`

**Request Body**:
```json
{
  "prompt": "Search the active database for user Bob. If not found, search the archive database for Bob, then calculate his metrics."
}
```

**Response (Success - Synchronous completion)**:
```json
{
  "threadId": "f8c61d5b-f5a5-418d-acaa-d80bb86201d2",
  "runId": "1c8eda30-e976-4aab-9b81-5a909f621cd8",
  "status": "SUCCESS",
  "result": "All planned steps were completed successfully.",
  "suspendedStepId": null,
  "suspendedToolName": null,
  "suspendedToolArgs": null
}
```

**Response (Suspended - Awaiting Approval)**:
Nếu Agent kích hoạt một công cụ mang tính thay đổi (Mutating tool) như `write_database`, API sẽ trả về trạng thái chờ.
```json
{
  "threadId": "f8c61d5b-f5a5-418d-acaa-d80bb86201d2",
  "runId": "1c8eda30-e976-4aab-9b81-5a909f621cd8",
  "status": "AWAITING_APPROVAL",
  "result": null,
  "suspendedStepId": "step_write_db_1",
  "suspendedToolName": "write_database",
  "suspendedToolArgs": "{\"query\": \"name=Bob\"}"
}
```

---

### 2. Resume Suspended Task (Human-in-the-Loop)
Tiếp tục một tác vụ đang bị treo.

- **URL**: `/api/v1/agent/resume`
- **Method**: `POST`
- **Headers**: `Content-Type: application/json`

**Request Body**:
```json
{
  "threadId": "f8c61d5b-f5a5-418d-acaa-d80bb86201d2",
  "runId": "1c8eda30-e976-4aab-9b81-5a909f621cd8",
  "decision": "APPROVED", // Hoặc "REJECTED", "FEEDBACK"
  "feedback": "Looks good, proceed",
  "modifiedToolArgs": "{\"query\": \"name=Bob,status=active\"}" // Dùng nếu muốn thay đổi thông số gửi đi
}
```

**Response**:
Tương tự như API `/chat`, nó sẽ trả về `SUCCESS` nếu hoàn thành, hoặc `AWAITING_APPROVAL` nếu luồng tiếp theo lại vướng một Mutating tool khác.

---

### 3. Actuator Health
Dùng để DevOps monitor trạng thái sống của Server.

- **URL**: `/actuator/health`
- **Method**: `GET`

**Response**:
```json
{"status":"UP"}
```
