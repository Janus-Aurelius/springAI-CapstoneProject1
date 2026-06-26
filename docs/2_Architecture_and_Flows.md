# 2. Architecture & Execution Flows

Tài liệu này đi sâu vào kiến trúc phần mềm và giải thích luồng hoạt động (lifecycle) của một Task từ lúc người dùng gửi yêu cầu đến khi hệ thống hoàn thành.

## 2.1 Kiến trúc Hexagonal (Ports & Adapters)

Ứng dụng tuân thủ nghiêm ngặt mô hình **Ports and Adapters**:

```mermaid
flowchart TD
    %% Styling
    classDef inbound fill:#e1f5fe,stroke:#0288d1,stroke-width:2px,color:#000
    classDef core fill:#e8f5e9,stroke:#388e3c,stroke-width:2px,color:#000
    classDef outbound fill:#fff3e0,stroke:#f57c00,stroke-width:2px,color:#000

    subgraph Primary[Inbound Adapters (Driving)]
        direction LR
        API[Web REST API]:::inbound
        WS[WebSockets]:::inbound
        UI[Dashboard Controller]:::inbound
    end

    subgraph Hexagon[Core Domain & Engine]
        direction TB
        Router[Task Router & Planner]:::core
        Engine[Execution Engine (Java 21 VT)]:::core
        State[State Management & DAGs]:::core
        SPI[Core SPIs & Ports]:::core
        
        Router --> Engine
        Engine <--> State
        Engine --> SPI
    end

    subgraph Secondary[Outbound Adapters (Driven)]
        direction LR
        DB[(PostgreSQL)]:::outbound
        Cache[(Redis)]:::outbound
        LLM{LlmProviderRegistry}:::outbound
        Tools[Tool Executors (MCP/Local)]:::outbound
    end

    Primary -->|Drives| Hexagon
    SPI -->|Invokes| Secondary
```

### Ưu điểm của kiến trúc này:
1. **Core Domain Độc Lập**: Logic lên kế hoạch (`Planner`), suy luận (`Reasoner`) và thực thi (`ExecutionEngine`) trong package `core/engine/` hoàn toàn mù mờ về Spring Boot, PostgreSQL hay OpenAI. Chúng giao tiếp ra ngoài qua các interface (`MemoryStore`, `LlmProvider`, `ToolExecutor`).
2. **Dễ Test**: Có thể thay thế Adapter bằng Mock/In-memory store dễ dàng trong quá trình viết Unit Test.

## 2.2 Luồng chạy chính (Execution Flow)

Luồng của hệ thống khi nhận 1 Prompt được chia làm nhiều pha (Phases) chạy qua `ExecutionEngine`.

```text
HTTP Request
     │
     ▼
 TaskRouter  ──► Simple? ──► Trả lời LLM trực tiếp (Bỏ qua Engine)
     │
     ▼ Complex
 ExecutionEngine
     │
     ├── 1. Planner (Sinh ra DAG của các Steps)
     │
     └── 2. DAG Scheduler (Lên lịch chạy song song bằng Virtual Threads)
           │
           ▼
        3. Reasoner (Phân tích Step hiện tại) ──► Action (Quyết định dùng Tool) ──► ToolExecutor
           │◄─────────────────── Observation (Kết quả từ Tool) ───────────────────────┘
           │
           ├── FinalAnswer ──► Đánh dấu Step là SUCCESS
           ├── Replan      ──► Báo lỗi, yêu cầu Planner tạo Plan mới (PIVOT)
           └── Mutating Tool ──► Chặn thực thi, đưa vào trạng thái SUSPEND → Chờ /resume
```

### Pha 1: Lập kế hoạch (Planning)
- `TaskRouter` nhận diện Prompt là phức tạp -> Đẩy vào `ExecutionEngine`.
- `Planner` nhận toàn bộ yêu cầu, kêu gọi LLM chia bài toán thành các `Step` rời rạc. Mỗi Step khai báo rõ `dependencies` (Nó phụ thuộc vào bước nào trước đó).
- Cấu trúc dữ liệu sinh ra là một **DAG (Directed Acyclic Graph)**.

### Pha 2: Lập lịch và Thực thi song song (DAG Scheduling)
- `DAG Scheduler` duyệt qua các Step trong Plan. Bước nào không có dependency (hoặc dep đã xong) sẽ được đưa vào hàng chờ chạy ngay.
- Mỗi Step được giao cho một **Virtual Thread** riêng biệt để chạy `Reasoner`. Việc này giúp hệ thống chờ mạng (I/O, gọi LLM, gọi Tools) siêu hiệu quả mà không tốn CPU.

### Pha 3: Suy luận (Reasoning & ReAct Loop)
Mỗi Step áp dụng logic **ReAct (Reason + Act)**:
1. **THINK**: Reasoner phân tích trạng thái Step, lịch sử chat.
2. **ACT**: Quyết định gọi công cụ (`Tool`).
3. **OBSERVE**: Thực thi công cụ và lấy kết quả.
4. **SELF-CORRECTION (Recourse)**: Nếu Tool báo lỗi (Ví dụ: Tìm không thấy Bob trong Active DB), Reasoner tự suy luận lại, nhận ra mình cần tìm trong Archive DB, và gọi Tool khác mà không cần sự can thiệp của người dùng.

### Pha 4: Human-in-the-Loop (HITL)
Nếu Reasoner quyết định dùng một công cụ có tính chất **thay đổi dữ liệu** (Mutating Tool, VD: `write_database`):
- `ExecutionEngine` bắt giữ (intercept) hành động này.
- Ngừng thực thi ngay lập tức, lưu trạng thái DAG vào Database bằng Memento pattern (`AgentContextMemento`).
- Đổi status Run thành `AWAITING_APPROVAL`.
- Người dùng review qua Dashboard. Nếu `/resume` bằng lệnh Approve, hệ thống nạp lại Memento từ DB và chạy tiếp Tool. Nếu Reject, Reasoner sẽ nhận được Observation là "User rejected" và tiến hành đổi Plan (Pivot).

## 2.3 Các module quan trọng khác

### Data Loss Prevention (DLP) & Secret Redactor
Nằm ở `adapters/security/DefaultSecretRedactor`.
Trước khi ToolExecutor truyền dữ liệu thực cho MCP Tool hoặc Docker, nó sẽ chạy qua Redactor. Redactor dùng Regex hoặc LLM để tìm kiếm các đoạn text chứa mật khẩu, API key, token... và che mờ (mask) chúng lại thành `[REDACTED]` để đảm bảo an toàn nếu script độc hại bị lọt vào sandbox.

### Sandbox Manager
Các công cụ dạng `execute_code` (Thực thi Python/JS) không chạy thẳng trên máy chủ Java. `DockerManagedSandbox` sẽ request Docker API tạo một Container dùng 1 lần (Ephemeral Container) được cắm vào mạng nội bộ `sandbox_net` (Bị giới hạn bởi Squid Proxy) để chạy script, trả về stdout/stderr, sau đó xoá container.
