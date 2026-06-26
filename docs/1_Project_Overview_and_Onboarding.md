# 1. Project Overview & Onboarding

Chào mừng bạn đến với dự án **Spring Boot Agentic Orchestrator** (Tên mã: Janus Aurelius). Tài liệu này cung cấp cái nhìn tổng quan về hệ thống và hướng dẫn onboarding cho thành viên mới (Dev, QA, DevOps).

## 1.1 Tổng quan dự án

- **Loại dự án**: Enterprise-grade Agentic Orchestration Platform / Web App & API.
- **Mục tiêu**: Xây dựng một nền tảng nhận các yêu cầu bằng ngôn ngữ tự nhiên (Goals) từ người dùng, sau đó sử dụng các mô hình ngôn ngữ lớn (LLM) để dịch các yêu cầu này thành một chuỗi các thao tác thực thi (Execution steps). Điểm đặc biệt của nền tảng là khả năng **tự động lập kế hoạch (Planning)** thành Đồ thị có hướng không tuần hoàn (DAG), **thực thi song song** (dùng Java 21 Virtual Threads) và **tự động sửa lỗi (Self-Correction/Recourse)**.
- **Tính năng nổi bật**:
  - Hỗ trợ Human-in-the-Loop (HITL) để duyệt các thao tác nguy hiểm (Mutating tools).
  - Tích hợp Model Context Protocol (MCP) clients cho Postgres, GitHub, Puppeteer, Tavily.
  - Data Loss Prevention (DLP) tích hợp sẵn để che giấu bí mật (secrets).
  - Giao diện console tương tác bằng HTMX.

## 1.2 Tech Stack

Hệ thống sử dụng các công nghệ hiện đại tập trung vào hiệu năng và khả năng bảo trì:

| Category | Technology |
| :--- | :--- |
| **Language** | Java 21 (sử dụng Virtual Threads) |
| **Core Framework** | Spring Boot v4.0.6, Spring AI v2.0.0-M8 |
| **Database** | PostgreSQL 16 (Runtime), H2 (Testing) |
| **Caching / Memory** | Redis Cloud (Agent Chat Memory) |
| **Container & Sandbox** | Docker (sử dụng Docker Java API Transport), Squid Proxy |
| **Observability** | OpenTelemetry, Prometheus, Grafana, Langfuse |
| **Frontend** | Thymeleaf, HTMX, TailwindCSS |
| **Resilience** | Resilience4j (Retry, Exponential Backoff) |

## 1.3 Cấu trúc thư mục (File Tree)

Dự án áp dụng chặt chẽ kiến trúc **Ports and Adapters (Hexagonal Architecture)**. 

```text
springAI-CapstoneProject1/
├── src/main/java/com/springagentic/springaiagent/
│   ├── adapters/      # Nơi chứa các implementation giao tiếp với bên ngoài
│   │   ├── llm/       # Kết nối tới các LLM Providers (Gemini, OpenRouter, v.v.)
│   │   ├── memory/    # Giao tiếp với Redis, PostgreSQL (JPA, RestClients)
│   │   ├── sandbox/   # Logic điều khiển Docker container và proxy
│   │   ├── security/  # Data Loss Prevention (DLP), Secret Redactor
│   │   ├── tools/     # Tích hợp MCP và thực thi local tools
│   │   └── web/       # REST Controllers, WebSocket, HTMX Dashboard
│   ├── core/          # Business Logic (KHÔNG phụ thuộc framework bên ngoài)
│   │   ├── domain/    # AgentContext, Plan, Step, ToolSchema (Entities/Value Objects)
│   │   ├── engine/    # Não bộ: Planner, Reasoner, TaskRouter, ExecutionEngine
│   │   ├── sandbox/   # Core interfaces cho code sandboxing
│   │   ├── security/  # Core security interfaces
│   │   └── spi/       # Service Provider Interfaces (Ports do adapters implement)
│   └── framework/     # Cấu hình Spring (Beans, Properties, Interceptors)
├── src/main/resources/
│   ├── application.yml   # Cấu hình chính của Spring Boot
│   ├── guardrails.yml    # Cấu hình giới hạn cho AI (VD: max-tokens, max-replans)
│   ├── sandbox/          # File config cho Squid proxy, Grafana, Prometheus
│   └── templates/        # File HTML cho Frontend (HTMX)
├── docker-compose.yml    # File khởi chạy các dịch vụ hạ tầng
└── pom.xml               # Danh sách thư viện Maven
```

## 1.4 Hướng dẫn Setup & Chạy Local

### Điều kiện tiên quyết
- **Java**: JDK 21
- **Docker**: Docker Desktop hoặc Docker Engine v24+ (Yêu cầu quyền truy cập vào `/var/run/docker.sock`).
- **Maven**: (Có thể dùng `./mvnw` tích hợp sẵn trong repo).

### Bước 1: Cấu hình biến môi trường
Sao chép file `.env.example` thành `.env`:
```bash
cp .env.example .env
```
Mở file `.env` và điền các API key:
- Nhóm `GEMINI_PROJECT_A/B/C_API_KEY`: API Key của mô hình Gemini (hoặc Groq/OpenAI nếu đã đổi base url).
- Cấu hình MCP (Tuỳ chọn): `GITHUB_PERSONAL_ACCESS_TOKEN`, `TAVILY_API_KEY`.
- Cấu hình Langfuse (Bắt buộc nếu không muốn lỗi tracing): `LANGFUSE_PUBLIC_KEY`, `LANGFUSE_SECRET_KEY`.

### Bước 2: Khởi động hạ tầng (Infrastructure)
Chạy tất cả các dịch vụ (PostgreSQL, Redis, MCP Servers, Grafana, Prometheus, Squid):
```bash
docker-compose up -d
```
> [!NOTE]
> Container `mcp-puppeteer` có healthcheck khá dài (do tải Chromium), hãy kiên nhẫn chờ đến khi container báo trạng thái `(healthy)`.

### Bước 3: Chạy Spring Boot App
Trong lúc code/phát triển, bạn chạy app bằng Maven wrapper:
```bash
# Trên Linux / macOS
./mvnw spring-boot:run

# Trên Windows
.\mvnw.cmd spring-boot:run
```

### Bước 4: Kiểm tra thành quả
- **Giao diện Dashboard**: Mở trình duyệt vào `http://localhost:8080/dashboard`.
- **API Swagger/OpenAPI**: `http://localhost:8080/swagger-ui/index.html`.
- **API Healthcheck**: `http://localhost:8080/actuator/health`.
