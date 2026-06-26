# 4. Configuration & Deployment

Tài liệu này bao quát cách cấu hình ứng dụng thông qua biến môi trường (Environment Variables) và hướng dẫn triển khai ứng dụng (Deployment) bằng Docker.

## 4.1 Cấu hình Biến môi trường (.env)

Hệ thống được thiết kế linh hoạt bằng cách externalize (tách) toàn bộ cấu hình ra biến môi trường. Điều này giúp dễ dàng chuyển đổi qua lại giữa các môi trường Dev / Staging / Prod mà không cần build lại mã nguồn.

File mẫu là `.env.example`.

### LLM Fleet Configuration (Multi-Project)
Hệ thống sử dụng chiến lược **Resilient Multi-Provider**. LlmProviderRegistry sẽ dựa vào các key này để gọi API LLM.

- `GEMINI_PROJECT_A_API_KEY`: API Key dành cho **PLANNER**.
- `GEMINI_PROJECT_B_API_KEY`: API Key dành cho **REASONER**.
- `GEMINI_PROJECT_C_API_KEY`: API Key dành cho **JUDGE**.
- `OPENROUTER_API_KEY`: Key dự phòng (Fallback) nếu tất cả các tầng trên thất bại.

Việc tách ra nhiều Project Key (A, B, C) giúp né Rate Limit (Quota) cực kỳ hiệu quả khi scale lên Production.

### Database & Caching
- `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_DB`: Cấu hình truy cập PostgreSQL.
- `REDIS_AGENT_MEMORY_URL` / `REDIS_AGENT_MEMORY_KEY`: Cấu hình cho Redis Cloud để lưu trữ Chat Memory. Cần đảm bảo tài khoản Redis của bạn hỗ trợ JSON type.

### MCP (Model Context Protocol) Tools
Tích hợp các tool ngoài vào mô hình.
- `GITHUB_PERSONAL_ACCESS_TOKEN`: PAT Github cấp quyền cho agent (Dùng bởi `mcp-github`).
- `TAVILY_API_KEY`: Key cho Tavily Search API (Dùng bởi `mcp-tavily-search`).

### Tracing (Langfuse)
Bắt buộc cấu hình để quan sát chi phí và luồng logic LLM.
- `LANGFUSE_SECRET_KEY` / `LANGFUSE_PUBLIC_KEY`: Lấy từ dashboard của Langfuse.
- `LANGFUSE_BASE_URL`: (VD: `https://cloud.langfuse.com` hoặc server tự host).

---

## 4.2 Application Properties (`application.yml`)

Spring Boot đọc các biến trên từ file `.env` (qua thư viện hoặc docker-compose) và đưa vào cấu hình. Một vài cấu hình cứng đáng chú ý trong `application.yml`:

- `spring.threads.virtual.enabled: true`: Bật Virtual Threads (Project Loom của Java 21) để xử lý lượng connection lớn.
- `app.llm.guardrails`: Định nghĩa giới hạn cho Agent.
  - `max-actions`: Giới hạn số tool calls tối đa trong 1 session.
  - `max-replans`: Giới hạn số lần Planner được phép tự lập lại kế hoạch (chống lặp vô hạn).
- `app.llm.pricing`: Chứa thông tin giá (Cost per million tokens) để OpenTelemetry / Langfuse tính toán.
- `agent.tools.mutating`: Danh sách quan trọng. Liệt kê tên (ID) của các tools bắt buộc phải có Human-in-the-Loop (VD: `create_or_update_file`, `write_database`). Nếu bạn thêm tool mới có tính nguy hiểm, phải khai báo vào list này.

---

## 4.3 Deployment (Docker Compose)

Cách triển khai chuẩn của dự án là sử dụng `docker-compose`.

Hạ tầng đi kèm gồm có:
1. `postgres`: Lưu trữ dữ liệu hệ thống.
2. `squid`: Proxy server phục vụ Sandbox.
3. Các sidecars cho MCP: `mcp-postgres`, `mcp-github`, `mcp-puppeteer`, `mcp-tavily-search`.
4. `prometheus` và `grafana`: Hỗ trợ thu thập và hiển thị Metrics.
5. `app` (spring-ai-agent): Ứng dụng chính của chúng ta.

### Lệnh triển khai cơ bản

Xóa các container cũ, build lại image mới và khởi động trong chế độ nền (detached):
```bash
docker-compose up -d --build
```

Kiểm tra log của ứng dụng chính:
```bash
docker-compose logs -f app
```

Dừng toàn bộ hệ thống (Không xoá volume database):
```bash
docker-compose down
```

Xoá sạch cả dữ liệu (Chú ý cẩn thận):
```bash
docker-compose down -v
```
